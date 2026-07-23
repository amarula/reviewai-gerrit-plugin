/*
 * Copyright (c) 2026. The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlesource.gerrit.plugins.reviewai.metrics.cost;

import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.readJsonResource;
import static com.googlesource.gerrit.plugins.reviewai.utils.StringUtils.stripQuotes;

import com.googlesource.gerrit.plugins.reviewai.config.AiModelRoute;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class AiPricingCatalog {
  private static final String DEFAULT_PRICING_RESOURCE = "config/aiPricing.json";
  private static final Set<String> OVERRIDE_FIELDS =
      Set.of(
          "input",
          "cachedInput",
          "cacheWrite",
          "output",
          "longThreshold",
          "longInput",
          "longCachedInput",
          "longCacheWrite",
          "longOutput");

  private final Map<AiModelRoute, ModelPricing> pricingByRoute;

  AiPricingCatalog(List<String> configuredOverrides) {
    pricingByRoute = defaultPricing();
    if (configuredOverrides != null) {
      configuredOverrides.forEach(this::applyOverride);
    }
  }

  Optional<ModelPricing> find(AiModelRoute route) {
    return Optional.ofNullable(pricingByRoute.get(route));
  }

  private void applyOverride(String configuredOverride) {
    try {
      String[] parts = stripQuotes(configuredOverride).split(",");
      Optional<AiModelRoute> route = AiModelRoute.parse(parts[0].trim());
      if (route.isEmpty()) {
        throw new IllegalArgumentException("invalid provider/model route");
      }

      Map<String, String> values = new HashMap<>();
      for (int i = 1; i < parts.length; i++) {
        String[] field = parts[i].trim().split("=", 2);
        if (field.length != 2 || !OVERRIDE_FIELDS.contains(field[0])) {
          throw new IllegalArgumentException("invalid pricing field: " + parts[i].trim());
        }
        values.put(field[0], field[1].trim());
      }
      pricingByRoute.put(route.get(), pricingFrom(values));
    } catch (RuntimeException e) {
      log.warn("Ignoring invalid aiPricing entry `{}`: {}", configuredOverride, e.getMessage());
    }
  }

  private static ModelPricing pricingFrom(Map<String, String> values) {
    BigDecimal input = requiredRate(values, "input");
    BigDecimal cachedInput = rate(values, "cachedInput", input);
    BigDecimal cacheWrite = rate(values, "cacheWrite", input);
    BigDecimal output = requiredRate(values, "output");
    ModelPricing.Rates standardRates = rates(input, cachedInput, cacheWrite, output);

    if (!values.containsKey("longThreshold")) {
      return new ModelPricing(standardRates, null, null);
    }
    int longThreshold = Integer.parseInt(values.get("longThreshold"));
    if (longThreshold <= 0) {
      throw new IllegalArgumentException("longThreshold must be positive");
    }
    BigDecimal longInput = rate(values, "longInput", input);
    ModelPricing.Rates longRates =
        rates(
            longInput,
            rate(values, "longCachedInput", cachedInput),
            rate(values, "longCacheWrite", longInput),
            rate(values, "longOutput", output));
    return new ModelPricing(standardRates, longThreshold, longRates);
  }

  private static BigDecimal requiredRate(Map<String, String> values, String name) {
    if (!values.containsKey(name)) {
      throw new IllegalArgumentException("missing required field: " + name);
    }
    return rate(values, name, null);
  }

  private static BigDecimal rate(
      Map<String, String> values, String name, BigDecimal defaultValue) {
    if (!values.containsKey(name)) {
      return defaultValue;
    }
    BigDecimal value = new BigDecimal(values.get(name));
    if (value.signum() < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    return value;
  }

  private static Map<AiModelRoute, ModelPricing> defaultPricing() {
    PricingCatalogData data = readJsonResource(DEFAULT_PRICING_RESOURCE, PricingCatalogData.class);
    if (data == null || !"USD".equals(data.currency) || !"perMillionTokens".equals(data.unit)) {
      throw new IllegalStateException(
          "Pricing catalog must contain USD rates per million tokens");
    }
    if (data.models == null) {
      throw new IllegalStateException("Pricing catalog does not contain a models array");
    }

    Map<AiModelRoute, ModelPricing> pricing = new HashMap<>();
    for (PricingEntry entry : data.models) {
      add(pricing, entry);
    }
    return pricing;
  }

  private static void add(Map<AiModelRoute, ModelPricing> pricing, PricingEntry entry) {
    if (entry == null || entry.model == null || entry.model.isBlank()) {
      throw new IllegalStateException("Pricing catalog contains an entry without a model");
    }
    AiProviderType provider =
        AiProviderType.fromConfigName(entry.provider)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Pricing catalog contains an invalid provider: " + entry.provider));
    AiModelRoute route = new AiModelRoute(provider, entry.model);
    ModelPricing previous = pricing.put(route, pricingFrom(entry.values()));
    if (previous != null) {
      throw new IllegalStateException("Pricing catalog contains a duplicate route: " + route);
    }
  }

  private static ModelPricing.Rates rates(
      BigDecimal input, BigDecimal cachedInput, BigDecimal cacheWrite, BigDecimal output) {
    return new ModelPricing.Rates(input, cachedInput, cacheWrite, output);
  }

  private static final class PricingCatalogData {
    private String currency;
    private String unit;
    private List<PricingEntry> models;
  }

  private static final class PricingEntry {
    private String provider;
    private String model;
    private BigDecimal input;
    private BigDecimal cachedInput;
    private BigDecimal cacheWrite;
    private BigDecimal output;
    private Integer longThreshold;
    private BigDecimal longInput;
    private BigDecimal longCachedInput;
    private BigDecimal longCacheWrite;
    private BigDecimal longOutput;

    private Map<String, String> values() {
      Map<String, String> values = new HashMap<>();
      put(values, "input", input);
      put(values, "cachedInput", cachedInput);
      put(values, "cacheWrite", cacheWrite);
      put(values, "output", output);
      put(values, "longThreshold", longThreshold);
      put(values, "longInput", longInput);
      put(values, "longCachedInput", longCachedInput);
      put(values, "longCacheWrite", longCacheWrite);
      put(values, "longOutput", longOutput);
      return values;
    }

    private static void put(Map<String, String> values, String name, Object value) {
      if (value != null) {
        values.put(name, value.toString());
      }
    }
  }
}

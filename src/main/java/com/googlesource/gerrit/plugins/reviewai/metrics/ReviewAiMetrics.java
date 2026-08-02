/*
 * Copyright (c) 2026. Amarula Solutions
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

package com.googlesource.gerrit.plugins.reviewai.metrics;

import com.google.common.annotations.VisibleForTesting;
import com.google.gerrit.metrics.Counter2;
import com.google.gerrit.metrics.Counter3;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Field;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.metrics.Timer2;
import com.google.gerrit.metrics.Timer3;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.utils.TimeUtils;
import java.util.concurrent.TimeUnit;

@Singleton
public class ReviewAiMetrics {
  private static final String UNKNOWN_VALUE = "unknown";

  private final Counter2<String, String> reviewRunCount;
  private final Timer2<String, String> reviewRunLatency;
  private final Counter3<String, String, String> aiRequestCount;
  private final Timer3<String, String, String> aiRequestLatency;
  private final Counter2<String, String> aiEstimatedCostNanoUsd;
  private final Counter2<String, String> aiPricingMissing;

  @Inject
  public ReviewAiMetrics(MetricMaker metricMaker) {
    Field<String> eventTypeField =
        Field.ofString("event_type", (metadataBuilder, fieldValue) -> {})
            .description("Gerrit event type")
            .build();
    Field<String> statusField =
        Field.ofString("status", (metadataBuilder, fieldValue) -> {})
            .description("Request status")
            .build();
    Field<String> providerField =
        Field.ofString("provider", (metadataBuilder, fieldValue) -> {})
            .description("AI provider")
            .build();
    Field<String> modelField =
        Field.ofString("model", (metadataBuilder, fieldValue) -> {})
            .description("AI model")
            .build();
    Field<String> stageField =
        Field.ofString("stage", (metadataBuilder, fieldValue) -> {})
            .description("Review assistant stage")
            .build();

    reviewRunCount =
        metricMaker.newCounter(
            "reviewai/review_run/count",
            new Description("ReviewAI event processing attempts").setRate().setUnit("runs"),
            eventTypeField,
            statusField);
    reviewRunLatency =
        metricMaker.newTimer(
            "reviewai/review_run/latency",
            new Description("ReviewAI event processing latency")
                .setCumulative()
                .setUnit(Description.Units.NANOSECONDS),
            eventTypeField,
            statusField);
    aiRequestCount =
        metricMaker.newCounter(
            "reviewai/ai_request/count",
            new Description("ReviewAI AI backend requests").setRate().setUnit("requests"),
            providerField,
            stageField,
            statusField);
    aiRequestLatency =
        metricMaker.newTimer(
            "reviewai/ai_request/latency",
            new Description("ReviewAI AI backend request latency")
                .setCumulative()
                .setUnit(Description.Units.NANOSECONDS),
            providerField,
            modelField,
            stageField);
    aiEstimatedCostNanoUsd =
        metricMaker.newCounter(
            "reviewai/ai_request/estimated_cost_nanousd",
            new Description("Estimated ReviewAI provider cost")
                .setCumulative()
                .setUnit("nanoUSD"),
            providerField,
            modelField);
    aiPricingMissing =
        metricMaker.newCounter(
            "reviewai/ai_request/pricing_missing",
            new Description("ReviewAI responses without configured model pricing")
                .setRate()
                .setUnit("responses"),
            providerField,
            modelField);
  }

  @VisibleForTesting
  public ReviewAiMetrics() {
    reviewRunCount = null;
    reviewRunLatency = null;
    aiRequestCount = null;
    aiRequestLatency = null;
    aiEstimatedCostNanoUsd = null;
    aiPricingMissing = null;
  }

  public MetricTimer startReviewRun(String eventType) {
    return new MetricTimer(
        (status, elapsedNanos) -> recordReviewRun(eventType, status, elapsedNanos));
  }

  public MetricTimer startAiRequest(
      Enum<?> provider, String model, Enum<?> stage, String specializedAgentName) {
    String stageLabel = aiRequestStageLabel(stage, specializedAgentName);
    return new MetricTimer(
        (status, elapsedNanos) ->
            recordAiRequest(provider, model, stageLabel, status, elapsedNanos));
  }

  private void recordReviewRun(String eventType, String status, long elapsedNanos) {
    if (reviewRunCount == null || reviewRunLatency == null) {
      return;
    }
    reviewRunCount.increment(label(eventType), label(status));
    reviewRunLatency.record(label(eventType), label(status), elapsedNanos, TimeUnit.NANOSECONDS);
  }

  private void recordAiRequest(
      Enum<?> provider, String model, String stage, String status, long elapsedNanos) {
    if (aiRequestCount == null || aiRequestLatency == null) {
      return;
    }
    aiRequestCount.increment(label(provider), label(stage), label(status));
    aiRequestLatency.record(label(provider), label(model), label(stage), elapsedNanos, TimeUnit.NANOSECONDS);
  }

  public void recordAiEstimatedCostNanoUsd(String provider, String model, long nanoUsd) {
    if (aiEstimatedCostNanoUsd != null) {
      aiEstimatedCostNanoUsd.incrementBy(label(provider), label(model), nanoUsd);
    }
  }

  public void recordAiPricingMissing(String provider, String model) {
    if (aiPricingMissing != null) {
      aiPricingMissing.increment(label(provider), label(model));
    }
  }

  private static String label(String value) {
    return value == null || value.isBlank() ? UNKNOWN_VALUE : value;
  }

  private static String label(Enum<?> value) {
    return value == null ? UNKNOWN_VALUE : value.name();
  }

  @VisibleForTesting
  static String aiRequestStageLabel(Enum<?> stage, String specializedAgentName) {
    String stageLabel = label(stage);
    if (!ReviewAssistantStage.REVIEW_SPECIALIZED_AGENT.name().equals(stageLabel)
        || specializedAgentName == null
        || specializedAgentName.isBlank()) {
      return stageLabel;
    }
    return stageLabel + "_" + specializedAgentName.trim();
  }

  @FunctionalInterface
  private interface MetricRecorder {
    void record(String status, long elapsedNanos);
  }

  public static final class MetricTimer {
    private final MetricRecorder recorder;
    private final long startNanos;

    private MetricTimer(MetricRecorder recorder) {
      this.recorder = recorder;
      this.startNanos = TimeUtils.getCurrentNanos();
    }

    public void complete() {
      record("completed");
    }

    public void empty() {
      record("empty");
    }

    public void fail() {
      record("error");
    }

    private void record(String status) {
      recorder.record(status, TimeUtils.getElapsedNanos(startNanos));
    }
  }
}

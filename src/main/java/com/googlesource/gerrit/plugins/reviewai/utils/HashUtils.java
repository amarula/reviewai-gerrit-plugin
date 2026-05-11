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

package com.googlesource.gerrit.plugins.reviewai.utils;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;

@Slf4j
public class HashUtils {

  public static String hashData(List<String> dataItems) {
    StringBuilder concatenatedData = new StringBuilder();
    for (String item : dataItems) {
      concatenatedData.append(item);
    }
    log.debug("Concatenated data for hashing: {}", concatenatedData);
    String hash = sha1(concatenatedData.toString());
    log.debug("Computed SHA-1 hash: {}", hash);
    return hash;
  }

  public static String stableUuid(String value) {
    String input = String.valueOf(value).toLowerCase(Locale.ROOT);
    long[] parts = {
      hash32(input, 0x811c9dc5L),
      hash32(input, 0x01000193L),
      hash32(input, 0x85ebca6bL),
      hash32(input, 0xc2b2ae35L)
    };
    StringBuilder hex = new StringBuilder();
    for (long part : parts) {
      hex.append(String.format("%08x", part));
    }
    int variant = (Integer.parseInt(hex.substring(16, 17), 16) & 0x3) | 0x8;
    return String.join(
        "-",
        hex.substring(0, 8),
        hex.substring(8, 12),
        "4" + hex.substring(13, 16),
        Integer.toString(variant, 16) + hex.substring(17, 20),
        hex.substring(20, 32));
  }

  private static String sha1(String data) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      byte[] hashBytes = digest.digest(data.getBytes(StandardCharsets.UTF_8));
      String hexResult = bytesToHex(hashBytes);
      log.debug("SHA-1 hash in hex: {}", hexResult);
      return hexResult;
    } catch (NoSuchAlgorithmException e) {
      log.error("Failed to find SHA-1 hashing algorithm", e);
      throw new RuntimeException("SHA-1 algorithm not found", e);
    }
  }

  private static long hash32(String input, long seed) {
    long hash = seed & 0xffffffffL;
    for (int i = 0; i < input.length(); i++) {
      hash ^= input.charAt(i);
      hash = (hash * 0x01000193L) & 0xffffffffL;
    }
    hash ^= hash >>> 16;
    hash = (hash * 0x7feb352dL) & 0xffffffffL;
    hash ^= hash >>> 15;
    hash = (hash * 0x846ca68bL) & 0xffffffffL;
    hash ^= hash >>> 16;
    return hash & 0xffffffffL;
  }

  private static String bytesToHex(byte[] hashBytes) {
    StringBuilder hexString = new StringBuilder(2 * hashBytes.length);
    for (byte b : hashBytes) {
      String hex = Integer.toHexString(0xff & b);
      if (hex.length() == 1) {
        hexString.append('0');
      }
      hexString.append(hex);
    }
    return hexString.toString();
  }
}

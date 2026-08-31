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

package com.googlesource.gerrit.plugins.reviewai.data;

import java.util.Objects;

/** Immutable input used to admit an AI request into a per-Change lane. */
public record AiRequestSubmission(
    String requestId,
    String changeId,
    String sourceEventId,
    AiRequest.Kind kind,
    AiRequest.AdmissionPolicy admissionPolicy,
    String payloadJson) {
  public AiRequestSubmission {
    requestId = requireNonBlank(requestId, "requestId");
    changeId = requireNonBlank(changeId, "changeId");
    sourceEventId = normalize(sourceEventId);
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(admissionPolicy, "admissionPolicy");
    Objects.requireNonNull(payloadJson, "payloadJson");
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static String normalize(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}

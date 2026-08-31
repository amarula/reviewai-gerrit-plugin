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

/** Persisted unit of AI work and its queue ownership state. */
public record AiRequest(
    long queueSequence,
    String requestId,
    String changeId,
    String sourceEventId,
    Kind kind,
    AdmissionPolicy admissionPolicy,
    State state,
    String payloadJson,
    String ownerId,
    Long leaseExpiresAtMillis,
    String resultText,
    long createdAtMillis,
    long updatedAtMillis) {
  public enum Kind {
    REVIEW,
    SUGGEST,
    MESSAGE
  }

  public enum AdmissionPolicy {
    REJECT_IF_OCCUPIED,
    QUEUE
  }

  public enum State {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    REJECTED,
    ABANDONED;

    public boolean isTerminal() {
      return this == COMPLETED || this == FAILED || this == REJECTED || this == ABANDONED;
    }
  }
}

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

package com.googlesource.gerrit.plugins.reviewai.data;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class ReviewAgentRequestStatusStore {
  public static final String STATUS_PENDING = "pending";
  public static final String STATUS_COMPLETED = "completed";
  public static final String STATUS_FAILED = "failed";

  private static final String KEY_REQUEST_STATUSES = "reviewAgentRequestStatuses";
  private static final Type STATUSES_TYPE =
      new TypeToken<Map<String, RequestStatus>>() {}.getType();

  private final PluginDataHandler pluginDataHandler;

  public ReviewAgentRequestStatusStore(PluginDataHandler pluginDataHandler) {
    this.pluginDataHandler = pluginDataHandler;
  }

  public synchronized void pending(String requestId, String prompt) {
    if (requestId == null || requestId.isBlank()) {
      return;
    }
    put(requestId, new RequestStatus(requestId, STATUS_PENDING, prompt, null));
  }

  public synchronized void completed(String requestId, String responseText) {
    update(requestId, STATUS_COMPLETED, responseText);
  }

  public synchronized void failed(String requestId, String responseText) {
    update(requestId, STATUS_FAILED, responseText);
  }

  public synchronized void move(String oldRequestId, String newRequestId) {
    if (oldRequestId == null
        || oldRequestId.isBlank()
        || newRequestId == null
        || newRequestId.isBlank()
        || oldRequestId.equals(newRequestId)) {
      return;
    }
    Map<String, RequestStatus> statuses = getStatuses();
    RequestStatus existing = statuses.remove(oldRequestId);
    RequestStatus target = statuses.get(newRequestId);
    if (target != null && !STATUS_PENDING.equals(target.status)) {
      pluginDataHandler.setJsonValue(KEY_REQUEST_STATUSES, statuses);
      return;
    }
    if (existing == null) {
      existing = new RequestStatus(newRequestId, STATUS_PENDING, null, null);
    }
    existing.requestId = newRequestId;
    existing.previousRequestId = oldRequestId;
    existing.updatedMillis = System.currentTimeMillis();
    statuses.put(newRequestId, existing);
    pluginDataHandler.setJsonValue(KEY_REQUEST_STATUSES, statuses);
  }

  public synchronized RequestStatus get(String requestId) {
    if (requestId == null || requestId.isBlank()) {
      return new RequestStatus("", STATUS_FAILED, null, "request_id is required");
    }
    return Optional.ofNullable(getStatuses().get(requestId))
        .orElse(new RequestStatus(requestId, STATUS_PENDING, null, null));
  }

  public synchronized Optional<String> getLatestPendingRequestId() {
    return getLatestPendingRequestId(getStatuses());
  }

  public synchronized Optional<String> getPendingRequestId(String preferredRequestId) {
    Map<String, RequestStatus> statuses = getStatuses();
    if (preferredRequestId != null && !preferredRequestId.isBlank()) {
      RequestStatus preferred = statuses.get(preferredRequestId);
      if (preferred != null && STATUS_PENDING.equals(preferred.status)) {
        return Optional.of(preferred.requestId);
      }
      Optional<String> movedRequestId =
          statuses.values().stream()
              .filter(status -> STATUS_PENDING.equals(status.status))
              .filter(status -> preferredRequestId.equals(status.previousRequestId))
              .max((left, right) -> Long.compare(left.updatedMillis, right.updatedMillis))
              .map(status -> status.requestId);
      if (movedRequestId.isPresent()) {
        return movedRequestId;
      }
    }
    return getLatestPendingRequestId(statuses);
  }

  private Optional<String> getLatestPendingRequestId(Map<String, RequestStatus> statuses) {
    return statuses.values().stream()
        .filter(status -> STATUS_PENDING.equals(status.status))
        .max((left, right) -> Long.compare(left.updatedMillis, right.updatedMillis))
        .map(status -> status.requestId);
  }

  private void update(String requestId, String status, String responseText) {
    if (requestId == null || requestId.isBlank()) {
      return;
    }
    RequestStatus existing = getStatuses().get(requestId);
    put(
        requestId,
        new RequestStatus(
            requestId, status, existing == null ? null : existing.prompt, responseText));
  }

  private void put(String requestId, RequestStatus requestStatus) {
    Map<String, RequestStatus> statuses = getStatuses();
    statuses.put(requestId, requestStatus);
    pluginDataHandler.setJsonValue(KEY_REQUEST_STATUSES, statuses);
  }

  private Map<String, RequestStatus> getStatuses() {
    Map<String, RequestStatus> statuses =
        pluginDataHandler.getJsonValue(KEY_REQUEST_STATUSES, STATUSES_TYPE);
    return statuses == null ? new HashMap<>() : new HashMap<>(statuses);
  }

  public static class RequestStatus {
    public String requestId;
    public String status;
    public String prompt;
    public String responseText;
    public String previousRequestId;
    public long updatedMillis;

    public RequestStatus() {}

    public RequestStatus(String requestId, String status, String prompt, String responseText) {
      this.requestId = requestId;
      this.status = status;
      this.prompt = prompt;
      this.responseText = responseText;
      this.updatedMillis = System.currentTimeMillis();
    }
  }
}

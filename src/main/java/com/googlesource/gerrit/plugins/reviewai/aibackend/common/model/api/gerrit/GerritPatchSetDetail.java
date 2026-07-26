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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

// TODO remove once migration to GerritApi is finished and
// com.google.gerrit.extensions.common.ChangeInfo is used
@Data
public class GerritPatchSetDetail {
  private Labels labels;
  private List<GerritComment> messages;

  @SerializedName("work_in_progress")
  private Boolean workInProgress;

  /**
   * Labels container that maps each label name (e.g. "Code-Review", "Verified") to its
   * list of voter permissions. The map keys are the raw label names from the Gerrit API.
   */
  @Data
  public static class Labels {

    /**
     * Returns all permissions for the Code-Review label.
     * Kept for backward compatibility with existing callers.
     */
    public List<Permission> getCodeReviewPermissions() {
      Map<String, List<Permission>> allLabels = getAllLabels();
      if (allLabels == null) {
        return null;
      }
      return allLabels.get("Code-Review");
    }

    /**
     * Returns all label names and their permission lists.
     * Each key is the label name (e.g. "Code-Review", "Verified").
     */
    public Map<String, List<Permission>> getAllLabels() {
      // The map is populated by Gson deserialization of the full labels JSON object.
      // Field names are derived from the JSON keys, so we need a catch-all map.
      // We use a custom deserializer approach: store raw data and expose via this method.
      return labelData;
    }

    /**
     * Sets the label data map. Used during deserialization and for testing.
     */
    public void setAllLabels(Map<String, List<Permission>> labelData) {
      this.labelData = labelData;
    }

    // The raw label data is stored here. Gson cannot deserialize arbitrary keys into this
    // field automatically with @SerializedName, so deserialization is handled in
    // GerritClientDetail through manual parsing of the ChangeInfo.labels map.
    private Map<String, List<Permission>> labelData = new HashMap<>();
  }

  @Data
  public static class Permission {
    private Integer value;
    private String date;

    @SerializedName("permitted_voting_range")
    private GerritPermittedVotingRange permittedVotingRange;

    @SerializedName("_account_id")
    private int accountId;
  }
}

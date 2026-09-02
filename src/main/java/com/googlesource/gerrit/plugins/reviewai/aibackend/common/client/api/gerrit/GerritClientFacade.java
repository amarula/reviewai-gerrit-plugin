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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritConditionLabel;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.gerrit.IGerritClientPatchSet;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritPermittedVotingRange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GerritClientFacade {
  private final ChangeSetData changeSetData;
  private final GerritClientDetail gerritClientDetail;
  private final GerritClientComments gerritClientComments;
  private final IGerritClientPatchSet gerritClientPatchSet;

  @VisibleForTesting
  @Inject
  public GerritClientFacade(
      Configuration config,
      ChangeSetData changeSetData,
      GerritClientComments gerritClientComments,
      IGerritClientPatchSet gerritClientPatchSet) {
    gerritClientDetail = new GerritClientDetail(config, changeSetData);
    this.gerritClientPatchSet = gerritClientPatchSet;
    this.changeSetData = changeSetData;
    this.gerritClientComments = gerritClientComments;
  }

  public GerritPermittedVotingRange getPermittedVotingRange(GerritChange change) {
    return gerritClientDetail.getPermittedVotingRange(change);
  }

  public String getPatchSet(GerritChange change) throws Exception {
    return gerritClientPatchSet.getPatchSet(changeSetData, change);
  }

  public String getIncrementalPatchSet(GerritChange change) throws Exception {
    return gerritClientPatchSet.getIncrementalPatchSet(changeSetData, change);
  }

  public boolean isDisabledUser(String authorUsername) {
    return gerritClientPatchSet.isDisabledUser(authorUsername);
  }

  public boolean isWorkInProgress(GerritChange change) {
    return gerritClientDetail.isWorkInProgress(change);
  }

  public boolean retrieveComments(GerritChange change, boolean administratorUser) {
    return gerritClientComments.retrieveComments(change, administratorUser);
  }

  public boolean retrieveComments(
      GerritChange change, boolean administratorUser, String changeMessageId) {
    return gerritClientComments.retrieveComments(change, administratorUser, changeMessageId);
  }

  public void retrievePatchSetInfo(GerritChange change) {
    gerritClientComments.retrieveAllComments(change);
    gerritClientPatchSet.retrieveRevisionBase(change);
  }

  public GerritClientData getClientData(GerritChange change) {
    return new GerritClientData(
        gerritClientPatchSet,
        gerritClientDetail.getMessages(change),
        gerritClientComments.getCommentData(),
        gerritClientPatchSet.getRevisionBase());
  }

  public List<GerritChange> getTopicChanges(GerritChange change) {
    return gerritClientDetail.getTopicChanges(change);
  }

  public Map<String, GerritConditionLabel> getConditionLabels(
      GerritChange change, String expression) {
    return gerritClientDetail.getConditionLabels(change, expression);
  }

  public Integer getCodeReviewValue(GerritChange change) {
    return gerritClientDetail.getCodeReviewValue(change);
  }
}

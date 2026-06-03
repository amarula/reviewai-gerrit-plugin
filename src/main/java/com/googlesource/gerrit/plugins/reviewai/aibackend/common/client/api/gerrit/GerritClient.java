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

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritPermittedVotingRange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class GerritClient {
  private final GerritClientFacade gerritClientFacade;

  @Inject
  public GerritClient(GerritClientFacade gerritClientFacade) {
    this.gerritClientFacade = gerritClientFacade;
  }

  public GerritPermittedVotingRange getPermittedVotingRange(GerritChange change) {
    return gerritClientFacade.getPermittedVotingRange(change);
  }

  public String getPatchSet(String fullChangeId) throws Exception {
    return getPatchSet(new GerritChange(fullChangeId));
  }

  public String getPatchSet(GerritChange change) throws Exception {
    return gerritClientFacade.getPatchSet(change);
  }

  public boolean isDisabledUser(String authorUsername) {
    return gerritClientFacade.isDisabledUser(authorUsername);
  }

  public boolean isDisabledTopic(String topic) {
    return gerritClientFacade.isDisabledTopic(topic);
  }

  public boolean isWorkInProgress(GerritChange change) {
    return gerritClientFacade.isWorkInProgress(change);
  }

  public boolean retrieveLastComments(GerritChange change) {
    return gerritClientFacade.retrieveLastComments(change);
  }

  public void retrievePatchSetInfo(GerritChange change) {
    gerritClientFacade.retrievePatchSetInfo(change);
  }

  public GerritClientData getClientData(GerritChange change) {
    return gerritClientFacade.getClientData(change);
  }
}

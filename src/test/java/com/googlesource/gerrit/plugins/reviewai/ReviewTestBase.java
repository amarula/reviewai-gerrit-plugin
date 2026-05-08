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

package com.googlesource.gerrit.plugins.reviewai;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.google.gerrit.entities.Account;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.*;
import com.google.gerrit.extensions.api.changes.ChangeApi.CommentsRequest;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.CommentInfo;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.json.OutputFormat;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.data.AccountAttribute;
import com.google.gerrit.server.data.PatchSetAttribute;
import com.google.gerrit.server.events.*;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.TypeLiteral;
import com.google.inject.util.Providers;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.LangChainClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.LangChainMultiAgentReviewClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiMultiAgentReviewClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiReviewClient;
import com.googlesource.gerrit.plugins.reviewai.config.ConfigCreator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.ChangeSetDataProvider;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.ai.IAiClient;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.gerrit.IGerritClientPatchSet;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.listener.EventHandlerTask;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientComments;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientFacade;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyOnDemand;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.gerrit.GerritClientPatchSetOpenAi;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyNone;
import com.googlesource.gerrit.plugins.reviewai.web.AiReviewPermission;

import lombok.NonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.function.Consumer;

import static com.google.gerrit.extensions.client.ChangeKind.REWORK;
import static com.googlesource.gerrit.plugins.reviewai.listener.EventHandlerTask.EVENT_CLASS_MAP;
import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.jsonToClass;
import static org.mockito.Mockito.*;

public class ReviewTestBase extends TestBase {
  protected static final Path basePath = Paths.get("src/test/resources");
  protected static final int GERRIT_AI_ACCOUNT_ID = 1000000;
  protected static final String GERRIT_AI_USERNAME = "gpt";
  protected static final int GERRIT_USER_ACCOUNT_ID = 1000001;
  protected static final String GERRIT_USER_ACCOUNT_NAME = "Test";
  protected static final String GERRIT_USER_ACCOUNT_EMAIL = "test@example.com";
  protected static final String GERRIT_USER_USERNAME = "test";
  protected static final String AI_TOKEN = "tk-test";
  protected static final String AI_DOMAIN = "http://localhost:9527";
  protected static final String GERRIT_UI_PROMPTS_PATH = "/gerrit-prompts.ts?format=TEXT";
  protected static final String GERRIT_UI_PROMPTS_URL_PROPERTY = "reviewai.gerritUiPromptUrl";
  protected static final long TEST_TIMESTAMP = 1699270812;
  protected static final Type COMMENTS_GERRIT_TYPE =
      new TypeLiteral<Map<String, List<CommentInfo>>>() {}.getType();

  private static final int AI_USER_ACCOUNT_ID = 1000000;

  @Rule public WireMockRule wireMockRule = new WireMockRule(9527);

  @Mock protected GitRepoFiles gitRepoFiles;

  @Mock protected PluginDataHandlerProvider pluginDataHandlerProvider;

  @Mock protected PluginDataHandler pluginDataHandler;

  @Mock protected OneOffRequestContext context;
  @Mock protected GerritApi gerritApi;
  @Mock protected Changes changesMock;
  @Mock protected ChangeApi changeApiMock;
  @Mock protected RevisionApi revisionApiMock;
  @Mock protected ReviewResult reviewResult;
  @Mock protected CommentsRequest commentsRequestMock;
  @Mock protected AccountCache accountCacheMock;
  @Mock protected GitRepositoryManager repositoryManager;
  @Mock protected ChangeSetDataProvider changeSetDataProvider;
  @Mock protected AiReviewPermission aiReviewPermission;
  @Mock protected IdentifiedUser.GenericFactory identifiedUserFactory;
  @Mock protected IdentifiedUser eventUser;

  protected PluginConfig globalConfig;
  protected PluginConfig projectConfig;
  protected Configuration config;
  protected ChangeSetData changeSetData;
  protected GerritClient gerritClient;
  protected PatchSetReviewer patchSetReviewer;
  protected ConfigCreator mockConfigCreator;
  protected JsonObject aiRequestBody;
  protected String promptTagComments;
  protected Localizer localizer;
  protected boolean includeEventAccountId = true;
  protected Integer eventAccountId = GERRIT_USER_ACCOUNT_ID;
  protected String eventAccountName = GERRIT_USER_ACCOUNT_NAME;
  protected String eventAccountEmail = GERRIT_USER_ACCOUNT_EMAIL;
  protected String eventAccountUsername = GERRIT_USER_USERNAME;

  @Before
  public void before() throws RestApiException {
    System.setProperty(GERRIT_UI_PROMPTS_URL_PROPERTY, AI_DOMAIN + GERRIT_UI_PROMPTS_PATH);
    initGlobalAndProjectConfig();
    initConfig();
    setupMockRequests();
    initComparisonContent();
    initTest();
  }

  @After
  public void after() {
    System.clearProperty(GERRIT_UI_PROMPTS_URL_PROPERTY);
  }

  protected void initGlobalAndProjectConfig() {
    globalConfig = mock(PluginConfig.class);
    Answer<Object> returnDefaultArgument =
        invocation -> {
          // Return the second argument (i.e., the Default value) passed to the method
          return invocation.getArgument(1);
        };

    // Mock the Global Config values not provided by Default
    when(globalConfig.getStringList("aiTokens")).thenReturn(new String[] {"OpenAI/" + AI_TOKEN});

    // Mock the Global Config values to the Defaults passed as second arguments of the `get*`
    // methods.
    when(globalConfig.getString(Mockito.anyString(), Mockito.anyString()))
        .thenAnswer(returnDefaultArgument);
    when(globalConfig.getInt(Mockito.anyString(), Mockito.anyInt()))
        .thenAnswer(returnDefaultArgument);
    when(globalConfig.getBoolean(Mockito.anyString(), Mockito.anyBoolean()))
        .thenAnswer(returnDefaultArgument);

    // Mock the Global Config values that differ from the ones provided by Default
    when(globalConfig.getString(Mockito.eq("aiDomain"), Mockito.anyString()))
        .thenReturn(AI_DOMAIN);
    when(globalConfig.getString("gerritUserName")).thenReturn(GERRIT_AI_USERNAME);
    when(globalConfig.getInt(Mockito.eq("aiConnectionMaxRetryAttempts"), Mockito.anyInt()))
        .thenReturn(1);

    projectConfig = mock(PluginConfig.class);
  }

  protected void initConfig() {
    config =
        new Configuration(
            context, gerritApi, globalConfig, projectConfig, "ai@email.com", Account.id(1000000));
  }

  protected void setupMockRequests() throws RestApiException {
    mockGerritUiPromptsApiCall();

    // Mock the behavior of the gerritAccountIdUri request
    mockGerritAccountsQueryApiCall(GERRIT_AI_USERNAME, GERRIT_AI_ACCOUNT_ID);

    // Mock the behavior of the gerritAccountIdUri request
    mockGerritAccountsQueryApiCall(GERRIT_USER_USERNAME, GERRIT_USER_ACCOUNT_ID);

    mockGerritChangeApiRestEndpoint();

    // Mock the behavior of the gerritGetPatchSetDetailUri request
    mockGerritChangeDetailsApiCall();

    // Mock the behavior of the gerritPatchSet comments request
    mockGerritChangeCommentsApiCall("gerritPatchSetComments.json");

    // Mock the behavior of the gerrit Review request
    mockGerritReviewApiCall();

    // Mock the GerritApi's revision API
    lenient().when(changeApiMock.current()).thenReturn(revisionApiMock);

    // Mock the pluginDataHandlerProvider to return the mocked Change pluginDataHandler
    when(pluginDataHandlerProvider.getChangeScope()).thenReturn(pluginDataHandler);
    lenient()
        .when(identifiedUserFactory.create(Account.id(GERRIT_USER_ACCOUNT_ID)))
        .thenReturn(eventUser);
    lenient()
        .when(identifiedUserFactory.create(Mockito.any(AccountState.class)))
        .thenReturn(eventUser);
  }

  protected void mockGerritUiPromptsApiCall() {
    mockGerritUiPromptsApiCall(readTestFile("__files/gerrit/gerritPrompts.ts"));
  }

  protected void mockGerritUiPromptsApiCall(String promptsSource) {
    WireMock.stubFor(
        WireMock.get(WireMock.urlEqualTo(GERRIT_UI_PROMPTS_PATH))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(200)
                    .withBody(
                        Base64.getEncoder()
                            .encodeToString(promptsSource.getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
  }

  private void mockGerritAccountsQueryApiCall(String username, int expectedAccountId) {
    Account account = Account.builder(Account.id(expectedAccountId), Instant.now()).build();
    AccountState accountState = AccountState.forAccount(account, Collections.emptyList());
    lenient().when(accountCacheMock.getByUsername(username)).thenReturn(Optional.of(accountState));
  }

  private void mockGerritChangeDetailsApiCall() throws RestApiException {
    ChangeInfo changeInfo =
        readTestFileToClass("__files/gerritPatchSetDetail.json", ChangeInfo.class);
    lenient().when(changeApiMock.get()).thenReturn(changeInfo);
  }

  private void mockGerritChangeCommentsApiCall(String patchSetCommentsFilename)
      throws RestApiException {
    Map<String, List<CommentInfo>> comments =
        readTestFileToType("__files/" + patchSetCommentsFilename, COMMENTS_GERRIT_TYPE);
    mockGerritChangeCommentsApiCall(comments);
  }

  private void mockGerritChangeApiRestEndpoint() throws RestApiException {
    when(gerritApi.changes()).thenReturn(changesMock);
    when(changesMock.id(PROJECT_NAME.get(), BRANCH_NAME.shortName(), CHANGE_ID.get()))
        .thenReturn(changeApiMock);
  }

  private void mockGerritReviewApiCall() throws RestApiException {
    ArgumentCaptor<ReviewInput> reviewInputCaptor = ArgumentCaptor.forClass(ReviewInput.class);
    lenient().when(revisionApiMock.review(reviewInputCaptor.capture())).thenReturn(reviewResult);
  }

  protected void initComparisonContent() {}

  protected <T> T readContentToType(String content, Type type) {
    Gson gson = OutputFormat.JSON.newGson();
    return gson.fromJson(content, type);
  }

  protected <T> T readTestFileToClass(String filename, Class<T> clazz) {
    return readContentToType(readTestFile(filename), clazz);
  }

  protected <T> T readTestFileToType(String filename, Type type) {
    return readContentToType(readTestFile(filename), type);
  }

  protected String readTestFile(String filename) {
    try {
      return new String(Files.readAllBytes(basePath.resolve(filename)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected void mockGerritChangeCommentsApiCall(Map<String, List<CommentInfo>> comments)
      throws RestApiException {
    when(changeApiMock.commentsRequest()).thenReturn(commentsRequestMock);
    when(commentsRequestMock.get()).thenReturn(comments);
  }

  protected EventHandlerTask.Result handleEventBasedOnType(
      EventHandlerTask.SupportedEvents triggeredEvent) {
    Consumer<Event> typeSpecificSetup = getTypeSpecificSetup(triggeredEvent);
    Event event = getMockedEvent(triggeredEvent);
    setupCommonEventMocks((PatchSetEvent) event); // Apply common mock configurations
    typeSpecificSetup.accept(event);

    EventHandlerTask task =
        Guice.createInjector(
                new AbstractModule() {
                  @Override
                  protected void configure() {
                    install(new TestGerritEventContextModule(config, event));

                    bind(GerritClient.class).toInstance(gerritClient);
                    bind(ConfigCreator.class).toInstance(mockConfigCreator);
                    bind(ChangeSetDataProvider.class).toInstance(changeSetDataProvider);
                    bind(PatchSetReviewer.class).toInstance(patchSetReviewer);
                    bind(PluginDataHandlerProvider.class).toInstance(pluginDataHandlerProvider);
                    bind(AiReviewPermission.class).toInstance(aiReviewPermission);
                    bind(IdentifiedUser.GenericFactory.class).toInstance(identifiedUserFactory);
                    bind(AccountCache.class).toInstance(accountCacheMock);
                    bind(GitRepositoryManager.class).toInstance(repositoryManager);
                  }
                })
            .getInstance(EventHandlerTask.class);
    return task.execute();
  }

  protected ArgumentCaptor<ReviewInput> testRequestSent() throws RestApiException {
    ArgumentCaptor<ReviewInput> reviewInputCaptor = ArgumentCaptor.forClass(ReviewInput.class);
    verify(revisionApiMock).review(reviewInputCaptor.capture());
    aiRequestBody =
        jsonToClass(patchSetReviewer.getOpenAiClient().getRequestBody(), JsonObject.class);
    return reviewInputCaptor;
  }

  protected void initTest() {
    changeSetData =
        new ChangeSetData(
            AI_USER_ACCOUNT_ID, config.getVotingMinScore(), config.getVotingMaxScore());
    when(changeSetDataProvider.get()).thenReturn(changeSetData);

    localizer = new Localizer(config);
    IGerritClientPatchSet gerritClientPatchSet = getGerritClientPatchSet();
    gerritClient =
        new GerritClient(
            new GerritClientFacade(
                config,
                changeSetData,
                new GerritClientComments(
                    config,
                    accountCacheMock,
                    changeSetData,
                    getCodeContextPolicy(),
                    gitRepoFiles,
                    gerritClientPatchSet,
                    pluginDataHandlerProvider,
                    localizer),
                gerritClientPatchSet));
    patchSetReviewer =
        new PatchSetReviewer(
            gerritClient,
            config,
            changeSetData,
            Providers.of(
                new GerritClientReview(
                    config, accountCacheMock, pluginDataHandlerProvider, localizer)),
            getOpenAIClient(),
            localizer);
    mockConfigCreator = mock(ConfigCreator.class);
  }

  protected ICodeContextPolicy getCodeContextPolicy() {
    return switch (config.getCodeContextPolicy()) {
      case NONE -> new CodeContextPolicyNone(config);
      case ON_DEMAND -> new CodeContextPolicyOnDemand(config);
    };
  }

  private AccountAttribute createTestAccountAttribute() {
    AccountAttribute accountAttribute = new AccountAttribute();
    accountAttribute.name = eventAccountName;
    accountAttribute.username = eventAccountUsername;
    accountAttribute.email = eventAccountEmail;
    if (includeEventAccountId) {
      accountAttribute.accountId = eventAccountId;
    }
    return accountAttribute;
  }

  private PatchSetAttribute createPatchSetAttribute() {
    PatchSetAttribute patchSetAttribute = new PatchSetAttribute();
    patchSetAttribute.kind = REWORK;
    patchSetAttribute.author = createTestAccountAttribute();
    return patchSetAttribute;
  }

  @NonNull
  private Consumer<Event> getTypeSpecificSetup(EventHandlerTask.SupportedEvents triggeredEvent) {
    return switch (triggeredEvent) {
      case COMMENT_ADDED ->
          event -> {
            CommentAddedEvent commentEvent = (CommentAddedEvent) event;
            commentEvent.author = this::createTestAccountAttribute;
            commentEvent.patchSet = this::createPatchSetAttribute;
            commentEvent.eventCreatedOn = TEST_TIMESTAMP;
            when(commentEvent.getType()).thenReturn("comment-added");
          };
      case PATCH_SET_CREATED ->
          event -> {
            PatchSetCreatedEvent patchEvent = (PatchSetCreatedEvent) event;
            patchEvent.uploader = this::createTestAccountAttribute;
            patchEvent.patchSet = this::createPatchSetAttribute;
            when(patchEvent.getType()).thenReturn("patchset-created");
          };
    };
  }

  private Event getMockedEvent(EventHandlerTask.SupportedEvents triggeredEvent) {
    return (Event) mock(EVENT_CLASS_MAP.get(triggeredEvent));
  }

  private void setupCommonEventMocks(PatchSetEvent event) {
    when(event.getProjectNameKey()).thenReturn(PROJECT_NAME);
    when(event.getBranchNameKey()).thenReturn(BRANCH_NAME);
    when(event.getChangeKey()).thenReturn(CHANGE_ID);
  }

  private AccountCache mockAccountCache() {
    AccountCache accountCache = mock(AccountCache.class);
    Account account = Account.builder(Account.id(AI_USER_ACCOUNT_ID), Instant.now()).build();
    AccountState accountState = AccountState.forAccount(account, Collections.emptyList());
    lenient()
        .doReturn(Optional.of(accountState))
        .when(accountCache)
        .getByUsername(GERRIT_AI_USERNAME);

    return accountCache;
  }

  private IAiClient getOpenAIClient() {
    if (config.getSelectedAiModelRoute().isLangChain()) {
      return config.getAiReviewCommitMessages() && config.getMultiAgentMode()
          ? new LangChainMultiAgentReviewClient(
              config, getCodeContextPolicy(), gerritClient, localizer, pluginDataHandlerProvider, Runnable::run)
          : new LangChainClient(
              config, getCodeContextPolicy(), gerritClient, localizer, pluginDataHandlerProvider);
    }
    return config.getAiReviewCommitMessages() && config.getMultiAgentMode()
        ? new OpenAiMultiAgentReviewClient(
            config, getCodeContextPolicy(), pluginDataHandlerProvider, Runnable::run)
        : new OpenAiReviewClient(config, getCodeContextPolicy(), pluginDataHandlerProvider);
  }

  private IGerritClientPatchSet getGerritClientPatchSet() {
    return new GerritClientPatchSetOpenAi(config, accountCacheMock);
  }
}

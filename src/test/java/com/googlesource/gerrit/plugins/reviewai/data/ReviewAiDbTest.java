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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.Test;

public class ReviewAiDbTest extends TestBase {
  @Test
  public void oldInstanceCannotShutDownDatabaseAfterReloadWithSameUrl() throws Exception {
    Path dataDir = tempFolder.getRoot().toPath();
    ReviewAiDb oldDb = new ReviewAiDb(dataDir);
    oldDb.initReviewConcernSchema();
    ReviewAiDb newDb = new ReviewAiDb(dataDir);
    try {
      ReviewChangeStateStore stateStore = new ReviewChangeStateStore(newDb);
      new ReviewConcernStore(newDb, "p~main~Ireload").save(new ReviewConcernLedger());
      try (Connection activeConnection = newDb.getConnection()) {
        // Work still finishing in the old plugin must not reclaim ownership.
        try (Connection ignored = oldDb.getConnection()) {}
        oldDb.stopManagedTcpServerIfOwner();

        assertTrue(activeConnection.isValid(1));
        assertEquals(List.of("p~main~Ireload"), stateStore.listChangeIds());

        newDb.stopManagedTcpServerIfOwner();
        assertFalse(activeConnection.isValid(1));
      }
    } finally {
      newDb.stopManagedTcpServerIfOwner();
      oldDb.stopManagedTcpServerIfOwner();
    }
  }

  @Test
  public void databaseOwnershipSurvivesPluginClassloaderReload() throws Exception {
    Path dataDir = tempFolder.getRoot().toPath();
    ReviewAiDb oldDb = new ReviewAiDb(dataDir);
    String dbClassName = ReviewAiDb.class.getName();
    try (URLClassLoader pluginClassLoader =
        new URLClassLoader(
            new URL[] {ReviewAiDb.class.getProtectionDomain().getCodeSource().getLocation()},
            ReviewAiDb.class.getClassLoader()) {
          @Override
          protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(dbClassName) || name.startsWith(dbClassName + "$")) {
              synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                  loaded = findClass(name);
                }
                if (resolve) {
                  resolveClass(loaded);
                }
                return loaded;
              }
            }
            return super.loadClass(name, resolve);
          }
        }) {
      oldDb.initReviewConcernSchema();
      new ReviewConcernStore(oldDb, "p~main~Ireload").save(new ReviewConcernLedger());
      Class<?> newDbClass = pluginClassLoader.loadClass(dbClassName);
      assertNotSame(ReviewAiDb.class, newDbClass);
      Object newDb = newDbClass.getConstructor(Path.class).newInstance(dataDir);
      try (Connection activeConnection =
          (Connection) newDbClass.getMethod("getConnection").invoke(newDb)) {
        try (Connection ignored = oldDb.getConnection()) {}
        oldDb.stopManagedTcpServerIfOwner();

        assertTrue(activeConnection.isValid(1));
        assertEquals(List.of("p~main~Ireload"), new ReviewChangeStateStore(oldDb).listChangeIds());

        newDbClass.getMethod("stopManagedTcpServerIfOwner").invoke(newDb);
        assertFalse(activeConnection.isValid(1));
      } finally {
        newDbClass.getMethod("stopManagedTcpServerIfOwner").invoke(newDb);
      }
    } finally {
      oldDb.stopManagedTcpServerIfOwner();
      // Release a server started by this classloader after the isolated owner has stopped.
      ReviewAiDb cleanupDb = new ReviewAiDb(dataDir);
      try (Connection ignored = cleanupDb.getConnection()) {
      } finally {
        cleanupDb.stopManagedTcpServerIfOwner();
      }
    }
  }

  @Test
  public void appliesExternalDatabaseConfigBeforeSchemaInitialization() throws Exception {
    PluginConfigFactory configFactory = mock(PluginConfigFactory.class);
    PluginConfig config = mock(PluginConfig.class);
    when(configFactory.getFromGerritConfig("reviewai-gerrit-plugin")).thenReturn(config);
    when(config.getString(ReviewAiDb.KEY_STORE_URL))
        .thenReturn("jdbc:postgresql://localhost/reviewai");
    when(config.getString(ReviewAiDb.KEY_STORE_USERNAME)).thenReturn("reviewai");
    when(config.getString(ReviewAiDb.KEY_STORE_PASSWORD)).thenReturn("secret");

    ReviewAiDb db =
        new ReviewAiDb(
            tempFolder.getRoot().toPath(), "reviewai-gerrit-plugin", configFactory);

    assertEquals(DbDialect.POSTGRESQL, db.getDialect());
    verify(config).getString(ReviewAiDb.KEY_STORE_USERNAME);
    verify(config).getString(ReviewAiDb.KEY_STORE_PASSWORD);
  }

  @Test
  public void migratesLegacyPostgresqlConversationPrimaryKeys() throws Exception {
    Connection connection = mock(Connection.class);
    DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    Statement statement = mock(Statement.class);
    when(connection.getMetaData()).thenReturn(metadata);
    when(connection.createStatement()).thenReturn(statement);
    when(metadata.storesLowerCaseIdentifiers()).thenReturn(true);
    when(metadata.getIdentifierQuoteString()).thenReturn("\"");
    ResultSet conversationPrimaryKey =
        primaryKey(
            "review_agent_conversations_pkey", List.of("change_id", "conversation_id"));
    ResultSet turnPrimaryKey =
        primaryKey(
            "review_agent_conversation_turns_pkey",
            List.of("change_id", "conversation_id", "turn_index"));
    when(metadata.getPrimaryKeys(null, null, "review_agent_conversations"))
        .thenReturn(conversationPrimaryKey);
    when(metadata.getPrimaryKeys(null, null, "review_agent_conversation_turns"))
        .thenReturn(turnPrimaryKey);
    ResultSet noLegacyTurnContent = mock(ResultSet.class);
    when(metadata.getColumns(
            null,
            null,
            "review_agent_conversation_turns",
            "turn_content_json"))
        .thenReturn(noLegacyTurnContent);
    when(noLegacyTurnContent.next()).thenReturn(false);

    ReviewAiDb db =
        org.mockito.Mockito.spy(
            new ReviewAiDb(
                tempFolder.getRoot().toPath(), "jdbc:postgresql://localhost/reviewai"));
    doReturn(connection).when(db).getConnection();

    db.initReviewAgentConversationSchema();

    verify(statement)
        .executeUpdate(
            "ALTER TABLE REVIEW_AGENT_CONVERSATIONS "
                + "ADD COLUMN IF NOT EXISTS user_id BIGINT NOT NULL DEFAULT 0");
    verify(statement)
        .executeUpdate(
            "ALTER TABLE REVIEW_AGENT_CONVERSATIONS DROP CONSTRAINT IF EXISTS "
                + "\"review_agent_conversations_pkey\"");
    verify(statement)
        .executeUpdate(
            "ALTER TABLE REVIEW_AGENT_CONVERSATIONS "
                + "ADD PRIMARY KEY(CHANGE_ID, USER_ID, CONVERSATION_ID)");
    verify(statement)
        .executeUpdate(
            "ALTER TABLE REVIEW_AGENT_CONVERSATION_TURNS DROP CONSTRAINT IF EXISTS "
                + "\"review_agent_conversation_turns_pkey\"");
    verify(statement)
        .executeUpdate(
            "ALTER TABLE REVIEW_AGENT_CONVERSATION_TURNS "
                + "ADD PRIMARY KEY(CHANGE_ID, USER_ID, CONVERSATION_ID, TURN_INDEX)");
  }

  private static ResultSet primaryKey(String name, List<String> columns) throws Exception {
    ResultSet result = mock(ResultSet.class);
    Boolean[] rows = new Boolean[columns.size() + 1];
    for (int index = 0; index < columns.size(); index++) {
      rows[index] = true;
    }
    rows[columns.size()] = false;
    when(result.next()).thenReturn(rows[0], java.util.Arrays.copyOfRange(rows, 1, rows.length));
    when(result.getString("COLUMN_NAME"))
        .thenReturn(columns.get(0), columns.subList(1, columns.size()).toArray(String[]::new));
    when(result.getString("PK_NAME")).thenReturn(name);
    return result;
  }
}

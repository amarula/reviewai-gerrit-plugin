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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.h2.tools.Server;
import org.junit.Test;

public class ReviewAiDbTest extends TestBase {
  @Test
  public void recordsVersionOnceAndPreservesTimestampAfterReload() throws Exception {
    ReviewAiDb db = getTestReviewAiDb();
    db.initSchema();
    Timestamp appliedAt;
    try (Connection c = db.getConnection();
        Statement s = c.createStatement();
        ResultSet rows = s.executeQuery(readDbResource("versions.sql"))) {
      assertTrue(rows.next());
      assertEquals(1, rows.getInt("version"));
      appliedAt = rows.getTimestamp("applied_at");
      assertNotNull(appliedAt);
      assertFalse(rows.next());
    }

    ReviewAiDb reloadedDb =
        new ReviewAiDb(
            tempFolder.getRoot().toPath(), buildEmbeddedTestJdbcUrl(tempFolder.getRoot().toPath()));
    reloadedDb.initSchema();
    try (Connection c = reloadedDb.getConnection();
        Statement s = c.createStatement();
        ResultSet rows = s.executeQuery(readDbResource("versions.sql"))) {
      assertTrue(rows.next());
      assertEquals(1, rows.getInt("version"));
      assertEquals(appliedAt, rows.getTimestamp("applied_at"));
      assertFalse(rows.next());
    }
  }

  @Test
  public void preservesExistingVersionHistory() throws Exception {
    ReviewAiDb db = getTestReviewAiDb();
    Timestamp originalAppliedAt;
    try (Connection c = db.getConnection();
        Statement s = c.createStatement()) {
      s.execute(readDbResource("existingVersions.sql"));
      try (ResultSet rows = s.executeQuery(readDbResource("versions.sql"))) {
        assertTrue(rows.next());
        originalAppliedAt = rows.getTimestamp("applied_at");
      }
    }

    db.initSchema();

    try (Connection c = db.getConnection();
        Statement s = c.createStatement();
        ResultSet rows = s.executeQuery(readDbResource("versions.sql"))) {
      assertTrue(rows.next());
      assertEquals(0, rows.getInt("version"));
      assertEquals(originalAppliedAt, rows.getTimestamp("applied_at"));
      assertTrue(rows.next());
      assertEquals(1, rows.getInt("version"));
      assertNotNull(rows.getTimestamp("applied_at"));
      assertFalse(rows.next());
    }
  }

  @Test
  public void doesNotRecordVersionWhenSchemaInitializationFails() throws Exception {
    ReviewAiDb db = spy(getTestReviewAiDb());
    doThrow(new SQLException("Schema initialization failed"))
        .when(db)
        .initReviewAgentConversationSchema();

    assertThrows(SQLException.class, db::initSchema);

    try (Connection c = db.getConnection();
        ResultSet tables = c.getMetaData().getTables(null, null, "DB_VERSIONS", null)) {
      assertFalse(tables.next());
    }
  }

  private String readDbResource(String name) throws Exception {
    try (InputStream input =
        getClass().getClassLoader().getResourceAsStream("__files/db/" + name)) {
      assertNotNull(input);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

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
        new ReviewAiDb(tempFolder.getRoot().toPath(), "reviewai-gerrit-plugin", configFactory);

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
        primaryKey("review_agent_conversations_pkey", List.of("change_id", "conversation_id"));
    ResultSet turnPrimaryKey =
        primaryKey(
            "review_agent_conversation_turns_pkey",
            List.of("change_id", "conversation_id", "turn_index"));
    when(metadata.getPrimaryKeys(null, null, "review_agent_conversations"))
        .thenReturn(conversationPrimaryKey);
    when(metadata.getPrimaryKeys(null, null, "review_agent_conversation_turns"))
        .thenReturn(turnPrimaryKey);
    ResultSet noLegacyTurnContent = mock(ResultSet.class);
    when(metadata.getColumns(null, null, "review_agent_conversation_turns", "turn_content_json"))
        .thenReturn(noLegacyTurnContent);
    when(noLegacyTurnContent.next()).thenReturn(false);

    ReviewAiDb db =
        org.mockito.Mockito.spy(
            new ReviewAiDb(tempFolder.getRoot().toPath(), "jdbc:postgresql://localhost/reviewai"));
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

  @Test
  public void claimingOwnershipRecordsItJvmWide() throws Exception {
    withOwnershipTokenCleared(
        () -> {
          assertNull(System.getProperty(OWNERSHIP_KEY));
          tcpRoutedDb("first").claimTcpServerOwnership();
          assertNotNull(
              "a plugin instance must be able to claim the database without connecting to it",
              System.getProperty(OWNERSHIP_KEY));
        });
  }

  @Test
  public void aLaterInstanceTakesOwnershipAwayFromAnEarlierOne() throws Exception {
    // The reload sequence: Gerrit starts the new plugin instance before stopping the old one. Once
    // the new instance has claimed the database, the old instance's stop must not tear it down -
    // otherwise the new instance's connections land inside H2's exclusive open/close window and
    // fail
    // with "the database is open in exclusive mode", which names nothing about the real cause.
    withOwnershipTokenCleared(
        () -> {
          ReviewAiDb oldInstance = tcpRoutedDb("old");
          oldInstance.claimTcpServerOwnership();
          String oldOwner = System.getProperty(OWNERSHIP_KEY);

          ReviewAiDb newInstance = tcpRoutedDb("new");
          newInstance.claimTcpServerOwnership();
          String newOwner = System.getProperty(OWNERSHIP_KEY);
          assertNotEquals("a later instance must take ownership", oldOwner, newOwner);

          // A non-owner returns before opening a connection or clearing the token, so the token
          // still being the new owner's is what shows the old instance left the database alone.
          oldInstance.stopManagedTcpServerIfOwner();
          assertEquals(
              "the previous instance must not release a database the new one owns",
              newOwner,
              System.getProperty(OWNERSHIP_KEY));
        });
  }

  private static final String OWNERSHIP_KEY = ReviewAiDb.class.getName() + ".tcpServerOwner";

  @Test
  public void reachabilityTracksARealServer() throws Exception {
    Path dir = tempFolder.newFolder("reachable").toPath();
    Server server = Server.createTcpServer("-tcpPort", "0", "-tcpDaemon", "-ifNotExists").start();
    ReviewAiDb db = new ReviewAiDb(dir, tcpUrl(server.getPort(), dir));
    try {
      assertTrue("a running server should be reachable", db.isDatabaseReachable());
    } finally {
      server.stop();
    }
    assertFalse("a stopped server should not be reachable", db.isDatabaseReachable());
  }

  @Test
  public void aBoundPortIsNotProofThatTheDatabaseIsUsable() throws Exception {
    // The reload case this guards: a server left behind by the previous plugin instance can still
    // hold the port while its database is closed or closing. Deciding by port rather than by query
    // means skipping our own server, and then connecting to a database inside H2's exclusive
    // open/close window - reported as "the database is open in exclusive mode", which names nothing
    // about the real cause.
    try (ServerSocket impostor = new ServerSocket(0)) {
      Thread rejecter =
          new Thread(
              () -> {
                try (Socket accepted = impostor.accept()) {
                  // Closing straight away gives the client EOF, so it fails fast rather than
                  // waiting
                  // for a handshake that will never arrive.
                  assertNotNull(accepted);
                } catch (IOException e) {
                  // The test finished and closed the socket; nothing to report.
                }
              });
      rejecter.setDaemon(true);
      rejecter.start();

      ReviewAiDb db =
          new ReviewAiDb(
              tempFolder.newFolder("impostor").toPath(),
              tcpUrl(impostor.getLocalPort(), tempFolder.getRoot().toPath()));

      assertFalse(
          "a port that accepts connections must not be taken for a usable database",
          db.isDatabaseReachable());
    }
  }

  @Test
  public void reachabilityTimesOutWhenServerStopsResponding() throws Exception {
    try (ServerSocket impostor = new ServerSocket(0)) {
      CountDownLatch acceptedConnection = new CountDownLatch(1);
      CountDownLatch releaseConnection = new CountDownLatch(1);
      Thread staller =
          new Thread(
              () -> {
                try (Socket ignored = impostor.accept()) {
                  acceptedConnection.countDown();
                  releaseConnection.await();
                } catch (IOException e) {
                  // The test finished and closed the socket; nothing to report.
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              });
      staller.setDaemon(true);
      staller.start();

      ReviewAiDb db =
          new ReviewAiDb(
              tempFolder.newFolder("stalled-impostor").toPath(),
              tcpUrl(impostor.getLocalPort(), tempFolder.getRoot().toPath())
                  + ";NETWORK_TIMEOUT=0");
      ExecutorService probeExecutor = Executors.newSingleThreadExecutor();
      try {
        Future<Boolean> reachable = probeExecutor.submit(db::isDatabaseReachable);
        assertTrue(
            "the impostor did not accept the probe", acceptedConnection.await(5, TimeUnit.SECONDS));
        assertFalse(
            "a server that stops responding must not block the reachability probe",
            reachable.get(5, TimeUnit.SECONDS));
      } finally {
        releaseConnection.countDown();
        probeExecutor.shutdownNow();
      }
    }
  }

  private static String tcpUrl(int port, Path dir) {
    return "jdbc:h2:tcp://localhost:" + port + "/" + dir + "/reviewai";
  }

  /**
   * A database routed through the managed TCP server, without starting one.
   *
   * <p>Claiming ownership only decides who may later shut the database down; it does not connect.
   * So the ownership rules can be exercised without binding port 9092 or touching a running Gerrit.
   */
  private ReviewAiDb tcpRoutedDb(String name) throws Exception {
    Path dir = tempFolder.newFolder(name).toPath();
    return new ReviewAiDb(dir, "jdbc:h2:tcp://localhost:9092/" + dir + "/reviewai");
  }

  private static void withOwnershipTokenCleared(ThrowingRunnable body) throws Exception {
    String previous = System.getProperty(OWNERSHIP_KEY);
    System.clearProperty(OWNERSHIP_KEY);
    try {
      body.run();
    } finally {
      if (previous == null) {
        System.clearProperty(OWNERSHIP_KEY);
      } else {
        System.setProperty(OWNERSHIP_KEY, previous);
      }
    }
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}

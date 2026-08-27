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

import static com.googlesource.gerrit.plugins.reviewai.utils.JdbcUtils.hasColumn;

import com.google.gerrit.extensions.annotations.PluginData;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.h2.tools.Server;

@Slf4j
@Singleton
public class ReviewAiDb {
  private static final String DB_FILE_NAME = "reviewai";
  private static final String TCP_HOST = "localhost";
  private static final int TCP_PORT = 9092;
  private static final String TCP_URL_PREFIX = "jdbc:h2:tcp://" + TCP_HOST + ":" + TCP_PORT + "/";
  private static final Object TCP_SERVER_LOCK = new Object();

  private static Server tcpServer;
  private static String managedJdbcUrl;

  public static final String KEY_STORE_URL = "storeUrl";
  public static final String KEY_STORE_USERNAME = "storeUsername";
  public static final String KEY_STORE_PASSWORD = "storePassword";

  private final Path pluginDataDir;
  private volatile String jdbcUrl;
  private volatile DbDialect dialect;
  private volatile Properties connectionProperties;

  @Inject
  public ReviewAiDb(@PluginData Path pluginDataDir) throws IOException {
    Files.createDirectories(pluginDataDir);
    this.pluginDataDir = pluginDataDir;
    this.dialect = DbDialect.H2;
    this.jdbcUrl = buildJdbcUrl(pluginDataDir);
    this.connectionProperties = new Properties();
  }

  public ReviewAiDb(Path pluginDataDir, String jdbcUrl) throws IOException {
    Files.createDirectories(pluginDataDir);
    this.pluginDataDir = pluginDataDir;
    this.jdbcUrl = jdbcUrl;
    this.dialect = DbDialect.fromJdbcUrl(jdbcUrl);
    this.connectionProperties = new Properties();
  }

  /**
   * Reconfigures the database connection with an external JDBC URL. Must be called before any
   * schema initialization or connection use. Called by ConfigCreator if storeUrl is present.
   */
  public void applyConfig(String storeUrl, String storeUsername, String storePassword) {
    if (storeUrl == null || storeUrl.isBlank()) {
      return;
    }
    this.dialect = DbDialect.fromJdbcUrl(storeUrl);
    this.jdbcUrl = storeUrl;
    Properties props = new Properties();
    if (storeUsername != null && !storeUsername.isBlank()) {
      props.setProperty("user", storeUsername);
    }
    if (storePassword != null && !storePassword.isBlank()) {
      props.setProperty("password", storePassword);
    }
    this.connectionProperties = props;
    log.info("ReviewAiDb configured for {} via {}", dialect, storeUrl);
  }

  public DbDialect getDialect() {
    return dialect;
  }

  public static String buildJdbcUrl(Path pluginDataDir) {
    Path dbFile = pluginDataDir.resolve(DB_FILE_NAME).toAbsolutePath();
    return TCP_URL_PREFIX + dbFile + ";AUTO_SERVER=FALSE;DB_CLOSE_DELAY=-1";
  }

  public Path getPluginDataDir() {
    return pluginDataDir;
  }

  public Connection getConnection() throws SQLException {
    if (dialect.needsTcpServer()) {
      ensureTcpServerStarted();
    }
    Properties props = connectionProperties;
    if (props != null && !props.isEmpty()) {
      return DriverManager.getConnection(jdbcUrl, props);
    }
    return DriverManager.getConnection(jdbcUrl);
  }

  public <T> T withConnection(ConnectionCallback<T> callback) throws SQLException {
    try (Connection c = getConnection()) {
      return callback.execute(c);
    }
  }

  public void initLangChainChatMemorySchema() throws SQLException {
    executeSchema(
        "CREATE TABLE IF NOT EXISTS langchain_chat_memory_messages ("
            + getDialect().autoIncrementPk("id")
            + ", change_id VARCHAR(512) NOT NULL"
            + ", patch_set INT NOT NULL"
            + ", scope VARCHAR(64) NOT NULL"
            + ", message_json " + getDialect().clobType() + " NOT NULL"
            + ", updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP"
            + ")",
        "CREATE INDEX IF NOT EXISTS idx_langchain_chat_memory_messages_scope_lookup"
            + " ON langchain_chat_memory_messages(change_id, patch_set, scope, updated_at, id)");
  }

  public void initPluginDataSchema() throws SQLException {
    executeSchema(
        "CREATE TABLE IF NOT EXISTS plugin_data ("
            + "scope VARCHAR(512) NOT NULL"
            + ", data_key VARCHAR(255) NOT NULL"
            + ", data_value " + getDialect().clobType() + " NOT NULL"
            + ", updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP"
            + ", PRIMARY KEY(scope, data_key)"
            + ")");
  }

  public void initReviewConcernSchema() throws SQLException {
    executeSchema(
        "CREATE TABLE IF NOT EXISTS review_concern_ledgers ("
            + "change_id VARCHAR(512) PRIMARY KEY"
            + ", schema_version INT NOT NULL"
            + ", last_reviewed_commit VARCHAR(128)"
            + ", updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP"
            + ")",
        "ALTER TABLE review_concern_ledgers"
            + " ADD COLUMN IF NOT EXISTS last_reviewed_commit VARCHAR(128)",
        "CREATE TABLE IF NOT EXISTS review_concern_reviewers ("
            + "change_id VARCHAR(512) NOT NULL"
            + ", reviewer_kind VARCHAR(64) NOT NULL"
            + ", reviewer_name VARCHAR(255) NOT NULL"
            + ", reviewer_order INT NOT NULL"
            + ", updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP"
            + ", PRIMARY KEY(change_id, reviewer_kind, reviewer_name)"
            + ", UNIQUE(change_id, reviewer_order)"
            + ", FOREIGN KEY(change_id) REFERENCES review_concern_ledgers(change_id)"
            + " ON DELETE CASCADE"
            + ")",
        "CREATE TABLE IF NOT EXISTS review_concerns ("
            + "change_id VARCHAR(512) NOT NULL"
            + ", reviewer_kind VARCHAR(64) NOT NULL"
            + ", reviewer_name VARCHAR(255) NOT NULL"
            + ", concern_id VARCHAR(255) NOT NULL"
            + ", concern_order INT NOT NULL"
            + ", concern_status VARCHAR(32) NOT NULL"
            + ", concern_json "
            + getDialect().clobType()
            + " NOT NULL"
            + ", updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP"
            + ", PRIMARY KEY(change_id, reviewer_kind, reviewer_name, concern_id)"
            + ", UNIQUE(change_id, reviewer_kind, reviewer_name, concern_order)"
            + ", FOREIGN KEY(change_id, reviewer_kind, reviewer_name)"
            + " REFERENCES review_concern_reviewers(change_id, reviewer_kind, reviewer_name)"
            + " ON DELETE CASCADE"
            + ")",
        "CREATE INDEX IF NOT EXISTS idx_review_concerns_change_status"
            + " ON review_concerns(change_id, concern_status)");
  }

  public List<String> listReviewConcernChangeIds() throws SQLException {
    try (Connection connection = getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT change_id FROM review_concern_ledgers");
        ResultSet results = statement.executeQuery()) {
      List<String> changeIds = new ArrayList<>();
      while (results.next()) {
        changeIds.add(results.getString(1));
      }
      return changeIds;
    }
  }

  public void initReviewFeedbackSchema() throws SQLException {
    executeSchema(
        "CREATE TABLE IF NOT EXISTS review_feedback_memories ("
            + "change_id VARCHAR(512) PRIMARY KEY"
            + ", schema_version INT NOT NULL"
            + ", memory_json "
            + getDialect().clobType()
            + " NOT NULL"
            + ", updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP"
            + ")",
        "CREATE TABLE IF NOT EXISTS review_feedback_comments ("
            + "change_id VARCHAR(512) NOT NULL"
            + ", comment_id VARCHAR(255) NOT NULL"
            + ", processing_state VARCHAR(32) NOT NULL"
            + ", processing_token VARCHAR(36)"
            + ", updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP"
            + ", PRIMARY KEY(change_id, comment_id)"
            + ")",
        "CREATE INDEX IF NOT EXISTS idx_review_feedback_comments_pending"
            + " ON review_feedback_comments(change_id, processing_state, updated_at)");
  }

  public void initReviewAgentConversationSchema() throws SQLException {
    withConnection(
        c -> {
          try (Statement s = c.createStatement()) {
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS review_agent_conversations ("
                    + "change_id VARCHAR(512) NOT NULL"
                    + ", user_id BIGINT NOT NULL DEFAULT 0"
                    + ", conversation_id VARCHAR(255) NOT NULL"
                    + ", title VARCHAR(1024)"
                    + ", timestamp_millis BIGINT"
                    + ", updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP"
                    + ", PRIMARY KEY(change_id, user_id, conversation_id)"
                    + ")");
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS review_agent_conversation_turns ("
                    + "change_id VARCHAR(512) NOT NULL"
                    + ", user_id BIGINT NOT NULL DEFAULT 0"
                    + ", conversation_id VARCHAR(255) NOT NULL"
                    + ", turn_index INT NOT NULL"
                    + ", user_message_id BIGINT"
                    + ", turn_metadata_json " + getDialect().clobType() + " NOT NULL"
                    + ", timestamp_millis BIGINT"
                    + ", updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP"
                    + ", PRIMARY KEY(change_id, user_id, conversation_id, turn_index)"
                    + ")");
            addUserIdColumnIfMissing(c, s, "REVIEW_AGENT_CONVERSATIONS");
            addUserIdColumnIfMissing(c, s, "REVIEW_AGENT_CONVERSATION_TURNS");
            ensurePrimaryKeyIncludesUserId(
                c, s, "REVIEW_AGENT_CONVERSATIONS", "CHANGE_ID", "USER_ID", "CONVERSATION_ID");
            ensurePrimaryKeyIncludesUserId(
                c,
                s,
                "REVIEW_AGENT_CONVERSATION_TURNS",
                "CHANGE_ID",
                "USER_ID",
                "CONVERSATION_ID",
                "TURN_INDEX");
            String legacyTurnContentColumn = "turn_content_json";
            if (hasColumn(
                c, "REVIEW_AGENT_CONVERSATION_TURNS", legacyTurnContentColumn.toUpperCase())) {
              s.executeUpdate(
                  "UPDATE review_agent_conversation_turns "
                      + "SET turn_metadata_json = "
                      + legacyTurnContentColumn
                      + " WHERE "
                      + legacyTurnContentColumn
                      + " IS NOT NULL");
              s.executeUpdate(
                  "ALTER TABLE review_agent_conversation_turns DROP COLUMN "
                      + legacyTurnContentColumn);
            }
          }
          return null;
        });
  }

  private void addUserIdColumnIfMissing(Connection c, Statement s, String tableName)
      throws SQLException {
    if (!hasColumn(c, tableName, "USER_ID")) {
      s.executeUpdate("ALTER TABLE " + tableName + " ADD COLUMN user_id BIGINT NOT NULL DEFAULT 0");
    }
  }

  private void ensurePrimaryKeyIncludesUserId(
      Connection c, Statement s, String tableName, String... primaryKeyColumns)
      throws SQLException {
    if (primaryKeyColumns(c, tableName).contains("USER_ID")) {
      return;
    }
    s.executeUpdate("ALTER TABLE " + tableName + " DROP PRIMARY KEY");
    s.executeUpdate(
        "ALTER TABLE "
            + tableName
            + " ADD PRIMARY KEY("
            + String.join(", ", primaryKeyColumns)
            + ")");
  }

  private Set<String> primaryKeyColumns(Connection c, String tableName) throws SQLException {
    Set<String> columns = new HashSet<>();
    try (ResultSet rs = c.getMetaData().getPrimaryKeys(null, null, tableName)) {
      while (rs.next()) {
        columns.add(rs.getString("COLUMN_NAME"));
      }
    }
    return columns;
  }

  private void executeSchema(String... statements) throws SQLException {
    withConnection(
        c -> {
          try (Statement s = c.createStatement()) {
            for (String statement : statements) {
              s.executeUpdate(statement);
            }
          }
          return null;
        });
  }

  private void ensureTcpServerStarted() {
    if (!usesManagedTcpServer() || !dialect.needsTcpServer()) {
      return;
    }
    synchronized (TCP_SERVER_LOCK) {
      managedJdbcUrl = jdbcUrl;
      if ((tcpServer != null && tcpServer.isRunning(false)) || isTcpServerAvailable()) {
        return;
      }
      try {
        tcpServer =
            Server.createTcpServer(
                    "-tcpPort", Integer.toString(TCP_PORT), "-tcpDaemon", "-ifNotExists")
                .start();
      } catch (SQLException e) {
        throw new RuntimeException("Failed to start H2 TCP server for ReviewAI DB", e);
      }
    }
  }

  /**
   * Stops the managed TCP server <em>only</em> if this instance's JDBC URL is the one currently
   * registered as {@link #managedJdbcUrl}.
   *
   * <p>During a plugin reload, Gerrit calls {@code start()} on the new plugin before {@code stop()}
   * on the old one. The new {@code ReviewAiDb} constructor calls {@link #ensureTcpServerStarted()},
   * which overwrites {@code managedJdbcUrl} with the new instance's URL. When the old instance's
   * {@code stop()} fires, this guard prevents it from shutting down the TCP server that the new
   * plugin is still using.
   */
  public void stopManagedTcpServerIfOwner() {
    synchronized (TCP_SERVER_LOCK) {
      if (managedJdbcUrl != null && managedJdbcUrl.equals(jdbcUrl)) {
        try (Connection c = DriverManager.getConnection(managedJdbcUrl);
            Statement s = c.createStatement()) {
          s.execute("SHUTDOWN");
        } catch (SQLException e) {
          log.debug("Failed to shut down ReviewAI H2 database cleanly", e);
        }
        managedJdbcUrl = null;
      }
      if (managedJdbcUrl == null && tcpServer != null) {
        tcpServer.stop();
        tcpServer = null;
      }
    }
  }

  private boolean usesManagedTcpServer() {
    return jdbcUrl.startsWith(TCP_URL_PREFIX);
  }

  private static boolean isTcpServerAvailable() {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(TCP_HOST, TCP_PORT), 200);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  @FunctionalInterface
  public interface ConnectionCallback<T> {
    T execute(Connection c) throws SQLException;
  }
}

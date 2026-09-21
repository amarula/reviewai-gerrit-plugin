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
import static com.googlesource.gerrit.plugins.reviewai.utils.JdbcUtils.metadataIdentifier;

import com.google.common.annotations.VisibleForTesting;
import com.google.gerrit.extensions.annotations.PluginData;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.h2.tools.Server;

@Slf4j
@Singleton
public class ReviewAiDb {
  private static final int CURRENT_DB_VERSION = 1;
  private static final String DB_FILE_NAME = "reviewai";
  private static final String TCP_HOST = "localhost";
  private static final int TCP_PORT = 9092;
  private static final String TCP_URL_PREFIX = "jdbc:h2:tcp://" + TCP_HOST + ":" + TCP_PORT + "/";
  // A leftover server can hold the port briefly after we decide it is not serving. Bounded, because
  // a failure to start after this many attempts is a real failure and should be reported as one.
  private static final int TCP_SERVER_START_ATTEMPTS = 5;
  private static final long TCP_SERVER_START_RETRY_MILLIS = 200;
  private static final int TCP_DATABASE_REACHABILITY_TIMEOUT_MILLIS = 2_000;
  private static final Pattern NETWORK_TIMEOUT_SETTING =
      Pattern.compile("(?i);NETWORK_TIMEOUT=[^;]*");
  // Plugin reloads use separate classloaders, so ownership and its lock must be JVM-wide.
  private static final String TCP_SERVER_OWNER_KEY = ReviewAiDb.class.getName() + ".tcpServerOwner";
  private static final Object TCP_SERVER_LOCK = TCP_SERVER_OWNER_KEY.intern();

  private static Server tcpServer;

  public static final String KEY_STORE_URL = "storeUrl";
  public static final String KEY_STORE_USERNAME = "storeUsername";
  public static final String KEY_STORE_PASSWORD = "storePassword";

  private final Path pluginDataDir;
  private final String tcpServerOwner = UUID.randomUUID().toString();
  private boolean registeredTcpServerOwner;
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

  public ReviewAiDb(
      @PluginData Path pluginDataDir,
      @PluginName String pluginName,
      PluginConfigFactory configFactory)
      throws IOException {
    this(pluginDataDir);
    PluginConfig globalConfig = configFactory.getFromGerritConfig(pluginName);
    applyConfig(
        globalConfig.getString(KEY_STORE_URL),
        globalConfig.getString(KEY_STORE_USERNAME),
        globalConfig.getString(KEY_STORE_PASSWORD));
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
   * schema initialization or connection use.
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

  /** Initializes the existing schemas and records their version without directing migrations. */
  public void initSchema() throws SQLException {
    initLangChainChatMemorySchema();
    initPluginDataSchema();
    initReviewConcernSchema();
    initReviewFeedbackSchema();
    initAiRequestSchema();
    initReviewAgentConversationSchema();
    executeSchema(
        "CREATE TABLE IF NOT EXISTS db_versions ("
            + "version INT PRIMARY KEY"
            + ", applied_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP"
            + ")");
    withConnection(
        c -> {
          String sql =
              getDialect() == DbDialect.POSTGRESQL
                  ? "INSERT INTO db_versions(version) VALUES (?) ON CONFLICT (version) DO NOTHING"
                  : "MERGE INTO db_versions USING (SELECT CAST(? AS INT) AS version) incoming"
                      + " ON db_versions.version = incoming.version"
                      + " WHEN NOT MATCHED THEN INSERT (version) VALUES (incoming.version)";
          try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setInt(1, CURRENT_DB_VERSION);
            s.executeUpdate();
          }
          return null;
        });
  }

  public void initLangChainChatMemorySchema() throws SQLException {
    executeSchema(
        "CREATE TABLE IF NOT EXISTS langchain_chat_memory_messages ("
            + getDialect().autoIncrementPk("id")
            + ", change_id VARCHAR(512) NOT NULL"
            + ", patch_set INT NOT NULL"
            + ", scope VARCHAR(64) NOT NULL"
            + ", message_json "
            + getDialect().clobType()
            + " NOT NULL"
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
            + ", data_value "
            + getDialect().clobType()
            + " NOT NULL"
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
            + ", author_account_id INT"
            + ", processing_state VARCHAR(32) NOT NULL"
            + ", processing_token VARCHAR(36)"
            + ", updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP"
            + ", PRIMARY KEY(change_id, comment_id)"
            + ")",
        "ALTER TABLE review_feedback_comments" + " ADD COLUMN IF NOT EXISTS author_account_id INT",
        "CREATE INDEX IF NOT EXISTS idx_review_feedback_comments_pending"
            + " ON review_feedback_comments(change_id, processing_state, updated_at)");
  }

  public void initAiRequestSchema() throws SQLException {
    executeSchema(
        "CREATE TABLE IF NOT EXISTS ai_requests ("
            + getDialect().autoIncrementPk("queue_sequence")
            + ", request_id VARCHAR(255) NOT NULL UNIQUE"
            + ", gerrit_instance_id VARCHAR(255) NOT NULL"
            + ", change_number INT NOT NULL"
            + ", source_event_id VARCHAR(255)"
            + ", request_kind VARCHAR(32) NOT NULL"
            + ", admission_policy VARCHAR(32) NOT NULL"
            + ", request_state VARCHAR(32) NOT NULL"
            + ", payload_json "
            + getDialect().clobType()
            + " NOT NULL"
            + ", owner_id VARCHAR(255)"
            + ", lease_expires_at_millis BIGINT"
            + ", result_text "
            + getDialect().clobType()
            + ", created_at_millis BIGINT NOT NULL"
            + ", updated_at_millis BIGINT NOT NULL"
            + ")",
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_ai_requests_source_event"
            + " ON ai_requests(gerrit_instance_id, change_number, source_event_id)",
        "CREATE INDEX IF NOT EXISTS idx_ai_requests_change_queue"
            + " ON ai_requests(gerrit_instance_id, change_number, request_state, queue_sequence)",
        "CREATE TABLE IF NOT EXISTS ai_request_lanes ("
            + "gerrit_instance_id VARCHAR(255) NOT NULL"
            + ", change_number INT NOT NULL"
            + ", active_request_id VARCHAR(255)"
            + ", updated_at_millis BIGINT NOT NULL DEFAULT 0"
            + ", PRIMARY KEY(gerrit_instance_id, change_number)"
            + ")");
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
                    + ", turn_metadata_json "
                    + getDialect().clobType()
                    + " NOT NULL"
                    + ", timestamp_millis BIGINT"
                    + ", updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP"
                    + ", PRIMARY KEY(change_id, user_id, conversation_id, turn_index)"
                    + ")");
            addUserIdColumnIfMissing(s, "REVIEW_AGENT_CONVERSATIONS");
            addUserIdColumnIfMissing(s, "REVIEW_AGENT_CONVERSATION_TURNS");
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
                c,
                "REVIEW_AGENT_CONVERSATION_TURNS",
                legacyTurnContentColumn.toUpperCase(Locale.ROOT))) {
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

  private void addUserIdColumnIfMissing(Statement s, String tableName) throws SQLException {
    s.executeUpdate(
        "ALTER TABLE " + tableName + " ADD COLUMN IF NOT EXISTS user_id BIGINT NOT NULL DEFAULT 0");
  }

  private void ensurePrimaryKeyIncludesUserId(
      Connection c, Statement s, String tableName, String... primaryKeyColumns)
      throws SQLException {
    PrimaryKeyInfo primaryKey = primaryKey(c, tableName);
    if (primaryKey.columns().contains("USER_ID")) {
      return;
    }
    if (primaryKey.name() == null || primaryKey.name().isBlank()) {
      throw new SQLException("Could not determine primary key constraint for " + tableName);
    }
    s.executeUpdate(getDialect().dropPrimaryKey(tableName, quoteIdentifier(c, primaryKey.name())));
    try {
      s.executeUpdate(
          "ALTER TABLE "
              + tableName
              + " ADD PRIMARY KEY("
              + String.join(", ", primaryKeyColumns)
              + ")");
    } catch (SQLException e) {
      if (!primaryKey(c, tableName).columns().contains("USER_ID")) {
        throw e;
      }
    }
  }

  private PrimaryKeyInfo primaryKey(Connection c, String tableName) throws SQLException {
    Set<String> columns = new HashSet<>();
    String name = null;
    String metadataTableName = metadataIdentifier(c.getMetaData(), tableName);
    try (ResultSet rs = c.getMetaData().getPrimaryKeys(null, null, metadataTableName)) {
      while (rs.next()) {
        String column = rs.getString("COLUMN_NAME");
        if (column != null) {
          columns.add(column.toUpperCase(Locale.ROOT));
        }
        if (name == null) {
          name = rs.getString("PK_NAME");
        }
      }
    }
    return new PrimaryKeyInfo(name, columns);
  }

  private String quoteIdentifier(Connection c, String identifier) throws SQLException {
    String quote = c.getMetaData().getIdentifierQuoteString();
    if (quote == null || quote.isBlank()) {
      return identifier;
    }
    return quote + identifier.replace(quote, quote + quote) + quote;
  }

  private record PrimaryKeyInfo(String name, Set<String> columns) {}

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

  /**
   * Claims JVM-wide ownership of the managed database, so a previous plugin instance will not shut
   * it down underneath this one.
   *
   * <p>Called at the start of the plugin lifecycle rather than deferred to the first query.
   * Ownership is what {@link #stopManagedTcpServerIfOwner()} checks before tearing the database
   * down, so claiming it late lets an old instance legitimately shut the database down while the
   * new one is still starting. That surfaces as a connection landing inside H2's exclusive
   * open/close window — reported as "the database is open in exclusive mode" — which names nothing
   * about the real cause.
   */
  public void claimTcpServerOwnership() {
    if (!usesManagedTcpServer() || !dialect.needsTcpServer()) {
      return;
    }
    synchronized (TCP_SERVER_LOCK) {
      if (!registeredTcpServerOwner) {
        System.setProperty(TCP_SERVER_OWNER_KEY, tcpServerOwner);
        registeredTcpServerOwner = true;
      }
    }
  }

  private void ensureTcpServerStarted() {
    if (!usesManagedTcpServer() || !dialect.needsTcpServer()) {
      return;
    }
    claimTcpServerOwnership();
    synchronized (TCP_SERVER_LOCK) {
      if (tcpServer != null && tcpServer.isRunning(false)) {
        return;
      }
      startTcpServerUnlessDatabaseIsUsable();
    }
  }

  /**
   * Starts this instance's TCP server, unless the database is already usable through one.
   *
   * <p>The decision is made by <em>connecting</em>, not by probing the port. A socket that accepts
   * proves only that something is bound: a server left behind by the previous plugin instance can
   * still hold the port while its database is closed or closing, and treating that as healthy means
   * skipping our own server and then talking to a database in H2's exclusive open/close window —
   * which fails with "the database is open in exclusive mode" and says nothing about the real
   * cause.
   *
   * <p>Retries because that same leftover server can hold the port for a moment after we decide it
   * is not serving: the wait is bounded, and each attempt re-checks reachability first, so a
   * database that recovers on its own is used rather than duplicated.
   */
  private void startTcpServerUnlessDatabaseIsUsable() {
    RuntimeException lastFailure = null;
    for (int attempt = 1; attempt <= TCP_SERVER_START_ATTEMPTS; attempt++) {
      if (isDatabaseReachable()) {
        return;
      }
      try {
        tcpServer =
            Server.createTcpServer(
                    "-tcpPort", Integer.toString(TCP_PORT), "-tcpDaemon", "-ifNotExists")
                .start();
        return;
      } catch (SQLException e) {
        lastFailure = new RuntimeException("Failed to start H2 TCP server for ReviewAI DB", e);
        log.debug(
            "Attempt {} of {} to start the ReviewAI H2 TCP server failed",
            attempt,
            TCP_SERVER_START_ATTEMPTS,
            e);
        pauseBeforeTcpServerRetry();
      }
    }
    throw lastFailure;
  }

  private static void pauseBeforeTcpServerRetry() {
    try {
      Thread.sleep(TCP_SERVER_START_RETRY_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while waiting to start the H2 TCP server", e);
    }
  }

  /**
   * Shuts down the managed database and TCP server only if this instance still owns them.
   *
   * <p>During a plugin reload, Gerrit calls {@code start()} on the new plugin before {@code stop()}
   * on the old one. The new instance claims ownership on its first connection, using a JVM-wide
   * token because each plugin has its own classloader. Equal JDBC URLs do not imply equal owners.
   * Later connections from the old instance must not reclaim ownership.
   */
  public void stopManagedTcpServerIfOwner() {
    synchronized (TCP_SERVER_LOCK) {
      if (!registeredTcpServerOwner
          || !tcpServerOwner.equals(System.getProperty(TCP_SERVER_OWNER_KEY))) {
        return;
      }
      try (Connection c = DriverManager.getConnection(jdbcUrl, connectionProperties);
          Statement s = c.createStatement()) {
        s.execute("SHUTDOWN");
      } catch (SQLException e) {
        log.debug("Failed to shut down ReviewAI H2 database cleanly", e);
      }
      System.clearProperty(TCP_SERVER_OWNER_KEY);
      if (tcpServer != null) {
        tcpServer.stop();
        tcpServer = null;
      }
    }
  }

  private boolean usesManagedTcpServer() {
    return jdbcUrl.startsWith(TCP_URL_PREFIX);
  }

  /**
   * Whether the database can actually be queried at the configured URL.
   *
   * <p>Deliberately a connection rather than a port check: the question this answers is "is there a
   * working database behind that port", and only a query answers it.
   */
  @VisibleForTesting
  boolean isDatabaseReachable() {
    String reachabilityUrl = NETWORK_TIMEOUT_SETTING.matcher(jdbcUrl).replaceAll("");
    Properties reachabilityProperties = new Properties();
    reachabilityProperties.putAll(connectionProperties);
    reachabilityProperties.setProperty(
        "NETWORK_TIMEOUT", Integer.toString(TCP_DATABASE_REACHABILITY_TIMEOUT_MILLIS));
    try (Connection connection =
            DriverManager.getConnection(reachabilityUrl, reachabilityProperties);
        Statement statement = connection.createStatement();
        ResultSet results = statement.executeQuery("SELECT 1")) {
      return results.next();
    } catch (SQLException e) {
      log.debug("ReviewAI database is not reachable at {}", jdbcUrl, e);
      return false;
    }
  }

  @FunctionalInterface
  public interface ConnectionCallback<T> {
    T execute(Connection c) throws SQLException;
  }
}

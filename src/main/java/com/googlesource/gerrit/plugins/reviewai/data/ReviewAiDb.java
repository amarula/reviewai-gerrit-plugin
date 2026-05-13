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
import java.sql.SQLException;
import java.sql.Statement;
import org.h2.tools.Server;

@Singleton
public class ReviewAiDb {
  private static final String DB_FILE_NAME = "reviewai";
  private static final String TCP_HOST = "localhost";
  private static final int TCP_PORT = 9092;
  private static final String TCP_URL_PREFIX = "jdbc:h2:tcp://" + TCP_HOST + ":" + TCP_PORT + "/";
  private static final Object TCP_SERVER_LOCK = new Object();

  private static Server tcpServer;

  private final Path pluginDataDir;
  private final String jdbcUrl;

  @Inject
  public ReviewAiDb(@PluginData Path pluginDataDir) throws IOException {
    this(pluginDataDir, buildJdbcUrl(pluginDataDir));
  }

  public ReviewAiDb(Path pluginDataDir, String jdbcUrl) throws IOException {
    Files.createDirectories(pluginDataDir);
    this.pluginDataDir = pluginDataDir;
    this.jdbcUrl = jdbcUrl;
    ensureTcpServerStarted();
  }

  public static String buildJdbcUrl(Path pluginDataDir) {
    Path dbFile = pluginDataDir.resolve(DB_FILE_NAME).toAbsolutePath();
    return TCP_URL_PREFIX + dbFile + ";AUTO_SERVER=FALSE;DB_CLOSE_DELAY=-1";
  }

  public Path getPluginDataDir() {
    return pluginDataDir;
  }

  public Connection getConnection() throws SQLException {
    ensureTcpServerStarted();
    return DriverManager.getConnection(jdbcUrl);
  }

  public <T> T withConnection(ConnectionCallback<T> callback) throws SQLException {
    try (Connection c = getConnection()) {
      return callback.execute(c);
    }
  }

  public void initLangChainChatMemorySchema() throws SQLException {
    executeSchema(
        """
        CREATE TABLE IF NOT EXISTS langchain_chat_memory_messages (
          id IDENTITY PRIMARY KEY,
          change_id VARCHAR(512) NOT NULL,
          patch_set INT NOT NULL,
          scope VARCHAR(64) NOT NULL,
          message_json CLOB NOT NULL,
          updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
        """,
        """
        CREATE INDEX IF NOT EXISTS idx_langchain_chat_memory_messages_scope_lookup
        ON langchain_chat_memory_messages(change_id, patch_set, scope, updated_at, id)
        """);
  }

  public void initPluginDataSchema() throws SQLException {
    executeSchema(
        """
        CREATE TABLE IF NOT EXISTS plugin_data (
          scope VARCHAR(512) NOT NULL,
          data_key VARCHAR(255) NOT NULL,
          data_value CLOB NOT NULL,
          updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY(scope, data_key)
        )
        """);
  }

  public void initReviewAgentConversationSchema() throws SQLException {
    withConnection(
        c -> {
          try (Statement s = c.createStatement()) {
            s.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS review_agent_conversations (
                  change_id VARCHAR(512) NOT NULL,
                  conversation_id VARCHAR(255) NOT NULL,
                  title VARCHAR(1024),
                  timestamp_millis BIGINT,
                  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY(change_id, conversation_id)
                )
                """);
            s.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS review_agent_conversation_turns (
                  change_id VARCHAR(512) NOT NULL,
                  conversation_id VARCHAR(255) NOT NULL,
                  turn_index INT NOT NULL,
                  user_message_id BIGINT,
                  turn_metadata_json CLOB NOT NULL,
                  timestamp_millis BIGINT,
                  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY(change_id, conversation_id, turn_index)
                )
                """);
            if (hasColumn(c, "REVIEW_AGENT_CONVERSATION_TURNS", "TURN_CONTENT_JSON")) {
              s.executeUpdate(
                  """
                  UPDATE review_agent_conversation_turns
                  SET turn_metadata_json = turn_content_json
                  WHERE turn_content_json IS NOT NULL
                  """);
              s.executeUpdate(
                  """
                  ALTER TABLE review_agent_conversation_turns
                  DROP COLUMN turn_content_json
                  """);
            }
          }
          return null;
        });
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
    if (!usesManagedTcpServer()) {
      return;
    }
    synchronized (TCP_SERVER_LOCK) {
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

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
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;

@Singleton
@Slf4j
public class PluginDataHandler {
  private static final Map<Path, Object> FILE_LOCKS = new ConcurrentHashMap<>();
  private static final String PATH_SUFFIX = ".data";
  private static final String KEY_REVIEW_AGENT_CONVERSATIONS = "reviewAgentConversations";

  private final Path configFile;
  private final Object fileLock;
  private final String scope;
  private final ReviewAiDb db;

  @Inject
  public PluginDataHandler(Path configFilePath) {
    this(configFilePath, null);
  }

  public PluginDataHandler(Path configFilePath, ReviewAiDb db) {
    this.configFile = configFilePath;
    this.fileLock =
        FILE_LOCKS.computeIfAbsent(configFile.toAbsolutePath().normalize(), path -> new Object());
    this.scope = scopeFrom(configFilePath);
    Path pluginDataDir = configFilePath.toAbsolutePath().getParent();
    try {
      this.db = db != null ? db : new ReviewAiDb(pluginDataDir);
      synchronized (fileLock) {
        initSchema();
        migrateLegacyProperties();
      }
    } catch (IOException | SQLException e) {
      log.error("Failed to initialize plugin data store", e);
      throw new RuntimeException(e);
    }
  }

  public synchronized void setValue(String key, String value) {
    log.debug("Setting value for key: {} with value: {}", key, value);
    synchronized (fileLock) {
      try (Connection c = db.getConnection();
          PreparedStatement ps =
              c.prepareStatement(
                  """
                  MERGE INTO plugin_data
                  KEY(scope, data_key)
                  VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                  """)) {
        ps.setString(1, scope);
        ps.setString(2, key);
        ps.setString(3, value);
        ps.executeUpdate();
      } catch (SQLException e) {
        log.error("Failed to store plugin data value for key {}", key, e);
        throw new RuntimeException(e);
      }
    }
  }

  public synchronized void setJsonValue(String key, Object value) {
    log.debug("Setting JSON value for key: {}", key);
    setValue(key, getGson().toJson(value));
  }

  public String getValue(String key) {
    log.debug("Getting value for key: {}", key);
    synchronized (fileLock) {
      try (Connection c = db.getConnection();
          PreparedStatement ps =
              c.prepareStatement(
                  """
                  SELECT data_value
                  FROM plugin_data
                  WHERE scope = ? AND data_key = ?
                  """)) {
        ps.setString(1, scope);
        ps.setString(2, key);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getString(1) : null;
        }
      } catch (SQLException e) {
        log.error("Failed to read plugin data value for key {}", key, e);
        throw new RuntimeException(e);
      }
    }
  }

  public <T> Map<String, T> getJsonObjectValue(String key, Class<T> clazz) {
    Type typeOfMap = TypeToken.getParameterized(Map.class, String.class, clazz).getType();
    return getJsonValue(key, typeOfMap);
  }

  public <T> T getJsonValue(String key, Type type) {
    log.debug("Getting JSON value for key: {}", key);
    String value = getValue(key);
    if (value == null || value.isEmpty()) {
      log.debug("No value found for key: {}", key);
      return null;
    }
    return getGson().fromJson(value, type);
  }

  public Map<String, String> getAllValues() {
    log.debug("Getting all properties");
    synchronized (fileLock) {
      try (Connection c = db.getConnection();
          PreparedStatement ps =
              c.prepareStatement(
                  """
                  SELECT data_key, data_value
                  FROM plugin_data
                  WHERE scope = ?
                  """)) {
        ps.setString(1, scope);
        try (ResultSet rs = ps.executeQuery()) {
          Map<String, String> allProperties = new HashMap<>();
          while (rs.next()) {
            allProperties.put(rs.getString(1), rs.getString(2));
          }
          return allProperties;
        }
      } catch (SQLException e) {
        log.error("Failed to read plugin data values", e);
        throw new RuntimeException(e);
      }
    }
  }

  public synchronized void removeValue(String key) {
    log.debug("Removing value for key: {}", key);
    synchronized (fileLock) {
      try (Connection c = db.getConnection();
          PreparedStatement ps =
              c.prepareStatement(
                  """
                  DELETE FROM plugin_data
                  WHERE scope = ? AND data_key = ?
                  """)) {
        ps.setString(1, scope);
        ps.setString(2, key);
        ps.executeUpdate();
      } catch (SQLException e) {
        log.error("Failed to remove plugin data value for key {}", key, e);
        throw new RuntimeException(e);
      }
    }
  }

  private void initSchema() throws SQLException {
    db.initPluginDataSchema();
  }

  private void migrateLegacyProperties() throws IOException, SQLException {
    if (Files.notExists(configFile)) {
      return;
    }
    java.util.Properties legacyProperties = new java.util.Properties();
    try (var input = Files.newInputStream(configFile)) {
      legacyProperties.load(input);
    }
    if (legacyProperties.isEmpty()) {
      return;
    }
    try (Connection c = db.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                """
                MERGE INTO plugin_data
                KEY(scope, data_key)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """)) {
      for (String key : legacyProperties.stringPropertyNames()) {
        if (KEY_REVIEW_AGENT_CONVERSATIONS.equals(key) || hasValue(c, key)) {
          continue;
        }
        ps.setString(1, scope);
        ps.setString(2, key);
        ps.setString(3, legacyProperties.getProperty(key));
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private boolean hasValue(Connection c, String key) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            """
            SELECT 1
            FROM plugin_data
            WHERE scope = ? AND data_key = ?
            LIMIT 1
            """)) {
      ps.setString(1, scope);
      ps.setString(2, key);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private static String scopeFrom(Path configFilePath) {
    String fileName = configFilePath.getFileName().toString();
    return fileName.endsWith(PATH_SUFFIX)
        ? fileName.substring(0, fileName.length() - PATH_SUFFIX.length())
        : fileName;
  }
}

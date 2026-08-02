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

/** SQL dialect abstraction for H2 (default, single-node) and PostgreSQL (multi-site). */
public enum DbDialect {
  H2 {
    @Override
    public String clobType() {
      return "CLOB";
    }

    @Override
    public String autoIncrementPk(String column) {
      return column + " IDENTITY PRIMARY KEY";
    }

    @Override
    public String upsert(
        String table,
        String insertColumns,
        String valuePlaceholders,
        String conflictColumns,
        String updateSetClause) {
      return "MERGE INTO " + table
          + " (" + insertColumns + ")"
          + " KEY(" + conflictColumns + ")"
          + " VALUES (" + valuePlaceholders + ")";
    }

    @Override
    public boolean needsTcpServer() {
      return true;
    }
  },

  POSTGRESQL {
    @Override
    public String clobType() {
      return "TEXT";
    }

    @Override
    public String autoIncrementPk(String column) {
      return column + " BIGSERIAL PRIMARY KEY";
    }

    @Override
    public String upsert(
        String table,
        String insertColumns,
        String valuePlaceholders,
        String conflictColumns,
        String updateSetClause) {
      return "INSERT INTO " + table
          + " (" + insertColumns + ")"
          + " VALUES (" + valuePlaceholders + ")"
          + " ON CONFLICT (" + conflictColumns + ")"
          + " DO UPDATE SET " + updateSetClause;
    }

    @Override
    public boolean needsTcpServer() {
      return false;
    }
  };

  /** Returns the text type for storing large values (CLOB for H2, TEXT for PostgreSQL). */
  public abstract String clobType();

  /** Returns the auto-increment primary key column definition. */
  public abstract String autoIncrementPk(String column);

  /**
   * Returns an upsert SQL statement for the given table.
   *
   * @param table table name
   * @param insertColumns comma-separated column names
   * @param valuePlaceholders comma-separated ? placeholders
   * @param conflictColumns comma-separated key columns for conflict detection
   * @param updateSetClause SET clause for the ON CONFLICT DO UPDATE (PostgreSQL only; H2 ignores)
   */
  public abstract String upsert(
      String table,
      String insertColumns,
      String valuePlaceholders,
      String conflictColumns,
      String updateSetClause);

  /** Whether this dialect requires an embedded TCP server (H2) or not (PostgreSQL). */
  public abstract boolean needsTcpServer();

  /** Determine the dialect from a JDBC URL prefix. */
  public static DbDialect fromJdbcUrl(String jdbcUrl) {
    if (jdbcUrl == null) {
      return H2;
    }
    String lower = jdbcUrl.toLowerCase();
    if (lower.startsWith("jdbc:postgresql:")) {
      return POSTGRESQL;
    }
    return H2;
  }
}

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

package com.googlesource.gerrit.plugins.reviewai.utils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

public class JdbcUtils {
  public static void setLongOrNull(PreparedStatement ps, int index, Long value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.BIGINT);
    } else {
      ps.setLong(index, value);
    }
  }

  public static boolean hasTable(Connection c, String tableName) throws SQLException {
    try (ResultSet rs = c.getMetaData().getTables(null, null, tableName, null)) {
      return rs.next();
    }
  }

  public static boolean hasColumn(Connection c, String tableName, String columnName)
      throws SQLException {
    try (ResultSet rs = c.getMetaData().getColumns(null, null, tableName, columnName)) {
      return rs.next();
    }
  }

  private JdbcUtils() {}
}

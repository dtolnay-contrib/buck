/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.build.engine.buildinfo;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.sqlite.RetryBusyHandler;
import com.facebook.buck.util.sqlite.SQLiteUtils;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.sqlite.BusyHandler;

public class SQLiteBuildInfoStore implements BuildInfoStore {
  private final Connection connection;
  private final PreparedStatement selectStmt;
  private final PreparedStatement selectAllStmt;
  private final PreparedStatement updateStmt;
  private final PreparedStatement deleteStmt;

  private final boolean shouldCacheInMemory;
  private final Map<BuildTarget, Map<String, String>> metaDataCache;

  private static final String EMPTY_VALUE = "EMPTY";

  public SQLiteBuildInfoStore(
      ProjectFilesystem filesystem, Optional<Map<BuildTarget, Map<String, String>>> metaDataCache)
      throws IOException {
    SQLiteUtils.initialize();
    String dbPath =
        filesystem
            .getRootPath()
            .resolve(filesystem.getBuckPaths().getScratchDir().resolve("metadata.db"))
            .toString();
    filesystem.createParentDirs(dbPath);
    try {
      Class.forName("org.sqlite.JDBC");
      connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
      connection.createStatement().executeUpdate("PRAGMA SYNCHRONOUS = OFF");
      connection.createStatement().executeUpdate("PRAGMA JOURNAL_MODE = WAL");
      connection
          .createStatement()
          .executeUpdate(
              "CREATE TABLE IF NOT EXISTS metadata "
                  + "(target TEXT, key TEXT, value TEXT, "
                  + "PRIMARY KEY (target, key)) "
                  + "WITHOUT ROWID");
      selectStmt =
          connection.prepareStatement("SELECT value FROM metadata WHERE target = ? AND key = ?");
      selectAllStmt =
          connection.prepareStatement("SELECT key, value FROM metadata WHERE target = ?");

      updateStmt =
          connection.prepareStatement(
              "INSERT OR REPLACE INTO metadata (target, key, value) VALUES (?, ?, ?)");
      deleteStmt = connection.prepareStatement("DELETE FROM metadata WHERE target = ?");
      BusyHandler.setHandler(connection, new RetryBusyHandler());
    } catch (ClassNotFoundException | SQLException e) {
      throw new IOException(e);
    }

    shouldCacheInMemory = metaDataCache.isPresent();
    if (shouldCacheInMemory) {
      this.metaDataCache = metaDataCache.get();
    } else {
      this.metaDataCache = null;
    }
  }

  @Override
  public void close() {
    try {
      connection.close();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public synchronized Optional<String> readMetadata(BuildTarget buildTarget, String key) {
    if (shouldCacheInMemory) {
      return readMetaDataFromCache(buildTarget, key);
    } else {
      return readMetaDataFromSQLite(buildTarget, key);
    }
  }

  private Optional<String> readMetaDataFromCache(BuildTarget buildTarget, String key) {
    Optional<String> cachedValue =
        Optional.ofNullable(metaDataCache.get(buildTarget)).map(metaData -> metaData.get(key));
    if (cachedValue.isPresent()) {
      if (cachedValue.get().equals(EMPTY_VALUE)) {
        return Optional.empty();
      } else {
        return cachedValue;
      }
    }

    Optional<String> metaData = readMetaDataFromSQLite(buildTarget, key);
    writeValueToCache(buildTarget, key, metaData);
    return metaData;
  }

  private Optional<String> readMetaDataFromSQLite(BuildTarget buildTarget, String key) {
    try {
      selectStmt.setString(1, cellRelativeName(buildTarget));
      selectStmt.setString(2, key);
      try (ResultSet rs = selectStmt.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        String value = rs.getString(1);

        return Optional.of(value);
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  private void writeValueToCache(BuildTarget buildTarget, String key, Optional<String> value) {
    if (!metaDataCache.containsKey(buildTarget)) {
      metaDataCache.put(buildTarget, new HashMap<>());
    }
    if (value.isPresent()) {
      metaDataCache.get(buildTarget).put(key, value.get());
    } else {
      metaDataCache.get(buildTarget).put(key, EMPTY_VALUE);
    }
  }

  @Override
  public synchronized ImmutableMap<String, String> getAllMetadata(BuildTarget buildTarget) {
    try {
      selectAllStmt.setString(1, cellRelativeName(buildTarget));
      try (ResultSet rs = selectAllStmt.executeQuery()) {
        ImmutableMap.Builder<String, String> result = ImmutableMap.builder();
        while (rs.next()) {
          result.put(rs.getString(1), rs.getString(2));
        }
        return result.build();
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public synchronized void updateMetadata(BuildTarget buildTarget, Map<String, String> metadata)
      throws IOException {
    try {
      for (Map.Entry<String, String> e : metadata.entrySet()) {
        updateStmt.setString(1, cellRelativeName(buildTarget));
        updateStmt.setString(2, e.getKey());
        updateStmt.setString(3, e.getValue());
        updateStmt.addBatch();
      }
      updateStmt.executeBatch();
    } catch (SQLException e) {
      throw new IOException(e);
    }

    if (shouldCacheInMemory) {
      Map<String, String> mutableMetaDataForCaching = new HashMap<>(metadata);
      metaDataCache.put(buildTarget, mutableMetaDataForCaching);
    }
  }

  @Override
  public synchronized void deleteMetadata(BuildTarget buildTarget) throws IOException {
    try {
      deleteStmt.setString(1, cellRelativeName(buildTarget));
      deleteStmt.executeUpdate();
    } catch (SQLException e) {
      throw new IOException(e);
    }

    if (shouldCacheInMemory) {
      metaDataCache.remove(buildTarget);
    }
  }

  private String cellRelativeName(BuildTarget buildTarget) {
    return buildTarget.getCellRelativeName();
  }
}

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

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritChangeRef;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Transactional persistence for durable, per-Change AI request queues. */
@Singleton
public final class AiRequestStore {
  private final ReviewAiDb db;

  @Inject
  public AiRequestStore(ReviewAiDb db) {
    this.db = db;
    try {
      db.initAiRequestSchema();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to initialize AI request queue schema", e);
    }
  }

  public Admission admit(AiRequestSubmission submission) {
    Objects.requireNonNull(submission, "submission");
    try (Connection connection = db.getConnection()) {
      connection.setAutoCommit(false);
      try {
        ensureAndLockLane(connection, submission.change());
        Optional<AiRequest> duplicate = findBySourceEvent(connection, submission);
        if (duplicate.isPresent()) {
          connection.commit();
          return new Admission(duplicate.get(), true);
        }
        AiRequest.State initialState =
            submission.admissionPolicy() == AiRequest.AdmissionPolicy.REJECT_IF_OCCUPIED
                    && isOccupied(connection, submission.change())
                ? AiRequest.State.REJECTED
                : AiRequest.State.QUEUED;
        insert(connection, submission, initialState);
        AiRequest request = get(connection, submission.requestId()).orElseThrow();
        connection.commit();
        return new Admission(request, false);
      } catch (SQLException | RuntimeException e) {
        rollback(connection, e);
        throw e;
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to admit AI request " + submission.requestId(), e);
    }
  }

  public Optional<AiRequest> claimNext(
      GerritChangeRef change, String ownerId, long leaseExpiresAtMillis) {
    Objects.requireNonNull(change, "change");
    requireNonBlank(ownerId, "ownerId");
    try (Connection connection = db.getConnection()) {
      connection.setAutoCommit(false);
      try {
        String activeRequestId = ensureAndLockLane(connection, change);
        if (activeRequestId != null) {
          connection.commit();
          return Optional.empty();
        }
        Optional<AiRequest> next = findFirstQueued(connection, change);
        if (next.isEmpty()) {
          deleteIdleLane(connection, change);
          connection.commit();
          return Optional.empty();
        }
        long now = System.currentTimeMillis();
        try (PreparedStatement updateRequest =
                connection.prepareStatement(
                    """
                    UPDATE ai_requests
                    SET request_state = ?, owner_id = ?, lease_expires_at_millis = ?,
                        updated_at_millis = ?
                    WHERE request_id = ? AND request_state = ?
                    """);
            PreparedStatement updateLane =
                connection.prepareStatement(
                    """
                    UPDATE ai_request_lanes
                    SET active_request_id = ?, updated_at_millis = ?
                    WHERE gerrit_instance_id = ? AND change_number = ?
                        AND active_request_id IS NULL
                    """)) {
          updateRequest.setString(1, AiRequest.State.RUNNING.name());
          updateRequest.setString(2, ownerId);
          updateRequest.setLong(3, leaseExpiresAtMillis);
          updateRequest.setLong(4, now);
          updateRequest.setString(5, next.get().requestId());
          updateRequest.setString(6, AiRequest.State.QUEUED.name());
          if (updateRequest.executeUpdate() != 1) {
            throw new IllegalStateException("Queued AI request could not be claimed");
          }
          updateLane.setString(1, next.get().requestId());
          updateLane.setLong(2, now);
          bindChange(updateLane, 3, change);
          if (updateLane.executeUpdate() != 1) {
            throw new IllegalStateException("AI request lane could not be claimed");
          }
        }
        AiRequest claimed = get(connection, next.get().requestId()).orElseThrow();
        connection.commit();
        return Optional.of(claimed);
      } catch (SQLException | RuntimeException e) {
        rollback(connection, e);
        throw e;
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to claim queued AI request for " + change, e);
    }
  }

  public boolean renewLease(String requestId, String ownerId, long leaseExpiresAtMillis) {
    requireNonBlank(requestId, "requestId");
    requireNonBlank(ownerId, "ownerId");
    try (Connection connection = db.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                UPDATE ai_requests
                SET lease_expires_at_millis = ?, updated_at_millis = ?
                WHERE request_id = ? AND request_state IN (?, ?) AND owner_id = ?
                """)) {
      statement.setLong(1, leaseExpiresAtMillis);
      statement.setLong(2, System.currentTimeMillis());
      statement.setString(3, requestId);
      statement.setString(4, AiRequest.State.RUNNING.name());
      statement.setString(5, AiRequest.State.SUPERSEDE_REQUESTED.name());
      statement.setString(6, ownerId);
      return statement.executeUpdate() == 1;
    } catch (SQLException e) {
      throw new RuntimeException("Failed to renew AI request lease " + requestId, e);
    }
  }

  public boolean complete(String requestId, String ownerId, String resultText) {
    return finish(requestId, ownerId, AiRequest.State.COMPLETED, resultText);
  }

  public boolean supersede(String requestId, String ownerId, String resultText) {
    return finish(requestId, ownerId, AiRequest.State.SUPERSEDED, resultText);
  }

  public boolean fail(String requestId, String ownerId, String failureText) {
    return finish(requestId, ownerId, AiRequest.State.FAILED, failureText);
  }

  public Optional<AiRequest> requestSupersession(GerritChangeRef change, String resultText) {
    Objects.requireNonNull(change, "change");
    try (Connection connection = db.getConnection()) {
      connection.setAutoCommit(false);
      try {
        String activeRequestId = ensureAndLockLane(connection, change);
        if (activeRequestId == null) {
          connection.commit();
          return Optional.empty();
        }
        Optional<AiRequest> active = getForUpdate(connection, activeRequestId);
        if (active.isEmpty()
            || active.get().kind() != AiRequest.Kind.REVIEW
            || active.get().state() != AiRequest.State.RUNNING) {
          connection.commit();
          return Optional.empty();
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                """
                UPDATE ai_requests
                SET request_state = ?, result_text = ?, updated_at_millis = ?
                WHERE request_id = ? AND request_state = ?
                """)) {
          statement.setString(1, AiRequest.State.SUPERSEDE_REQUESTED.name());
          statement.setString(2, resultText);
          statement.setLong(3, System.currentTimeMillis());
          statement.setString(4, activeRequestId);
          statement.setString(5, AiRequest.State.RUNNING.name());
          if (statement.executeUpdate() != 1) {
            throw new IllegalStateException("Running AI review could not request supersession");
          }
        }
        AiRequest requested = get(connection, activeRequestId).orElseThrow();
        connection.commit();
        return Optional.of(requested);
      } catch (SQLException | RuntimeException e) {
        rollback(connection, e);
        throw e;
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to request AI review supersession for " + change, e);
    }
  }

  public boolean isSupersessionRequested(String requestId) {
    return get(requestId)
        .map(request -> request.state() == AiRequest.State.SUPERSEDE_REQUESTED)
        .orElse(false);
  }

  public int abandonExpired(long expiredBeforeMillis, String failureText) {
    return abandonExpiredRequests(expiredBeforeMillis, failureText).size();
  }

  public List<AiRequest> abandonExpiredRequests(long expiredBeforeMillis, String failureText) {
    List<String> expiredRequestIds = new ArrayList<>();
    try (Connection connection = db.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT request_id
                FROM ai_requests
                WHERE request_state IN (?, ?) AND lease_expires_at_millis <= ?
                ORDER BY queue_sequence
                """)) {
      statement.setString(1, AiRequest.State.RUNNING.name());
      statement.setString(2, AiRequest.State.SUPERSEDE_REQUESTED.name());
      statement.setLong(3, expiredBeforeMillis);
      try (ResultSet results = statement.executeQuery()) {
        while (results.next()) {
          expiredRequestIds.add(results.getString(1));
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to list expired AI requests", e);
    }

    List<AiRequest> abandoned = new ArrayList<>();
    for (String requestId : expiredRequestIds) {
      if (abandon(requestId, expiredBeforeMillis, failureText)) {
        get(requestId).ifPresent(abandoned::add);
      }
    }
    return abandoned;
  }

  public Optional<AiRequest> get(String requestId) {
    requireNonBlank(requestId, "requestId");
    try (Connection connection = db.getConnection()) {
      return get(connection, requestId);
    } catch (SQLException e) {
      throw new RuntimeException("Failed to load AI request " + requestId, e);
    }
  }

  public List<AiRequest> listByChange(GerritChangeRef change) {
    Objects.requireNonNull(change, "change");
    try (Connection connection = db.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT *
                FROM ai_requests
                WHERE gerrit_instance_id = ? AND change_number = ?
                ORDER BY queue_sequence
                """)) {
      bindChange(statement, 1, change);
      try (ResultSet results = statement.executeQuery()) {
        List<AiRequest> requests = new ArrayList<>();
        while (results.next()) {
          requests.add(read(results));
        }
        return requests;
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to list AI requests for " + change, e);
    }
  }

  /** Removes all persisted AI request state for a Change. */
  public void deleteByChange(GerritChangeRef change) {
    Objects.requireNonNull(change, "change");
    try (Connection connection = db.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement deleteRequests =
              connection.prepareStatement(
                  "DELETE FROM ai_requests"
                      + " WHERE gerrit_instance_id = ? AND change_number = ?");
          PreparedStatement deleteLane =
              connection.prepareStatement(
                  "DELETE FROM ai_request_lanes"
                      + " WHERE gerrit_instance_id = ? AND change_number = ?")) {
        bindChange(deleteRequests, 1, change);
        deleteRequests.executeUpdate();
        bindChange(deleteLane, 1, change);
        deleteLane.executeUpdate();
        connection.commit();
      } catch (SQLException | RuntimeException e) {
        rollback(connection, e);
        throw e;
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to delete AI requests for " + change, e);
    }
  }

  public List<GerritChangeRef> listQueuedChanges(int limit) {
    if (limit <= 0) {
      return List.of();
    }
    try (Connection connection = db.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT r.gerrit_instance_id, r.change_number,
                    MIN(r.queue_sequence) AS first_sequence
                FROM ai_requests r
                JOIN ai_request_lanes l
                  ON l.gerrit_instance_id = r.gerrit_instance_id
                  AND l.change_number = r.change_number
                WHERE r.request_state = ? AND l.active_request_id IS NULL
                GROUP BY r.gerrit_instance_id, r.change_number
                ORDER BY first_sequence
                LIMIT ?
                """)) {
      statement.setString(1, AiRequest.State.QUEUED.name());
      statement.setInt(2, limit);
      try (ResultSet results = statement.executeQuery()) {
        List<GerritChangeRef> changes = new ArrayList<>();
        while (results.next()) {
          changes.add(new GerritChangeRef(results.getString(1), results.getInt(2)));
        }
        return changes;
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to list Changes with queued AI requests", e);
    }
  }

  public boolean hasQueuedRequest(GerritChangeRef change) {
    Objects.requireNonNull(change, "change");
    try (Connection connection = db.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM ai_requests
                WHERE gerrit_instance_id = ? AND change_number = ? AND request_state = ?
                """)) {
      bindChange(statement, 1, change);
      statement.setString(3, AiRequest.State.QUEUED.name());
      try (ResultSet results = statement.executeQuery()) {
        return results.next() && results.getLong(1) > 0;
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to inspect queued AI requests for " + change, e);
    }
  }

  private boolean finish(
      String requestId, String ownerId, AiRequest.State state, String resultText) {
    requireNonBlank(requestId, "requestId");
    requireNonBlank(ownerId, "ownerId");
    if (state != AiRequest.State.COMPLETED
        && state != AiRequest.State.FAILED
        && state != AiRequest.State.SUPERSEDED) {
      throw new IllegalArgumentException("Unsupported worker terminal state: " + state);
    }
    try (Connection connection = db.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Optional<AiRequest> existing = get(connection, requestId);
        if (existing.isEmpty()) {
          connection.commit();
          return false;
        }
        ensureAndLockLane(connection, existing.get().change());
        Optional<AiRequest> request = getForUpdate(connection, requestId);
        if (request.isEmpty()
            || (request.get().state() != AiRequest.State.RUNNING
                && request.get().state() != AiRequest.State.SUPERSEDE_REQUESTED)
            || !ownerId.equals(request.get().ownerId())) {
          connection.commit();
          return false;
        }
        boolean supersessionRequested =
            request.get().state() == AiRequest.State.SUPERSEDE_REQUESTED;
        updateTerminalRequest(
            connection,
            requestId,
            supersessionRequested ? AiRequest.State.SUPERSEDED : state,
            supersessionRequested ? request.get().resultText() : resultText);
        releaseLane(connection, request.get().change(), requestId);
        connection.commit();
        return true;
      } catch (SQLException | RuntimeException e) {
        rollback(connection, e);
        throw e;
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to finish AI request " + requestId, e);
    }
  }

  private boolean abandon(String requestId, long expiredBeforeMillis, String failureText) {
    try (Connection connection = db.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Optional<AiRequest> existing = get(connection, requestId);
        if (existing.isEmpty()) {
          connection.commit();
          return false;
        }
        ensureAndLockLane(connection, existing.get().change());
        Optional<AiRequest> request = getForUpdate(connection, requestId);
        if (request.isEmpty()
            || (request.get().state() != AiRequest.State.RUNNING
                && request.get().state() != AiRequest.State.SUPERSEDE_REQUESTED)
            || request.get().leaseExpiresAtMillis() == null
            || request.get().leaseExpiresAtMillis() > expiredBeforeMillis) {
          connection.commit();
          return false;
        }
        AiRequest.State terminalState =
            request.get().state() == AiRequest.State.SUPERSEDE_REQUESTED
                ? AiRequest.State.SUPERSEDED
                : AiRequest.State.ABANDONED;
        updateTerminalRequest(connection, requestId, terminalState, failureText);
        releaseLane(connection, request.get().change(), requestId);
        connection.commit();
        return true;
      } catch (SQLException | RuntimeException e) {
        rollback(connection, e);
        throw e;
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to abandon expired AI request " + requestId, e);
    }
  }

  private String ensureAndLockLane(Connection connection, GerritChangeRef change)
      throws SQLException {
    String upsert =
        db.getDialect()
            .upsert(
                "ai_request_lanes",
                "gerrit_instance_id, change_number",
                "?, ?",
                "gerrit_instance_id, change_number",
                "gerrit_instance_id = EXCLUDED.gerrit_instance_id");
    try (PreparedStatement statement = connection.prepareStatement(upsert)) {
      bindChange(statement, 1, change);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT active_request_id FROM ai_request_lanes"
                + " WHERE gerrit_instance_id = ? AND change_number = ? FOR UPDATE")) {
      bindChange(statement, 1, change);
      try (ResultSet results = statement.executeQuery()) {
        if (!results.next()) {
          throw new IllegalStateException("AI request lane was not created for " + change);
        }
        return results.getString(1);
      }
    }
  }

  private boolean isOccupied(Connection connection, GerritChangeRef change) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT COUNT(*)
            FROM ai_requests
            WHERE gerrit_instance_id = ? AND change_number = ?
                AND request_state IN (?, ?, ?)
            """)) {
      bindChange(statement, 1, change);
      statement.setString(3, AiRequest.State.QUEUED.name());
      statement.setString(4, AiRequest.State.RUNNING.name());
      statement.setString(5, AiRequest.State.SUPERSEDE_REQUESTED.name());
      try (ResultSet results = statement.executeQuery()) {
        if (!results.next()) {
          throw new IllegalStateException("Could not determine AI request lane occupancy");
        }
        return results.getLong(1) > 0;
      }
    }
  }

  private void insert(Connection connection, AiRequestSubmission submission, AiRequest.State state)
      throws SQLException {
    long now = System.currentTimeMillis();
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO ai_requests
                (request_id, gerrit_instance_id, change_number, source_event_id,
                 request_kind, admission_policy, request_state, payload_json, owner_id,
                 lease_expires_at_millis, result_text, created_at_millis, updated_at_millis)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?)
            """)) {
      statement.setString(1, submission.requestId());
      bindChange(statement, 2, submission.change());
      statement.setString(4, submission.sourceEventId());
      statement.setString(5, submission.kind().name());
      statement.setString(6, submission.admissionPolicy().name());
      statement.setString(7, state.name());
      statement.setString(8, submission.payloadJson());
      statement.setLong(9, now);
      statement.setLong(10, now);
      statement.executeUpdate();
    }
  }

  private Optional<AiRequest> findBySourceEvent(
      Connection connection, AiRequestSubmission submission) throws SQLException {
    if (submission.sourceEventId() == null) {
      return Optional.empty();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT *
            FROM ai_requests
            WHERE gerrit_instance_id = ? AND change_number = ? AND source_event_id = ?
            """)) {
      bindChange(statement, 1, submission.change());
      statement.setString(3, submission.sourceEventId());
      try (ResultSet results = statement.executeQuery()) {
        return results.next() ? Optional.of(read(results)) : Optional.empty();
      }
    }
  }

  private Optional<AiRequest> findFirstQueued(Connection connection, GerritChangeRef change)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT *
            FROM ai_requests
            WHERE gerrit_instance_id = ? AND change_number = ? AND request_state = ?
            ORDER BY queue_sequence
            LIMIT 1
            """)) {
      bindChange(statement, 1, change);
      statement.setString(3, AiRequest.State.QUEUED.name());
      try (ResultSet results = statement.executeQuery()) {
        return results.next() ? Optional.of(read(results)) : Optional.empty();
      }
    }
  }

  private Optional<AiRequest> get(Connection connection, String requestId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT * FROM ai_requests WHERE request_id = ?")) {
      statement.setString(1, requestId);
      try (ResultSet results = statement.executeQuery()) {
        return results.next() ? Optional.of(read(results)) : Optional.empty();
      }
    }
  }

  private Optional<AiRequest> getForUpdate(Connection connection, String requestId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT * FROM ai_requests WHERE request_id = ? FOR UPDATE")) {
      statement.setString(1, requestId);
      try (ResultSet results = statement.executeQuery()) {
        return results.next() ? Optional.of(read(results)) : Optional.empty();
      }
    }
  }

  private void updateTerminalRequest(
      Connection connection, String requestId, AiRequest.State state, String resultText)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE ai_requests
            SET request_state = ?, owner_id = NULL, lease_expires_at_millis = NULL,
                result_text = ?, updated_at_millis = ?
            WHERE request_id = ?
            """)) {
      statement.setString(1, state.name());
      statement.setString(2, resultText);
      statement.setLong(3, System.currentTimeMillis());
      statement.setString(4, requestId);
      statement.executeUpdate();
    }
  }

  private void releaseLane(Connection connection, GerritChangeRef change, String requestId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE ai_request_lanes
            SET active_request_id = NULL, updated_at_millis = ?
            WHERE gerrit_instance_id = ? AND change_number = ? AND active_request_id = ?
            """)) {
      statement.setLong(1, System.currentTimeMillis());
      bindChange(statement, 2, change);
      statement.setString(4, requestId);
      if (statement.executeUpdate() != 1) {
        throw new IllegalStateException("AI request does not own its Change lane: " + requestId);
      }
    }
  }

  private void deleteIdleLane(Connection connection, GerritChangeRef change) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "DELETE FROM ai_request_lanes"
                + " WHERE gerrit_instance_id = ? AND change_number = ?"
                + " AND active_request_id IS NULL")) {
      bindChange(statement, 1, change);
      statement.executeUpdate();
    }
  }

  private AiRequest read(ResultSet results) throws SQLException {
    long leaseExpiresAtMillis = results.getLong("lease_expires_at_millis");
    Long lease = results.wasNull() ? null : leaseExpiresAtMillis;
    return new AiRequest(
        results.getLong("queue_sequence"),
        results.getString("request_id"),
        new GerritChangeRef(
            results.getString("gerrit_instance_id"), results.getInt("change_number")),
        results.getString("source_event_id"),
        AiRequest.Kind.valueOf(results.getString("request_kind")),
        AiRequest.AdmissionPolicy.valueOf(results.getString("admission_policy")),
        AiRequest.State.valueOf(results.getString("request_state")),
        results.getString("payload_json"),
        results.getString("owner_id"),
        lease,
        results.getString("result_text"),
        results.getLong("created_at_millis"),
        results.getLong("updated_at_millis"));
  }

  private void rollback(Connection connection, Exception cause) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      cause.addSuppressed(rollbackFailure);
    }
  }

  private static void bindChange(
      PreparedStatement statement, int parameterIndex, GerritChangeRef change) throws SQLException {
    statement.setString(parameterIndex, change.instanceId());
    statement.setInt(parameterIndex + 1, change.changeNumber());
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  public record Admission(AiRequest request, boolean duplicate) {}
}

package io.github.kevinrabbe.minecraftserver.common.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.world.WorldEraId;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Append-only PostgreSQL authority for the server Chronicle.
 *
 * <p>The logical identity is {@code (source_kind, source_id, event_type)}. Repeating the same event is idempotent;
 * attempting to reuse that logical identity with different historical facts fails closed.</p>
 */
public final class ChronicleRepository {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;

    public ChronicleRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public ChronicleEvent record(ChronicleEventRequest request) throws SQLException {
        Objects.requireNonNull(request, "request");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<ChronicleEvent> existing = findBySource(
                        connection,
                        request.sourceKind(),
                        request.sourceId(),
                        request.eventType()
                );
                if (existing.isPresent()) {
                    ChronicleEvent result = requireSame(existing.orElseThrow(), request);
                    connection.commit();
                    return result;
                }

                UUID eventId = UUID.randomUUID();
                UUID insertedId = insertIfAbsent(connection, eventId, request);
                ChronicleEvent result;
                if (insertedId != null) {
                    result = load(connection, insertedId);
                } else {
                    result = requireSame(
                            findBySource(
                                    connection,
                                    request.sourceKind(),
                                    request.sourceId(),
                                    request.eventType()
                            ).orElseThrow(() -> new ChronicleException(
                                    "Chronicle source conflict disappeared before it could be read"
                            )),
                            request
                    );
                }
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public ChronicleEvent load(UUID eventId) throws SQLException {
        Objects.requireNonNull(eventId, "eventId");
        try (Connection connection = dataSource.getConnection()) {
            return load(connection, eventId);
        }
    }

    public Optional<ChronicleEvent> findBySource(String sourceKind, String sourceId, String eventType)
            throws SQLException {
        ChronicleEventRequest key = new ChronicleEventRequest(
                eventType,
                sourceKind,
                sourceId,
                null,
                java.time.Instant.EPOCH,
                Map.of()
        );
        try (Connection connection = dataSource.getConnection()) {
            return findBySource(connection, key.sourceKind(), key.sourceId(), key.eventType());
        }
    }

    public List<ChronicleEvent> listRecent(int limit) throws SQLException {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT event_id,
                            event_type,
                            source_kind,
                            source_id,
                            world_era_id,
                            occurred_at,
                            metadata::text AS metadata
                     FROM historical_events
                     ORDER BY occurred_at DESC, event_id DESC
                     LIMIT ?
                     """)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<ChronicleEvent> events = new ArrayList<>();
                while (rows.next()) {
                    events.add(readEvent(rows));
                }
                return List.copyOf(events);
            }
        }
    }

    private static UUID insertIfAbsent(
            Connection connection,
            UUID eventId,
            ChronicleEventRequest request
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO historical_events(
                    event_id,
                    event_type,
                    source_kind,
                    source_id,
                    world_era_id,
                    occurred_at,
                    metadata
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (source_kind, source_id, event_type) DO NOTHING
                RETURNING event_id
                """)) {
            statement.setObject(1, eventId);
            statement.setString(2, request.eventType());
            statement.setString(3, request.sourceKind());
            statement.setString(4, request.sourceId());
            if (request.worldEraId() == null) {
                statement.setNull(5, java.sql.Types.VARCHAR);
            } else {
                statement.setString(5, request.worldEraId().value());
            }
            statement.setTimestamp(6, Timestamp.from(request.occurredAt()));
            statement.setString(7, writeMetadata(request.metadata()));
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getObject("event_id", UUID.class) : null;
            }
        }
    }

    private static ChronicleEvent load(Connection connection, UUID eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT event_id,
                       event_type,
                       source_kind,
                       source_id,
                       world_era_id,
                       occurred_at,
                       metadata::text AS metadata
                FROM historical_events
                WHERE event_id = ?
                """)) {
            statement.setObject(1, eventId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ChronicleException("Unknown Chronicle event: " + eventId);
                }
                return readEvent(row);
            }
        }
    }

    private static Optional<ChronicleEvent> findBySource(
            Connection connection,
            String sourceKind,
            String sourceId,
            String eventType
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT event_id,
                       event_type,
                       source_kind,
                       source_id,
                       world_era_id,
                       occurred_at,
                       metadata::text AS metadata
                FROM historical_events
                WHERE source_kind = ? AND source_id = ? AND event_type = ?
                """)) {
            statement.setString(1, sourceKind);
            statement.setString(2, sourceId);
            statement.setString(3, eventType);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readEvent(row)) : Optional.empty();
            }
        }
    }

    private static ChronicleEvent readEvent(ResultSet row) throws SQLException {
        String eraId = row.getString("world_era_id");
        return new ChronicleEvent(
                row.getObject("event_id", UUID.class),
                row.getString("event_type"),
                row.getString("source_kind"),
                row.getString("source_id"),
                eraId == null ? null : new WorldEraId(eraId),
                row.getTimestamp("occurred_at").toInstant(),
                readMetadata(row.getString("metadata"))
        );
    }

    private static ChronicleEvent requireSame(ChronicleEvent existing, ChronicleEventRequest request) {
        if (!existing.asRequest().equals(request)) {
            throw new ChronicleException(
                    "Chronicle logical source already exists with different immutable history: "
                            + request.sourceKind() + "/" + request.sourceId() + "/" + request.eventType()
            );
        }
        return existing;
    }

    private static String writeMetadata(Map<String, String> metadata) {
        try {
            return JSON.writeValueAsString(new TreeMap<>(metadata));
        } catch (JsonProcessingException exception) {
            throw new ChronicleException("Could not serialize Chronicle metadata", exception);
        }
    }

    private static Map<String, String> readMetadata(String json) {
        try {
            Map<String, String> values = JSON.readValue(json, new TypeReference<>() { });
            return Map.copyOf(new TreeMap<>(values));
        } catch (JsonProcessingException | NullPointerException exception) {
            throw new ChronicleException("Could not parse Chronicle metadata", exception);
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}

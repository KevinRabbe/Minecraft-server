package io.github.kevinrabbe.minecraftserver.common.history;

import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.world.WorldEraId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ChronicleRepositoryIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private ChronicleRepository chronicle;

    @BeforeAll
    void openDatabase() {
        database = Database.open(new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                4
        ));
        database.migrate();
        dataSource = database.dataSource();
        chronicle = new ChronicleRepository(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE historical_events, world_eras RESTART IDENTITY CASCADE");
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void exactRetryReturnsSameHistoricalEvent() throws SQLException {
        Instant occurredAt = Instant.parse("2026-08-01T18:00:00Z");
        insertEra("founding", 0, occurredAt.minusSeconds(1));
        ChronicleEventRequest request = new ChronicleEventRequest(
                "FIRST_MAP_CLEAR",
                "MAP_RUN",
                UUID.randomUUID().toString(),
                new WorldEraId("founding"),
                occurredAt,
                Map.of("difficulty", "42", "player_count", "1")
        );

        ChronicleEvent first = chronicle.record(request);
        ChronicleEvent retry = chronicle.record(request);

        assertEquals(first, retry);
        assertEquals(1L, countHistoricalEvents());
    }

    @Test
    void sameLogicalSourceCannotRewriteHistoricalFacts() throws SQLException {
        String sourceId = UUID.randomUUID().toString();
        Instant occurredAt = Instant.parse("2026-08-01T18:00:00Z");
        ChronicleEventRequest original = new ChronicleEventRequest(
                "EXPANSION_RESOLVED",
                "EXPANSION_VOTE",
                sourceId,
                null,
                occurredAt,
                Map.of("winner", "fishing")
        );
        chronicle.record(original);

        ChronicleEventRequest conflicting = new ChronicleEventRequest(
                "EXPANSION_RESOLVED",
                "EXPANSION_VOTE",
                sourceId,
                null,
                occurredAt,
                Map.of("winner", "mining")
        );

        assertThrows(ChronicleException.class, () -> chronicle.record(conflicting));
        assertEquals(1L, countHistoricalEvents());
    }

    @Test
    void unknownWorldEraIsRejectedByPersistenceBoundary() {
        ChronicleEventRequest request = new ChronicleEventRequest(
                "WORLD_MILESTONE",
                "SYSTEM",
                "milestone-1",
                new WorldEraId("missing-era"),
                Instant.parse("2026-08-01T18:00:00Z"),
                Map.of()
        );

        assertThrows(SQLException.class, () -> chronicle.record(request));
    }

    @Test
    void historicalEventsRemainAppendOnly() throws SQLException {
        ChronicleEvent event = chronicle.record(new ChronicleEventRequest(
                "DAY_ZERO_OPENED",
                "SYSTEM",
                "day-zero",
                null,
                Instant.parse("2026-08-01T18:00:00Z"),
                Map.of("canonical", "true")
        ));

        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE historical_events
                     SET source_id = 'rewritten'
                     WHERE event_id = ?
                     """)) {
            update.setObject(1, event.eventId());
            assertThrows(SQLException.class, update::executeUpdate);
        }
    }

    @Test
    void recentHistoryUsesOccurredTimeThenStableEventIdOrdering() throws SQLException {
        chronicle.record(new ChronicleEventRequest(
                "WORLD_MILESTONE",
                "SYSTEM",
                "older",
                null,
                Instant.parse("2026-08-01T18:00:00Z"),
                Map.of()
        ));
        ChronicleEvent newest = chronicle.record(new ChronicleEventRequest(
                "WORLD_MILESTONE",
                "SYSTEM",
                "newer",
                null,
                Instant.parse("2026-08-02T18:00:00Z"),
                Map.of()
        ));

        List<ChronicleEvent> recent = chronicle.listRecent(10);
        assertEquals(2, recent.size());
        assertEquals(newest.eventId(), recent.getFirst().eventId());
    }

    private void insertEra(String eraId, int sequence, Instant startedAt) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO world_eras(era_id, sequence_no, started_at)
                     VALUES (?, ?, ?)
                     """)) {
            statement.setString(1, eraId);
            statement.setInt(2, sequence);
            statement.setTimestamp(3, Timestamp.from(startedAt));
            statement.executeUpdate();
        }
    }

    private long countHistoricalEvents() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM historical_events")) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}

package io.github.kevinrabbe.minecraftserver.common.analytics;

import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SessionAnalyticsRepositoryIntegrationTest {
    private static final Instant WINDOW_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-08-02T00:00:00Z");

    private final List<UUID> testPlayers = new ArrayList<>();
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;

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
        identities = new PlayerIdentityRepository(dataSource);
    }

    @AfterEach
    void removeTestPlayers() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM players WHERE player_id = ?")) {
            for (UUID playerId : testPlayers) {
                statement.setObject(1, playerId);
                statement.addBatch();
            }
            statement.executeBatch();
        } finally {
            testPlayers.clear();
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void summarySeparatesNewReturningAndObservedPlayerTime() throws Exception {
        UUID newPlayer = player("MetricNew");
        UUID returningPlayer = player("MetricReturn");
        UUID crossWindowPlayer = player("MetricCross");
        UUID partialPlayer = player("MetricPartial");

        session(newPlayer, "2026-08-01T10:00:00Z", "2026-08-01T11:30:00Z");

        session(returningPlayer, "2026-07-31T20:00:00Z", "2026-07-31T21:00:00Z");
        session(returningPlayer, "2026-08-01T12:00:00Z", "2026-08-01T14:00:00Z");

        session(crossWindowPlayer, "2026-07-31T23:30:00Z", "2026-08-01T00:30:00Z");

        // This session ends after the injected observation time and therefore contributes only 22:00 -> 23:00.
        session(partialPlayer, "2026-08-01T22:00:00Z", "2026-08-01T23:30:00Z");

        SessionAnalyticsRepository partialRepository = new SessionAnalyticsRepository(
                dataSource,
                Clock.fixed(Instant.parse("2026-08-01T23:00:00Z"), ZoneOffset.UTC)
        );
        SessionActivitySummary partial = partialRepository.summarize(WINDOW_START, WINDOW_END);

        assertEquals(Instant.parse("2026-08-01T23:00:00Z"), partial.observedThrough());
        assertEquals(4L, partial.uniquePlayers());
        assertEquals(2L, partial.newPlayers());
        assertEquals(2L, partial.returningPlayers());
        assertEquals(3L, partial.sessionsStarted());
        assertEquals(3L, partial.sessionsEnded());
        assertEquals(18_000L, partial.activePlayerSeconds());
        assertEquals(Duration.ofHours(5), partial.activePlayerTime());

        SessionAnalyticsRepository completedRepository = new SessionAnalyticsRepository(
                dataSource,
                Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC)
        );
        SessionActivitySummary completed = completedRepository.summarize(WINDOW_START, WINDOW_END);

        assertEquals(WINDOW_END, completed.observedThrough());
        assertEquals(4L, completed.uniquePlayers());
        assertEquals(2L, completed.newPlayers());
        assertEquals(2L, completed.returningPlayers());
        assertEquals(3L, completed.sessionsStarted());
        assertEquals(4L, completed.sessionsEnded());
        assertEquals(19_800L, completed.activePlayerSeconds());
        assertEquals(Duration.ofMinutes(330), completed.activePlayerTime());
    }

    @Test
    void futureWindowReturnsAZeroObservedSummary() throws Exception {
        SessionAnalyticsRepository repository = new SessionAnalyticsRepository(
                dataSource,
                Clock.fixed(Instant.parse("2026-08-01T23:00:00Z"), ZoneOffset.UTC)
        );

        Instant start = Instant.parse("2026-08-02T00:00:00Z");
        Instant end = Instant.parse("2026-08-03T00:00:00Z");
        SessionActivitySummary summary = repository.summarize(start, end);

        assertEquals(start, summary.observedThrough());
        assertEquals(0L, summary.uniquePlayers());
        assertEquals(0L, summary.newPlayers());
        assertEquals(0L, summary.returningPlayers());
        assertEquals(0L, summary.sessionsStarted());
        assertEquals(0L, summary.sessionsEnded());
        assertEquals(0L, summary.activePlayerSeconds());
    }

    private UUID player(String name) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        testPlayers.add(playerId);
        return playerId;
    }

    private void session(UUID playerId, String createdAt, String disconnectedAt) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO player_sessions(
                         network_session_id,
                         player_id,
                         state_version,
                         status,
                         created_at,
                         disconnected_at
                     ) VALUES (?, ?, 0, 'DISCONNECTED', ?, ?)
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, playerId);
            statement.setTimestamp(3, Timestamp.from(Instant.parse(createdAt)));
            statement.setTimestamp(4, Timestamp.from(Instant.parse(disconnectedAt)));
            statement.executeUpdate();
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

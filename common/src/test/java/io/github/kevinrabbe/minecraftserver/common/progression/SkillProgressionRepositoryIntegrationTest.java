package io.github.kevinrabbe.minecraftserver.common.progression;

import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SkillProgressionRepositoryIntegrationTest {
    private static final SkillId MINING = new SkillId("mining");

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private SkillProgressionRepository skills;

    @BeforeAll
    void openDatabase() {
        database = Database.open(new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                8
        ));
        database.migrate();
        dataSource = database.dataSource();
        identities = new PlayerIdentityRepository(dataSource);
        List<Long> thresholds = LongStream.rangeClosed(0, 100)
                .map(level -> level * 100L)
                .boxed()
                .toList();
        skills = new SkillProgressionRepository(
                dataSource,
                new SkillProgressionCatalog(List.of(
                        new SkillProgressionDefinition(MINING, thresholds)
                ))
        );
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        skill_xp_awards,
                        player_skills,
                        processed_operations,
                        players
                    RESTART IDENTITY CASCADE
                    """);
            statement.execute("""
                    UPDATE progression_state
                    SET active_skill_cap = 50,
                        state_version = 0,
                        source_operation_id = NULL,
                        changed_at = NOW()
                    WHERE singleton = TRUE
                    """);
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void awardStopsExactlyAtLaunchCapWithoutOverflow() throws SQLException {
        UUID playerId = newPlayer("Capper");

        SkillXpAwardResult first = skills.awardExperience(
                UUID.randomUUID(), playerId, MINING, 9_000L, "mining.ore"
        );
        assertEquals(5_000L, first.grantedExperience());
        assertEquals(5_000L, first.newExperience());
        assertEquals(50, first.newLevel());

        SkillXpAwardResult blocked = skills.awardExperience(
                UUID.randomUUID(), playerId, MINING, 1_000L, "mining.ore"
        );
        assertEquals(0L, blocked.grantedExperience());
        assertEquals(5_000L, blocked.newExperience());
        assertEquals(50, skills.load(playerId, MINING).level());
    }

    @Test
    void raisingCapReopensProgressionFromExactPersistedExperience() throws SQLException {
        UUID playerId = newPlayer("ExpansionMiner");
        skills.awardExperience(UUID.randomUUID(), playerId, MINING, 9_000L, "mining.ore");

        ActiveSkillCapState expanded = skills.advanceActiveCap(
                UUID.randomUUID(), SkillCapStage.EXPANSION_75, "world.skill_cap_75"
        );
        assertEquals(75, expanded.activeCap());

        SkillXpAwardResult award = skills.awardExperience(
                UUID.randomUUID(), playerId, MINING, 1_000L, "mining.ore"
        );
        assertEquals(1_000L, award.grantedExperience());
        assertEquals(6_000L, award.newExperience());
        assertEquals(60, award.newLevel());
    }

    @Test
    void capCannotSkipFromFiftyDirectlyToOneHundred() {
        assertThrows(
                SkillProgressionException.class,
                () -> skills.advanceActiveCap(UUID.randomUUID(), SkillCapStage.LATE_100, "world.skill_cap_100")
        );
    }

    @Test
    void sameXpOperationReturnsExactlySameCommittedResult() throws SQLException {
        UUID playerId = newPlayer("RetryMiner");
        UUID operationId = UUID.randomUUID();

        SkillXpAwardResult first = skills.awardExperience(
                operationId, playerId, MINING, 250L, "mining.ore"
        );
        SkillXpAwardResult retry = skills.awardExperience(
                operationId, playerId, MINING, 250L, "mining.ore"
        );

        assertEquals(first, retry);
        assertEquals(250L, skills.load(playerId, MINING).experience());
    }

    @Test
    void operationIdCannotBeReusedForDifferentXpRequest() throws SQLException {
        UUID playerId = newPlayer("BoundRetry");
        UUID operationId = UUID.randomUUID();
        skills.awardExperience(operationId, playerId, MINING, 100L, "mining.ore");

        assertThrows(
                SkillProgressionException.class,
                () -> skills.awardExperience(operationId, playerId, MINING, 101L, "mining.ore")
        );
    }

    @Test
    void concurrentAwardsSumExactlyWithoutLostUpdates() throws Exception {
        UUID playerId = newPlayer("ConcurrentMiner");

        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Callable<SkillXpAwardResult>> tasks = java.util.stream.IntStream.range(0, 20)
                    .<Callable<SkillXpAwardResult>>mapToObj(index -> () -> skills.awardExperience(
                            UUID.randomUUID(),
                            playerId,
                            MINING,
                            100L,
                            "mining.ore"
                    ))
                    .toList();
            List<Future<SkillXpAwardResult>> futures = executor.invokeAll(tasks);
            for (Future<SkillXpAwardResult> future : futures) {
                future.get();
            }
        }

        SkillProgressSnapshot snapshot = skills.load(playerId, MINING);
        assertEquals(2_000L, snapshot.experience());
        assertEquals(20, snapshot.level());
    }

    @Test
    void capAdvanceIsIdempotentAndRequestBound() throws SQLException {
        UUID operationId = UUID.randomUUID();
        ActiveSkillCapState first = skills.advanceActiveCap(
                operationId, SkillCapStage.EXPANSION_75, "world.skill_cap_75"
        );
        ActiveSkillCapState retry = skills.advanceActiveCap(
                operationId, SkillCapStage.EXPANSION_75, "world.skill_cap_75"
        );
        assertEquals(first, retry);

        assertThrows(
                SkillProgressionException.class,
                () -> skills.advanceActiveCap(operationId, SkillCapStage.LATE_100, "world.skill_cap_100")
        );
    }

    private UUID newPlayer(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}

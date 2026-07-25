package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class BountyBossMaterializationRepositoryIntegrationTest {
    private static final BountyFamilyId FAMILY = new BountyFamilyId("zombie");
    private static final String BACKEND = "paper-a";
    private static final String BOSS = "boss.zombie.t1";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private BountyBossMaterializationRepository materializations;

    @BeforeAll
    void openDatabase() {
        database = Database.open(new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                6
        ));
        database.migrate();
        dataSource = database.dataSource();
        identities = new PlayerIdentityRepository(dataSource);
        materializations = new BountyBossMaterializationRepository(
                dataSource,
                new BountyTierCatalog(List.of(new BountyTierDefinition(
                        FAMILY,
                        1,
                        100,
                        10,
                        BOSS,
                        List.of("material.zombie_essence")
                )))
        );
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        bounty_boss_materializations,
                        bounty_summons,
                        bounty_contracts,
                        processed_operations,
                        player_names,
                        player_state,
                        wallets,
                        players,
                        backends
                    RESTART IDENTITY CASCADE
                    """);
            statement.execute("INSERT INTO backends(backend_id, status) VALUES ('paper-a', 'ONLINE'), ('paper-b', 'ONLINE')");
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void activeOwnedSummonBindsExactlyOneEntityAndReplaysExactly() throws Exception {
        UUID summonId = insertActiveSummon("MatOwner", BACKEND);
        UUID entityUuid = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();

        BountyBossMaterializationSnapshot first = materializations.record(
                operationId,
                summonId,
                BACKEND,
                BOSS,
                entityUuid,
                "world",
                32.5,
                64.0,
                8.5
        );
        BountyBossMaterializationSnapshot replay = materializations.record(
                operationId,
                summonId,
                BACKEND,
                BOSS,
                entityUuid,
                "world",
                32.5,
                64.0,
                8.5
        );

        assertEquals(first, replay);
        assertEquals(first, materializations.findBySummon(summonId).orElseThrow());
        assertEquals(first, materializations.findByEntity(entityUuid).orElseThrow());

        assertThrows(
                BountyException.class,
                () -> materializations.record(
                        operationId,
                        summonId,
                        BACKEND,
                        BOSS,
                        entityUuid,
                        "world",
                        33.5,
                        64.0,
                        8.5
                )
        );
    }

    @Test
    void nonOwnerAndWrongBossDefinitionFailClosed() throws Exception {
        UUID summonId = insertActiveSummon("MatReject", BACKEND);

        assertThrows(
                BountyException.class,
                () -> materializations.record(
                        UUID.randomUUID(), summonId, "paper-b", BOSS, UUID.randomUUID(), "world", 32.5, 64.0, 8.5
                )
        );
        assertThrows(
                BountyException.class,
                () -> materializations.record(
                        UUID.randomUUID(), summonId, BACKEND, "boss.fake", UUID.randomUUID(), "world", 32.5, 64.0, 8.5
                )
        );
        assertEquals(java.util.Optional.empty(), materializations.findBySummon(summonId));
    }

    @Test
    void readySummonCannotMaterialize() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "MatReady");
        UUID contractId = insertContract(playerId);
        UUID summonId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO bounty_summons(summon_id, contract_id, status)
                     VALUES (?, ?, 'READY')
                     """)) {
            statement.setObject(1, summonId);
            statement.setObject(2, contractId);
            statement.executeUpdate();
        }

        assertThrows(
                BountyException.class,
                () -> materializations.record(
                        UUID.randomUUID(), summonId, BACKEND, BOSS, UUID.randomUUID(), "world", 32.5, 64.0, 8.5
                )
        );
    }

    @Test
    void oneMinecraftEntityCannotRepresentTwoSummons() throws Exception {
        UUID firstSummon = insertActiveSummon("MatOne", BACKEND);
        UUID secondSummon = insertActiveSummon("MatTwo", BACKEND);
        UUID entityUuid = UUID.randomUUID();

        materializations.record(
                UUID.randomUUID(), firstSummon, BACKEND, BOSS, entityUuid, "world", 32.5, 64.0, 8.5
        );
        assertThrows(
                SQLException.class,
                () -> materializations.record(
                        UUID.randomUUID(), secondSummon, BACKEND, BOSS, entityUuid, "world", 40.5, 64.0, 8.5
                )
        );
    }

    private UUID insertActiveSummon(String name, String backendId) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        UUID contractId = insertContract(playerId);
        UUID summonId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO bounty_summons(
                         summon_id,
                         contract_id,
                         status,
                         owner_backend_id,
                         lease_expires_at,
                         state_version,
                         activated_at
                     ) VALUES (?, ?, 'ACTIVE', ?, NOW() + INTERVAL '5 minutes', 1, NOW())
                     """)) {
            statement.setObject(1, summonId);
            statement.setObject(2, contractId);
            statement.setString(3, backendId);
            statement.executeUpdate();
        }
        return summonId;
    }

    private UUID insertContract(UUID playerId) throws SQLException {
        UUID contractId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO bounty_contracts(
                         contract_id,
                         player_id,
                         family_id,
                         tier,
                         status,
                         eligible_kill_progress,
                         required_eligible_kills,
                         summon_authorizations_remaining,
                         fee_operation_id,
                         state_version
                     ) VALUES (?, ?, ?, 1, 'SUMMONED', 10, 10, 0, ?, 2)
                     """)) {
            statement.setObject(1, contractId);
            statement.setObject(2, playerId);
            statement.setString(3, FAMILY.value());
            statement.setObject(4, UUID.randomUUID());
            statement.executeUpdate();
        }
        return contractId;
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }
}

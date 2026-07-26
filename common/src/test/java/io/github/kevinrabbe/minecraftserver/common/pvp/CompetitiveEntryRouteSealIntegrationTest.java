package io.github.kevinrabbe.minecraftserver.common.pvp;

import io.github.kevinrabbe.minecraftserver.common.clan.ClanMembershipRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanSnapshot;
import io.github.kevinrabbe.minecraftserver.common.control.BackendRegistry;
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
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompetitiveEntryRouteSealIntegrationTest {
    private static final String BACKEND = "legacy-route-seal";
    private static final Duration FRESHNESS = Duration.ofMinutes(1);
    private static final Duration LEASE = Duration.ofSeconds(60);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ClanMembershipRepository memberships;
    private BackendRegistry backends;
    private ClanWarLifecycleRepository wars;
    private ClanWarLoadoutReadinessRepository readiness;
    private CompetitiveDispatchRepository dispatchRepository;
    private CompetitiveDispatchService dispatchService;
    private CompetitiveEntryRouteRepository routes;

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
        memberships = new ClanMembershipRepository(dataSource);
        backends = new BackendRegistry(dataSource);
        wars = new ClanWarLifecycleRepository(dataSource, ClanWarRuleset.legacy189V1());
        readiness = new ClanWarLoadoutReadinessRepository(dataSource);

        CompetitiveExecutionRepository executions = new CompetitiveExecutionRepository(
                dataSource, FRESHNESS, Duration.ofMinutes(5)
        );
        CompetitiveExecutionService executionService = new CompetitiveExecutionService(
                executions,
                new RankedArenaRepository(dataSource, RankedArenaRuleset.legacy189V1()),
                wars,
                new ClanWarResolutionRepository(dataSource)
        );
        dispatchRepository = new CompetitiveDispatchRepository(dataSource, executions, FRESHNESS, LEASE);
        dispatchService = new CompetitiveDispatchService(dispatchRepository, executionService, LEASE);
        routes = new CompetitiveEntryRouteRepository(dataSource, FRESHNESS);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        competitive_execution_loadout_seals,
                        competitive_execution_loadout_items,
                        clan_war_loadout_confirmations,
                        competitive_player_execution_reservations,
                        competitive_runtime_principals,
                        competitive_execution_participants,
                        competitive_execution_specs,
                        competitive_result_reports,
                        competitive_executions,
                        clan_war_results,
                        clan_war_items,
                        clan_war_rosters,
                        clan_wars,
                        clan_war_ratings,
                        ranked_match_results,
                        ranked_match_participants,
                        ranked_matches,
                        ranked_ratings,
                        clan_invitations,
                        clan_commodity_balances,
                        clan_treasuries,
                        clan_members,
                        clans,
                        processed_operations,
                        player_sessions,
                        player_state,
                        player_names,
                        wallets,
                        players,
                        backends
                    RESTART IDENTITY CASCADE
                    """);
            statement.execute("""
                    INSERT INTO competitive_runtime_principals(
                        database_role,
                        backend_id,
                        max_execution_lease_seconds,
                        dispatch_enabled,
                        max_active_executions
                    ) VALUES ('route-seal-runtime', 'legacy-route-seal', 120, TRUE, 4)
                    """);
        }
        backends.registerOnline(BACKEND, 0);
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void missingClanWarLoadoutSealRemovesBothProxyRouteProjections() throws Exception {
        Player challenger = player("RouteSealA");
        Player defender = player("RouteSealB");
        ClanSnapshot challengerClan = memberships.createClan(
                UUID.randomUUID(), challenger.playerId(), "Seal Route A", randomTag()
        );
        ClanSnapshot defenderClan = memberships.createClan(
                UUID.randomUUID(), defender.playerId(), "Seal Route B", randomTag()
        );
        ClanWarSnapshot war = wars.challenge(
                UUID.randomUUID(), challenger.playerId(), challengerClan.clanId(), defenderClan.clanId()
        );
        wars.accept(UUID.randomUUID(), war.warId(), defender.playerId());
        wars.setRoster(
                UUID.randomUUID(), war.warId(), challenger.playerId(), challengerClan.clanId(), List.of(challenger.playerId())
        );
        wars.setRoster(
                UUID.randomUUID(), war.warId(), defender.playerId(), defenderClan.clanId(), List.of(defender.playerId())
        );
        wars.lockRoster(UUID.randomUUID(), war.warId());
        readiness.confirm(UUID.randomUUID(), war.warId(), challenger.playerId());
        readiness.confirm(UUID.randomUUID(), war.warId(), defender.playerId());

        CompetitiveDispatchCandidate candidate = dispatchRepository.listReadyActivities(100).stream()
                .filter(value -> value.activityKind() == CompetitiveActivityKind.CLAN_WAR)
                .filter(value -> value.activityId().equals(war.warId()))
                .findFirst()
                .orElseThrow();
        CompetitiveExecutionSnapshot active = dispatchService.dispatchCandidate(candidate).orElseThrow();

        assertTrue(routes.findByMinecraftUuid(challenger.minecraftUuid()).isPresent());
        assertEquals(2, routes.findAllActive().size());

        deleteSealBypassingTriggers(active.executionId());

        assertTrue(routes.findByMinecraftUuid(challenger.minecraftUuid()).isEmpty());
        assertTrue(routes.findByMinecraftUuid(defender.minecraftUuid()).isEmpty());
        assertTrue(routes.findAllActive().isEmpty());
    }

    private void deleteSealBypassingTriggers(UUID executionId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL session_replication_role = replica");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM competitive_execution_loadout_seals WHERE execution_id = ?"
                )) {
                    statement.setObject(1, executionId);
                    assertEquals(1, statement.executeUpdate());
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private Player player(String name) throws SQLException {
        UUID minecraftUuid = UUID.randomUUID();
        return new Player(identities.ensurePlayer(minecraftUuid, name), minecraftUuid);
    }

    private static String randomTag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private record Player(UUID playerId, UUID minecraftUuid) { }
}

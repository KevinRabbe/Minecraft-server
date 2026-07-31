package io.github.kevinrabbe.minecraftserver.common.pvp;

import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompetitiveRuntimeDatabasePrivilegeIntegrationTest {
    private Database database;
    private DataSource dataSource;

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
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void privilegedRuntimeFunctionsAreNotExecutableByPublic() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT routine_name
                     FROM information_schema.routine_privileges
                     WHERE routine_schema = 'public'
                       AND grantee = 'PUBLIC'
                       AND privilege_type = 'EXECUTE'
                       AND routine_name IN (
                           'require_competitive_runtime_backend',
                           'require_competitive_runtime_incarnation',
                           'competitive_runtime_register',
                           'competitive_runtime_heartbeat',
                           'competitive_runtime_mark_offline',
                           'competitive_runtime_poll_active',
                           'competitive_runtime_heartbeat_execution',
                           'competitive_runtime_submit_report',
                           'competitive_runtime_find_player_execution',
                           'competitive_runtime_page_loadout'
                       )
                     ORDER BY routine_name
                     """)) {
            try (ResultSet rows = statement.executeQuery()) {
                List<String> exposed = new ArrayList<>();
                while (rows.next()) exposed.add(rows.getString("routine_name"));
                assertTrue(exposed.isEmpty(), "runtime SECURITY DEFINER API leaked PUBLIC EXECUTE: " + exposed);
            }
        }
    }

    @Test
    void privilegedRuntimeFunctionsRemainSecurityDefinerWithFixedSearchPath() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT p.proname,
                            p.prosecdef,
                            COALESCE(array_to_string(p.proconfig, ','), '') AS config
                     FROM pg_proc p
                     JOIN pg_namespace n ON n.oid = p.pronamespace
                     WHERE n.nspname = 'public'
                       AND p.proname IN (
                           'require_competitive_runtime_backend',
                           'require_competitive_runtime_incarnation',
                           'competitive_runtime_register',
                           'competitive_runtime_heartbeat',
                           'competitive_runtime_mark_offline',
                           'competitive_runtime_poll_active',
                           'competitive_runtime_heartbeat_execution',
                           'competitive_runtime_submit_report',
                           'competitive_runtime_find_player_execution',
                           'competitive_runtime_page_loadout'
                       )
                     ORDER BY p.proname
                     """)) {
            try (ResultSet rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    count++;
                    assertTrue(rows.getBoolean("prosecdef"), rows.getString("proname") + " must be SECURITY DEFINER");
                    assertTrue(
                            rows.getString("config").contains("search_path=public, pg_temp"),
                            rows.getString("proname") + " must pin search_path"
                    );
                }
                assertEquals(10, count, "expected complete competitive runtime privileged function set");
            }
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

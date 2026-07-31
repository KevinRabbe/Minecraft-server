package io.github.kevinrabbe.minecraftserver.common.pvp;

import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompetitiveRuntimePollCapacityIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private UUID runtimeIncarnation;

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

    @BeforeEach
    void resetPrincipal() throws SQLException {
        runtimeIncarnation = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE competitive_runtime_principals, backends CASCADE");
            statement.execute("""
                    INSERT INTO competitive_runtime_principals(
                        database_role,
                        backend_id,
                        max_execution_lease_seconds,
                        dispatch_enabled,
                        max_active_executions
                    ) VALUES (SESSION_USER::TEXT, 'legacy-poll-capacity', 120, TRUE, 64)
                    """);
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT competitive_runtime_register(?, 0)"
             )) {
            statement.setObject(1, runtimeIncarnation);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
            }
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void pollCeilingMatchesMaximumDispatchCapacity() {
        assertDoesNotThrow(() -> poll(64));
        assertThrows(SQLException.class, () -> poll(65));
    }

    private void poll(int limit) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM competitive_runtime_poll_active(?, ?)"
             )) {
            statement.setObject(1, runtimeIncarnation);
            statement.setInt(2, limit);
            statement.executeQuery().close();
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

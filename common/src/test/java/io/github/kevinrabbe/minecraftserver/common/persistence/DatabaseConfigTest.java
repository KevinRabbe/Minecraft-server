package io.github.kevinrabbe.minecraftserver.common.persistence;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseConfigTest {
    @Test
    void usesLocalDevelopmentDefaults() {
        DatabaseConfig config = DatabaseConfig.fromEnvironment(Map.of());

        assertEquals("jdbc:postgresql://127.0.0.1:5432/minecraft", config.jdbcUrl());
        assertEquals("minecraft", config.username());
        assertEquals("minecraft-dev", config.password());
        assertEquals(4, config.maximumPoolSize());
    }

    @Test
    void readsExplicitEnvironmentValues() {
        DatabaseConfig config = DatabaseConfig.fromEnvironment(Map.of(
                "DATABASE_URL", "jdbc:postgresql://db:5432/game",
                "DATABASE_USER", "game_user",
                "DATABASE_PASSWORD", "secret",
                "DATABASE_POOL_SIZE", "8"
        ));

        assertEquals("jdbc:postgresql://db:5432/game", config.jdbcUrl());
        assertEquals("game_user", config.username());
        assertEquals("secret", config.password());
        assertEquals(8, config.maximumPoolSize());
    }

    @Test
    void rejectsInvalidPoolSize() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseConfig.fromEnvironment(Map.of("DATABASE_POOL_SIZE", "0"))
        );
    }
}

package io.github.kevinrabbe.minecraftserver.common.persistence;

import java.util.Map;
import java.util.Objects;

/** Runtime PostgreSQL connection configuration. */
public record DatabaseConfig(
        String jdbcUrl,
        String username,
        String password,
        int maximumPoolSize
) {
    private static final String DEFAULT_URL = "jdbc:postgresql://127.0.0.1:5432/minecraft";
    private static final String DEFAULT_USER = "minecraft";
    private static final String DEFAULT_PASSWORD = "minecraft-dev";
    private static final int DEFAULT_POOL_SIZE = 4;

    public DatabaseConfig {
        jdbcUrl = requireNonBlank(jdbcUrl, "jdbcUrl");
        username = requireNonBlank(username, "username");
        password = Objects.requireNonNull(password, "password");
        if (maximumPoolSize < 1) {
            throw new IllegalArgumentException("maximumPoolSize must be at least 1");
        }
    }

    public static DatabaseConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    public static DatabaseConfig fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");

        String url = valueOrDefault(environment, "DATABASE_URL", DEFAULT_URL);
        String user = valueOrDefault(environment, "DATABASE_USER", DEFAULT_USER);
        String password = environment.getOrDefault("DATABASE_PASSWORD", DEFAULT_PASSWORD);
        int poolSize = parsePositiveInt(environment.get("DATABASE_POOL_SIZE"), DEFAULT_POOL_SIZE, "DATABASE_POOL_SIZE");

        return new DatabaseConfig(url, user, password, poolSize);
    }

    private static String valueOrDefault(Map<String, String> environment, String name, String defaultValue) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static int parsePositiveInt(String value, int defaultValue, String name) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 1) {
                throw new IllegalArgumentException(name + " must be at least 1");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a positive integer", exception);
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}

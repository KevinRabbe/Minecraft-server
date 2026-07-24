package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.control.ZoneInstanceRegistry;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneInstanceStatus;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Temporary local-development host for one explicit zone instance on a Paper backend.
 *
 * <p>This is a bootstrap configuration, not the long-term scheduling model. The shared registry already supports
 * multiple instances per backend; a later instance manager can replace this without changing gameplay contracts.</p>
 */
final class BootstrapZoneInstance {
    private final ZoneInstanceRegistry registry;
    private final UUID instanceId;
    private final String zoneId;
    private final String templateVersion;
    private final String backendId;
    private final int softCapacity;
    private final int hardCapacity;

    private BootstrapZoneInstance(
            ZoneInstanceRegistry registry,
            UUID instanceId,
            String zoneId,
            String templateVersion,
            String backendId,
            int softCapacity,
            int hardCapacity
    ) {
        this.registry = registry;
        this.instanceId = instanceId;
        this.zoneId = zoneId;
        this.templateVersion = templateVersion;
        this.backendId = backendId;
        this.softCapacity = softCapacity;
        this.hardCapacity = hardCapacity;
    }

    static Optional<BootstrapZoneInstance> fromEnvironment(String backendId, DataSource dataSource) {
        return fromEnvironment(backendId, dataSource, System.getenv());
    }

    static Optional<BootstrapZoneInstance> fromEnvironment(
            String backendId,
            DataSource dataSource,
            Map<String, String> environment
    ) {
        String zoneId = trimToNull(environment.get("BOOTSTRAP_ZONE_ID"));
        if (zoneId == null) {
            return Optional.empty();
        }

        String templateVersion = valueOrDefault(environment, "BOOTSTRAP_ZONE_TEMPLATE", "v1");
        int softCapacity = positiveInt(environment, "BOOTSTRAP_ZONE_SOFT_CAPACITY", 20);
        int hardCapacity = positiveInt(environment, "BOOTSTRAP_ZONE_HARD_CAPACITY", Math.max(softCapacity, 25));
        if (hardCapacity < softCapacity) {
            throw new IllegalArgumentException("BOOTSTRAP_ZONE_HARD_CAPACITY must be >= soft capacity");
        }

        return Optional.of(new BootstrapZoneInstance(
                new ZoneInstanceRegistry(dataSource),
                UUID.randomUUID(),
                zoneId,
                templateVersion,
                backendId,
                softCapacity,
                hardCapacity
        ));
    }

    void start() throws SQLException {
        registry.registerStarting(
                instanceId,
                zoneId,
                templateVersion,
                backendId,
                softCapacity,
                hardCapacity
        );
        registry.heartbeat(instanceId, ZoneInstanceStatus.ACTIVE, 0);
    }

    void heartbeat(int playerCount) throws SQLException {
        registry.heartbeat(instanceId, ZoneInstanceStatus.ACTIVE, playerCount);
    }

    void stop() throws SQLException {
        registry.markStopped(instanceId);
    }

    UUID instanceId() {
        return instanceId;
    }

    String zoneId() {
        return zoneId;
    }

    private static String valueOrDefault(Map<String, String> environment, String name, String fallback) {
        String value = trimToNull(environment.get(name));
        return value == null ? fallback : value;
    }

    private static int positiveInt(Map<String, String> environment, String name, int fallback) {
        String value = trimToNull(environment.get(name));
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw new IllegalArgumentException(name + " must be >= 1");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

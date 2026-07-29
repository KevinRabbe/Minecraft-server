package io.github.kevinrabbe.minecraftserver.common.session;

import io.github.kevinrabbe.minecraftserver.common.control.ZoneRoute;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneRouter;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the initial persistent-MMO login destination without exposing backend identity to gameplay state.
 *
 * <p>A player with a durable logical location first attempts that location. A new player, a player without a durable
 * location, or a player whose saved zone currently has no healthy routable instance falls back to the configured
 * persistent Hub/Town zone. The Hub/Town itself must still have a healthy route; this class never chooses an arbitrary
 * backend or unrelated gameplay zone as a second fallback.</p>
 */
public final class PersistentMmoLoginRouter {
    private final PlayerZoneRoutingRepository playerZones;
    private final ZoneRouter zones;

    public PersistentMmoLoginRouter(DataSource dataSource, Duration heartbeatFreshness) {
        this(
                new PlayerZoneRoutingRepository(Objects.requireNonNull(dataSource, "dataSource")),
                new ZoneRouter(dataSource, heartbeatFreshness)
        );
    }

    public PersistentMmoLoginRouter(PlayerZoneRoutingRepository playerZones, ZoneRouter zones) {
        this.playerZones = Objects.requireNonNull(playerZones, "playerZones");
        this.zones = Objects.requireNonNull(zones, "zones");
    }

    public Optional<ZoneRoute> findInitialRoute(UUID minecraftUuid, String hubZoneId) throws SQLException {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        String normalizedHubZoneId = requireNonBlank(hubZoneId, "hubZoneId");

        Optional<String> savedZone = playerZones.findLogicalZone(minecraftUuid)
                .map(String::trim)
                .filter(value -> !value.isEmpty());

        if (savedZone.isPresent()) {
            String preferredZone = savedZone.orElseThrow();
            Optional<ZoneRoute> preferredRoute = zones.findPreferredActiveInstance(preferredZone);
            if (preferredRoute.isPresent()) {
                return preferredRoute;
            }
            if (preferredZone.equals(normalizedHubZoneId)) {
                return Optional.empty();
            }
        }

        return zones.findPreferredActiveInstance(normalizedHubZoneId);
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}

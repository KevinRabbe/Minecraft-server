package io.github.kevinrabbe.minecraftserver.common.control;

import java.util.UUID;

/** Infrastructure-only result of resolving a logical gameplay zone to one live instance/backend. */
public record ZoneRoute(
        String zoneId,
        UUID instanceId,
        String backendId
) {
}

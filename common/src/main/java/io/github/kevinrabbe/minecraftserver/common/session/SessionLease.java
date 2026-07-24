package io.github.kevinrabbe.minecraftserver.common.session;

import java.time.Instant;
import java.util.UUID;

public record SessionLease(
        UUID sessionId,
        UUID playerId,
        String ownerBackendId,
        UUID ownerInstanceId,
        long stateVersion,
        SessionStatus status,
        Instant leaseExpiresAt
) {
}

package io.github.kevinrabbe.minecraftserver.common.session;

import java.time.Instant;
import java.util.UUID;

public record RoutedTransfer(
        UUID transferId,
        UUID sessionId,
        UUID playerId,
        String sourceBackendId,
        String targetZoneId,
        String targetBackendId,
        UUID targetInstanceId,
        long expectedStateVersion,
        Instant expiresAt,
        Instant routedAt
) {
}

package io.github.kevinrabbe.minecraftserver.common.session;

import java.time.Instant;
import java.util.UUID;

public record TransferTicket(
        UUID transferId,
        UUID sessionId,
        UUID playerId,
        String sourceBackendId,
        String targetZoneId,
        long expectedStateVersion,
        Instant expiresAt
) {
}

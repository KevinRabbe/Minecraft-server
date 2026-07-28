package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Exact proxy-routing projection for one player already reserved into an ACTIVE competitive execution.
 * Contains routing/identity only; no persistent MMO inventory, economy, item, or custody state.
 */
public record CompetitiveEntryRoute(
        UUID executionId,
        CompetitiveActivityKind activityKind,
        UUID activityId,
        String backendId,
        UUID playerId,
        UUID minecraftUuid,
        String sideKey,
        UUID sideId,
        Instant leaseExpiresAt
) {
    public CompetitiveEntryRoute {
        executionId = Objects.requireNonNull(executionId, "executionId");
        activityKind = Objects.requireNonNull(activityKind, "activityKind");
        activityId = Objects.requireNonNull(activityId, "activityId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        minecraftUuid = Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        sideId = Objects.requireNonNull(sideId, "sideId");
        leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("backendId must not be blank");
        }
        backendId = backendId.trim();
        if (sideKey == null || sideKey.isBlank()) {
            throw new IllegalArgumentException("sideKey must not be blank");
        }
        sideKey = sideKey.trim();
        switch (activityKind) {
            case RANKED_ARENA -> {
                if (!sideKey.equals("A") && !sideKey.equals("B")) {
                    throw new IllegalArgumentException("Ranked entry route side must be A or B");
                }
                if (!sideId.equals(playerId)) {
                    throw new IllegalArgumentException("Ranked entry route sideId must equal playerId");
                }
            }
            case CLAN_WAR -> {
                if (!sideKey.equals("CHALLENGER") && !sideKey.equals("DEFENDER")) {
                    throw new IllegalArgumentException("Clan-War entry route side must be CHALLENGER or DEFENDER");
                }
            }
        }
    }
}

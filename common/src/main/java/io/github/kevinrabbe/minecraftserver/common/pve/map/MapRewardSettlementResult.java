package io.github.kevinrabbe.minecraftserver.common.pve.map;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record MapRewardSettlementResult(
        UUID runId,
        UUID settlementOperationId,
        int resolverVersion,
        Instant settledAt,
        List<MapRewardGrantSnapshot> grants
) {
    public MapRewardSettlementResult {
        runId = Objects.requireNonNull(runId, "runId");
        settlementOperationId = Objects.requireNonNull(settlementOperationId, "settlementOperationId");
        if (resolverVersion < 0) {
            throw new IllegalArgumentException("resolverVersion must be >= 0");
        }
        settledAt = Objects.requireNonNull(settledAt, "settledAt");
        grants = List.copyOf(Objects.requireNonNull(grants, "grants"));
        if (grants.isEmpty()) {
            throw new IllegalArgumentException("Map reward settlement must contain at least one grant");
        }
        for (MapRewardGrantSnapshot grant : grants) {
            if (!grant.runId().equals(runId)) {
                throw new IllegalArgumentException("all grants must belong to settlement runId");
            }
        }
    }
}

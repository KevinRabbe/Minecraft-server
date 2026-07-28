package io.github.kevinrabbe.minecraftserver.common.economy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Exactly-once result of irreversibly salvaging one individualized item. */
public record SalvageResult(
        UUID salvageId,
        UUID operationId,
        UUID playerId,
        UUID itemInstanceId,
        String itemDefinitionId,
        long destroyedItemVersion,
        long coinReturnMinor,
        long walletBalanceMinor,
        long walletStateVersion,
        long playerStateVersion,
        List<SalvageCommodityReturn> commodityReturns,
        Instant createdAt
) {
    public SalvageResult {
        salvageId = Objects.requireNonNull(salvageId, "salvageId");
        operationId = Objects.requireNonNull(operationId, "operationId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        itemInstanceId = Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        if (itemDefinitionId == null || itemDefinitionId.isBlank()) {
            throw new IllegalArgumentException("itemDefinitionId must not be blank");
        }
        if (destroyedItemVersion <= 0 || coinReturnMinor < 0 || walletBalanceMinor < 0
                || walletStateVersion < 0 || playerStateVersion < 0) {
            throw new IllegalArgumentException("invalid salvage state values");
        }
        commodityReturns = List.copyOf(Objects.requireNonNull(commodityReturns, "commodityReturns"));
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}

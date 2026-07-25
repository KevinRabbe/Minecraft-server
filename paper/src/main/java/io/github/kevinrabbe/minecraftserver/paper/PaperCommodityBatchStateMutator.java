package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.crafting.CraftingException;
import io.github.kevinrabbe.minecraftserver.common.economy.CommodityBatchEscrowValidator;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Deterministic exact batch removal used both to build and verify crafting player-state mutations. */
final class PaperCommodityBatchStateMutator implements CommodityBatchEscrowValidator {
    private final PaperCommodityStateMutator commodities;

    PaperCommodityBatchStateMutator(PaperCommodityStateMutator commodities) {
        this.commodities = Objects.requireNonNull(commodities, "commodities");
    }

    byte[] remove(UUID playerId, Map<String, Long> commodityQuantities, byte[] currentStatePayload) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(commodityQuantities, "commodityQuantities");
        byte[] next = currentStatePayload;
        for (Map.Entry<String, Long> entry : commodityQuantities.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                throw new CraftingException("Craft ingredient quantities must be positive");
            }
            next = commodities.remove(playerId, entry.getKey(), entry.getValue(), next);
        }
        return next;
    }

    @Override
    public void verifyRemoval(
            UUID playerId,
            Map<String, Long> commodityQuantities,
            byte[] currentStatePayload,
            byte[] nextStatePayload
    ) {
        Objects.requireNonNull(nextStatePayload, "nextStatePayload");
        byte[] expected = remove(playerId, commodityQuantities, currentStatePayload);
        if (!Arrays.equals(expected, nextStatePayload)) {
            throw new CraftingException("Craft player-state mutation does not equal the exact recipe ingredient removal");
        }
    }
}

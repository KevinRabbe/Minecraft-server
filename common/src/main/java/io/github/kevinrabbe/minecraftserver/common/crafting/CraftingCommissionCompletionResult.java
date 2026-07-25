package io.github.kevinrabbe.minecraftserver.common.crafting;

import io.github.kevinrabbe.minecraftserver.common.economy.CraftingCommissionSnapshot;
import io.github.kevinrabbe.minecraftserver.common.economy.CraftingCommissionStatus;

import java.util.Objects;

/** Atomic accepted-commission completion result. */
public record CraftingCommissionCompletionResult(
        CraftingCommissionSnapshot commission,
        CraftExecutionResult craft,
        long workerWalletBalanceMinor,
        long workerWalletStateVersion
) {
    public CraftingCommissionCompletionResult {
        commission = Objects.requireNonNull(commission, "commission");
        craft = Objects.requireNonNull(craft, "craft");
        if (commission.status() != CraftingCommissionStatus.COMPLETED) {
            throw new IllegalArgumentException("commission must be COMPLETED");
        }
        if (!commission.commissionId().equals(commission.commissionId())
                || !commission.workerPlayerId().equals(craft.crafterPlayerId())
                || !commission.requesterPlayerId().equals(craft.recipientPlayerId())
                || !commission.recipeId().equals(craft.recipeId())
                || commission.recipeVersion() != craft.recipeVersion()) {
            throw new IllegalArgumentException("commission/craft identity mismatch");
        }
        if (workerWalletBalanceMinor < 0 || workerWalletStateVersion < 0) {
            throw new IllegalArgumentException("worker wallet state must be nonnegative");
        }
    }
}

package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.List;
import java.util.Objects;

public record CraftingCommissionCancelResult(
        CraftingCommissionSnapshot commission,
        long walletBalanceMinor,
        long walletStateVersion,
        List<CraftingCommissionReturn> materialReturns
) {
    public CraftingCommissionCancelResult {
        commission = Objects.requireNonNull(commission, "commission");
        if (commission.status() != CraftingCommissionStatus.CANCELLED) {
            throw new IllegalArgumentException("commission must be CANCELLED");
        }
        if (walletBalanceMinor < 0 || walletStateVersion < 0) {
            throw new IllegalArgumentException("wallet state values must be nonnegative");
        }
        materialReturns = List.copyOf(Objects.requireNonNull(materialReturns, "materialReturns"));
        if (materialReturns.size() != commission.materialQuantities().size()) {
            throw new IllegalArgumentException("cancellation must return every escrowed material definition");
        }
    }
}

package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;

public record CraftingCommissionCreateResult(
        CraftingCommissionSnapshot commission,
        long walletBalanceMinor,
        long walletStateVersion,
        long playerStateVersion
) {
    public CraftingCommissionCreateResult {
        commission = Objects.requireNonNull(commission, "commission");
        if (commission.status() != CraftingCommissionStatus.OPEN) {
            throw new IllegalArgumentException("created commission must be OPEN");
        }
        if (walletBalanceMinor < 0 || walletStateVersion < 0 || playerStateVersion < 0) {
            throw new IllegalArgumentException("state values must be nonnegative");
        }
    }
}

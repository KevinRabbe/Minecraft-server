package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import java.util.Objects;

public record BountyContractStartResult(
        BountyContractSnapshot contract,
        long walletBalanceMinor,
        long walletStateVersion
) {
    public BountyContractStartResult {
        contract = Objects.requireNonNull(contract, "contract");
        if (walletBalanceMinor < 0 || walletStateVersion < 0) {
            throw new IllegalArgumentException("wallet values must be nonnegative");
        }
    }
}

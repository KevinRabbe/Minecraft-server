package io.github.kevinrabbe.minecraftserver.common.economy;

/**
 * Computes the pocket-Coin amount destroyed by one ordinary PvE death from the wallet balance locked for that death.
 *
 * <p>The policy shape is intentionally unconstrained so balance configuration may use percentages, tiers, caps or other
 * curves without changing wallet authority. Implementations must be deterministic for their externally supplied policy
 * version and return an amount between zero and the supplied pocket balance, inclusive.</p>
 */
@FunctionalInterface
public interface PveDeathLossPolicy {
    long lossMinor(long lockedPocketBalanceMinor);
}

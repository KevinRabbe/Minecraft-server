package io.github.kevinrabbe.minecraftserver.common.item;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Validated gameplay-facing snapshot of one authoritative individual item.
 *
 * <p>Normalized roll quality remains persistent identity-adjacent state. Intrinsic multipliers are derived from the
 * current item definition. Upgrade state is carried separately and is not folded into intrinsic roll quality.</p>
 */
public record ItemRuntimeStatSnapshot(
        UUID itemInstanceId,
        String definitionId,
        ItemLocation location,
        long stateVersion,
        Map<String, Integer> normalizedRollQualityBasisPoints,
        Map<String, Integer> intrinsicMultipliersBasisPoints,
        UpgradeState upgradeState
) {
    public ItemRuntimeStatSnapshot {
        itemInstanceId = Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        definitionId = definitionId.trim();
        location = Objects.requireNonNull(location, "location");
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion must be >= 0");
        }
        Objects.requireNonNull(normalizedRollQualityBasisPoints, "normalizedRollQualityBasisPoints");
        normalizedRollQualityBasisPoints = Map.copyOf(normalizedRollQualityBasisPoints);
        Objects.requireNonNull(intrinsicMultipliersBasisPoints, "intrinsicMultipliersBasisPoints");
        intrinsicMultipliersBasisPoints = Map.copyOf(intrinsicMultipliersBasisPoints);
        upgradeState = Objects.requireNonNull(upgradeState, "upgradeState");
    }
}

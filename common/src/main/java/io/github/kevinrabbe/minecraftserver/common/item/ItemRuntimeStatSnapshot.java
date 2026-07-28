package io.github.kevinrabbe.minecraftserver.common.item;

import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
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
        normalizedRollQualityBasisPoints = Map.copyOf(
                Objects.requireNonNull(normalizedRollQualityBasisPoints, "normalizedRollQualityBasisPoints")
        );
        intrinsicMultipliersBasisPoints = Map.copyOf(
                Objects.requireNonNull(intrinsicMultipliersBasisPoints, "intrinsicMultipliersBasisPoints")
        );
        upgradeState = Objects.requireNonNull(upgradeState, "upgradeState");

        TreeSet<String> qualityKeys = new TreeSet<>(normalizedRollQualityBasisPoints.keySet());
        TreeSet<String> multiplierKeys = new TreeSet<>(intrinsicMultipliersBasisPoints.keySet());
        if (!qualityKeys.equals(multiplierKeys)) {
            throw new IllegalArgumentException(
                    "roll quality and intrinsic multiplier properties must match; quality="
                            + qualityKeys + ", multipliers=" + multiplierKeys
            );
        }
        for (String propertyId : qualityKeys) {
            if (propertyId == null || propertyId.isBlank()) {
                throw new IllegalArgumentException("roll property id must not be blank");
            }
            Integer quality = normalizedRollQualityBasisPoints.get(propertyId);
            Integer multiplier = intrinsicMultipliersBasisPoints.get(propertyId);
            if (quality == null || multiplier == null) {
                throw new IllegalArgumentException("runtime stat values must not be null for " + propertyId);
            }
            new RollQuality(quality);
            if (multiplier < 0 || multiplier > RollRange.TECHNICAL_MAX_BASIS_POINTS) {
                throw new IllegalArgumentException(
                        "intrinsic multiplier out of technical range for " + propertyId + ": " + multiplier
                );
            }
        }
    }

    public int requireIntrinsicMultiplierBasisPoints(String propertyId) {
        if (propertyId == null || propertyId.isBlank()) {
            throw new IllegalArgumentException("propertyId must not be blank");
        }
        Integer multiplier = intrinsicMultipliersBasisPoints.get(propertyId.trim());
        if (multiplier == null) {
            throw new IllegalArgumentException(
                    "item runtime snapshot does not contain rolled property " + propertyId.trim()
            );
        }
        return multiplier;
    }

    public RollQuality requireRollQuality(String propertyId) {
        if (propertyId == null || propertyId.isBlank()) {
            throw new IllegalArgumentException("propertyId must not be blank");
        }
        Integer quality = normalizedRollQualityBasisPoints.get(propertyId.trim());
        if (quality == null) {
            throw new IllegalArgumentException(
                    "item runtime snapshot does not contain rolled property " + propertyId.trim()
            );
        }
        return new RollQuality(quality);
    }
}

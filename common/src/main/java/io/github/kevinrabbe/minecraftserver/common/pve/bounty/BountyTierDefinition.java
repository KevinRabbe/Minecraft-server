package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable versioned content definition for one tier inside a bounty family. */
public record BountyTierDefinition(
        BountyFamilyId familyId,
        int tier,
        int contentVersion,
        long contractFeeMinor,
        int requiredEligibleKills,
        String bossDefinitionId,
        List<String> materialDefinitionIds
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    /** Compatibility constructor for v1 fixtures/callers that predate explicit content versioning. */
    public BountyTierDefinition(
            BountyFamilyId familyId,
            int tier,
            long contractFeeMinor,
            int requiredEligibleKills,
            String bossDefinitionId,
            List<String> materialDefinitionIds
    ) {
        this(
                familyId,
                tier,
                1,
                contractFeeMinor,
                requiredEligibleKills,
                bossDefinitionId,
                materialDefinitionIds
        );
    }

    public BountyTierDefinition {
        familyId = Objects.requireNonNull(familyId, "familyId");
        if (tier <= 0) {
            throw new IllegalArgumentException("tier must be > 0");
        }
        if (contentVersion <= 0) {
            throw new IllegalArgumentException("contentVersion must be > 0");
        }
        if (contractFeeMinor < 0) {
            throw new IllegalArgumentException("contractFeeMinor must be >= 0");
        }
        if (requiredEligibleKills <= 0) {
            throw new IllegalArgumentException("requiredEligibleKills must be > 0");
        }
        bossDefinitionId = requireId(bossDefinitionId, "bossDefinitionId");
        Objects.requireNonNull(materialDefinitionIds, "materialDefinitionIds");
        if (materialDefinitionIds.isEmpty()) {
            throw new IllegalArgumentException("materialDefinitionIds must not be empty");
        }
        materialDefinitionIds = materialDefinitionIds.stream()
                .map(value -> requireId(value, "materialDefinitionId"))
                .distinct()
                .toList();
    }

    private static String requireId(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        String normalized = value.trim();
        if (!ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(fieldName + " has invalid format: " + normalized);
        }
        return normalized;
    }
}

package io.github.kevinrabbe.minecraftserver.common.world.resource;

import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable content definition for one authorized renewable gathering source type. */
public record ResourceSourceDefinition(
        String definitionId,
        String zoneId,
        String templateVersion,
        String commodityDefinitionId,
        long commodityQuantity,
        SkillId skillId,
        long requestedExperience,
        Duration respawnDelay
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Duration MAX_RESPAWN = Duration.ofDays(30);

    public ResourceSourceDefinition {
        definitionId = requireId(definitionId, "definitionId");
        zoneId = requireId(zoneId, "zoneId");
        if (templateVersion == null || templateVersion.isBlank()) {
            throw new IllegalArgumentException("templateVersion must not be blank");
        }
        templateVersion = templateVersion.trim();
        commodityDefinitionId = requireId(commodityDefinitionId, "commodityDefinitionId");
        if (commodityQuantity <= 0) {
            throw new IllegalArgumentException("commodityQuantity must be > 0");
        }
        if (skillId == null) {
            if (requestedExperience != 0) {
                throw new IllegalArgumentException("requestedExperience must be 0 when skillId is null");
            }
        } else if (requestedExperience <= 0) {
            throw new IllegalArgumentException("requestedExperience must be > 0 when skillId is present");
        }
        respawnDelay = Objects.requireNonNull(respawnDelay, "respawnDelay");
        if (respawnDelay.isNegative() || respawnDelay.isZero() || respawnDelay.compareTo(MAX_RESPAWN) > 0) {
            throw new IllegalArgumentException("respawnDelay must be > 0 and <= 30 days");
        }
    }

    private static String requireId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (!ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " has invalid format: " + normalized);
        }
        return normalized;
    }
}

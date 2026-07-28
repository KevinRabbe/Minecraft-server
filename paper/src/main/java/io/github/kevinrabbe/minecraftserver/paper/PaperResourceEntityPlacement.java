package io.github.kevinrabbe.minecraftserver.paper;

import org.bukkit.entity.EntityType;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/** One authored spawn point bound to a renewable resource-source identity. */
record PaperResourceEntityPlacement(
        String sourceKey,
        String definitionId,
        String zoneId,
        String templateVersion,
        String worldName,
        double x,
        double y,
        double z,
        EntityType entityType,
        Duration pendingLease,
        Duration activeLifetime
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");

    PaperResourceEntityPlacement {
        sourceKey = requireId(sourceKey, "sourceKey");
        definitionId = requireId(definitionId, "definitionId");
        zoneId = requireId(zoneId, "zoneId");
        if (templateVersion == null || templateVersion.isBlank()) {
            throw new IllegalArgumentException("templateVersion must not be blank");
        }
        templateVersion = templateVersion.trim();
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("worldName must not be blank");
        }
        worldName = worldName.trim();
        entityType = Objects.requireNonNull(entityType, "entityType");
        if (entityType == EntityType.PLAYER || entityType == EntityType.UNKNOWN) {
            throw new IllegalArgumentException("entityType is not spawnable for ordinary PvE: " + entityType);
        }
        pendingLease = requirePositive(pendingLease, "pendingLease");
        activeLifetime = requirePositive(activeLifetime, "activeLifetime");
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

    private static Duration requirePositive(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}

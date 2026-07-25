package io.github.kevinrabbe.minecraftserver.paper;

import org.bukkit.entity.LivingEntity;

import java.util.Objects;

/** Authored disposable boss anchor for one configured bounty boss definition. */
record PaperBountyBossPlacement(
        String bossDefinitionId,
        String zoneId,
        String templateVersion,
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        Class<? extends LivingEntity> entityClass,
        String displayName
) {
    PaperBountyBossPlacement {
        bossDefinitionId = requireText(bossDefinitionId, "bossDefinitionId");
        zoneId = requireText(zoneId, "zoneId");
        templateVersion = requireText(templateVersion, "templateVersion");
        worldName = requireText(worldName, "worldName");
        entityClass = Objects.requireNonNull(entityClass, "entityClass");
        displayName = requireText(displayName, "displayName");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("boss placement coordinates must be finite");
        }
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("boss placement rotation must be finite");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}

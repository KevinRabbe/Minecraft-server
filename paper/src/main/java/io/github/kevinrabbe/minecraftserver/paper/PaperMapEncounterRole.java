package io.github.kevinrabbe.minecraftserver.paper;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;

import java.util.Objects;

/** One player-facing enemy role inside a versioned Map enemy package. */
record PaperMapEncounterRole(
        String roleId,
        String displayName,
        EntityType entityType,
        int weight
) {
    PaperMapEncounterRole {
        roleId = requireId(roleId, "roleId");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        displayName = displayName.trim();
        entityType = Objects.requireNonNull(entityType, "entityType");
        Class<?> entityClass = entityType.getEntityClass();
        if (!entityType.isAlive() || !entityType.isSpawnable()
                || entityClass == null || !Monster.class.isAssignableFrom(entityClass)) {
            throw new IllegalArgumentException("entityType must materialize a spawnable Monster: " + entityType);
        }
        if (weight < 1 || weight > 1_000) {
            throw new IllegalArgumentException("weight must be between 1 and 1000");
        }
    }

    private static String requireId(String value, String field) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9._-]{0,95}")) {
            throw new IllegalArgumentException(field + " must be a stable lowercase ID");
        }
        return value;
    }
}

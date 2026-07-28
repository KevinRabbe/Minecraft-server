package io.github.kevinrabbe.minecraftserver.common.pve.map;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable challenge snapshot for one Map run. */
public record MapRunDefinition(
        MapDifficulty difficulty,
        String environmentId,
        String enemyFamilyId,
        String objectiveId,
        List<String> modifierIds,
        long generationSeed,
        int generationVersion,
        int balanceVersion,
        String worldEraId
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public MapRunDefinition {
        difficulty = Objects.requireNonNull(difficulty, "difficulty");
        environmentId = requireId(environmentId, "environmentId");
        enemyFamilyId = requireId(enemyFamilyId, "enemyFamilyId");
        objectiveId = requireId(objectiveId, "objectiveId");
        worldEraId = requireId(worldEraId, "worldEraId");
        modifierIds = normalizeModifierIds(modifierIds);
        if (generationVersion < 0) {
            throw new IllegalArgumentException("generationVersion must be >= 0");
        }
        if (balanceVersion < 0) {
            throw new IllegalArgumentException("balanceVersion must be >= 0");
        }
    }

    private static List<String> normalizeModifierIds(List<String> values) {
        Objects.requireNonNull(values, "modifierIds");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = requireId(value, "modifierId");
            if (!unique.add(normalized)) {
                throw new IllegalArgumentException("duplicate modifierId: " + normalized);
            }
        }
        return List.copyOf(unique);
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

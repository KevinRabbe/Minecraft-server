package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.pve.map.MapDifficulty;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunDefinition;
import org.bukkit.entity.EntityType;

/** One version-controlled Paper materialization/reward policy for a compatible Map run definition. */
record PaperMapEncounterDefinition(
        String encounterId,
        String environmentId,
        String enemyFamilyId,
        String objectiveId,
        int generationVersion,
        int balanceVersion,
        EntityType entityType,
        int baseKills,
        int difficultyPerExtraKill,
        int maxKills,
        double healthPerDifficulty,
        double maxHealthMultiplier,
        double damagePerDifficulty,
        double maxDamageMultiplier,
        double spawnRadius,
        String rewardMapDefinitionId,
        int successorDifficultyDelta,
        int maxSuccessorDifficulty
) {
    PaperMapEncounterDefinition {
        encounterId = requireId(encounterId, "encounterId");
        environmentId = requireId(environmentId, "environmentId");
        enemyFamilyId = requireId(enemyFamilyId, "enemyFamilyId");
        objectiveId = requireId(objectiveId, "objectiveId");
        if (generationVersion < 1) {
            throw new IllegalArgumentException("generationVersion must be >= 1");
        }
        if (balanceVersion < 1) {
            throw new IllegalArgumentException("balanceVersion must be >= 1");
        }
        if (entityType == null || !entityType.isAlive()) {
            throw new IllegalArgumentException("entityType must be a living entity type");
        }
        if (baseKills < 1 || maxKills < baseKills || maxKills > 64) {
            throw new IllegalArgumentException("kill bounds must satisfy 1 <= baseKills <= maxKills <= 64");
        }
        if (difficultyPerExtraKill < 1) {
            throw new IllegalArgumentException("difficultyPerExtraKill must be >= 1");
        }
        requireFiniteNonNegative(healthPerDifficulty, "healthPerDifficulty");
        requireFiniteNonNegative(damagePerDifficulty, "damagePerDifficulty");
        requireFiniteAtLeastOne(maxHealthMultiplier, "maxHealthMultiplier");
        requireFiniteAtLeastOne(maxDamageMultiplier, "maxDamageMultiplier");
        if (!Double.isFinite(spawnRadius) || spawnRadius < 2.0 || spawnRadius > 32.0) {
            throw new IllegalArgumentException("spawnRadius must be finite and between 2 and 32");
        }
        rewardMapDefinitionId = requireId(rewardMapDefinitionId, "rewardMapDefinitionId");
        if (successorDifficultyDelta < 0 || successorDifficultyDelta > 1_000) {
            throw new IllegalArgumentException("successorDifficultyDelta must be between 0 and 1000");
        }
        if (maxSuccessorDifficulty < MapDifficulty.MIN_VALUE
                || maxSuccessorDifficulty > MapDifficulty.TECHNICAL_MAX_VALUE) {
            throw new IllegalArgumentException("maxSuccessorDifficulty is outside MapDifficulty bounds");
        }
    }

    boolean matches(MapRunDefinition definition) {
        return environmentId.equals(definition.environmentId())
                && enemyFamilyId.equals(definition.enemyFamilyId())
                && objectiveId.equals(definition.objectiveId())
                && generationVersion == definition.generationVersion()
                && balanceVersion == definition.balanceVersion();
    }

    int requiredKills(int difficulty) {
        long extra = (Math.max(1, difficulty) - 1L) / difficultyPerExtraKill;
        return (int) Math.min(maxKills, baseKills + extra);
    }

    double healthMultiplier(int difficulty) {
        return boundedMultiplier(difficulty, healthPerDifficulty, maxHealthMultiplier);
    }

    double damageMultiplier(int difficulty) {
        return boundedMultiplier(difficulty, damagePerDifficulty, maxDamageMultiplier);
    }

    int successorDifficulty(int clearedDifficulty) {
        long next = (long) clearedDifficulty + successorDifficultyDelta;
        return (int) Math.min(maxSuccessorDifficulty, Math.max(MapDifficulty.MIN_VALUE, next));
    }

    private static double boundedMultiplier(int difficulty, double perDifficulty, double maximum) {
        double scaled = 1.0 + perDifficulty * Math.max(0L, (long) difficulty - 1L);
        return Math.min(maximum, scaled);
    }

    private static String requireId(String value, String field) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9._-]{0,95}")) {
            throw new IllegalArgumentException(field + " must be a stable lowercase ID");
        }
        return value;
    }

    private static void requireFiniteNonNegative(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(field + " must be finite and >= 0");
        }
    }

    private static void requireFiniteAtLeastOne(double value, String field) {
        if (!Double.isFinite(value) || value < 1.0) {
            throw new IllegalArgumentException(field + " must be finite and >= 1");
        }
    }
}

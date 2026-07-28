package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persistent evidence for the disposable Minecraft entity representing one ACTIVE bounty summon. */
public record BountyBossMaterializationSnapshot(
        UUID summonId,
        UUID entityUuid,
        String backendId,
        String bossDefinitionId,
        String worldName,
        double spawnX,
        double spawnY,
        double spawnZ,
        Instant createdAt
) {
    public BountyBossMaterializationSnapshot {
        summonId = Objects.requireNonNull(summonId, "summonId");
        entityUuid = Objects.requireNonNull(entityUuid, "entityUuid");
        backendId = requireText(backendId, "backendId");
        bossDefinitionId = requireText(bossDefinitionId, "bossDefinitionId");
        worldName = requireText(worldName, "worldName");
        if (!Double.isFinite(spawnX) || !Double.isFinite(spawnY) || !Double.isFinite(spawnZ)) {
            throw new IllegalArgumentException("spawn coordinates must be finite");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}

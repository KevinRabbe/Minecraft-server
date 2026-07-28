package io.github.kevinrabbe.minecraftserver.paper;

import org.bukkit.Material;

import java.util.Objects;
import java.util.regex.Pattern;

/** One authored physical block location bound to a stable renewable source key. */
record PaperResourceSourcePlacement(
        String sourceKey,
        String definitionId,
        String zoneId,
        String templateVersion,
        String worldName,
        int blockX,
        int blockY,
        int blockZ,
        Material expectedBlock
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");

    PaperResourceSourcePlacement {
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
        expectedBlock = Objects.requireNonNull(expectedBlock, "expectedBlock");
        if (!expectedBlock.isBlock()) {
            throw new IllegalArgumentException("expectedBlock must be a block material");
        }
    }

    BlockKey blockKey() {
        return new BlockKey(worldName, blockX, blockY, blockZ);
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

    record BlockKey(String worldName, int x, int y, int z) { }
}

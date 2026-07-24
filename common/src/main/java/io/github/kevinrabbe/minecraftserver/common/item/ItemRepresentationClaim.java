package io.github.kevinrabbe.minecraftserver.common.item;

import java.util.Objects;
import java.util.UUID;

/** A compact claim extracted from one live Minecraft representation at a trust boundary. */
public record ItemRepresentationClaim(
        String source,
        String definitionId,
        String minecraftMaterial,
        int amount,
        UUID itemInstanceId,
        Long authorityVersion
) {
    public ItemRepresentationClaim {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        source = source.trim();
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        definitionId = definitionId.trim();
        if (minecraftMaterial == null || minecraftMaterial.isBlank()) {
            throw new IllegalArgumentException("minecraftMaterial must not be blank");
        }
        minecraftMaterial = minecraftMaterial.trim();
        if (amount < 1) {
            throw new IllegalArgumentException("amount must be >= 1");
        }
        if (authorityVersion != null && authorityVersion < 0) {
            throw new IllegalArgumentException("authorityVersion must be >= 0");
        }
    }

    public boolean individualClaim() {
        return itemInstanceId != null || authorityVersion != null;
    }
}

package io.github.kevinrabbe.minecraftserver.common.pve.map;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable challenge profile attached to one individualized persistent Map item. */
public record MapItemProfile(
        UUID itemInstanceId,
        String definitionId,
        MapRunDefinition runDefinition,
        Instant createdAt
) {
    private static final Pattern DEFINITION_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public MapItemProfile {
        itemInstanceId = Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        definitionId = definitionId.trim();
        if (!DEFINITION_ID.matcher(definitionId).matches()) {
            throw new IllegalArgumentException("definitionId has invalid format: " + definitionId);
        }
        runDefinition = Objects.requireNonNull(runDefinition, "runDefinition");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}

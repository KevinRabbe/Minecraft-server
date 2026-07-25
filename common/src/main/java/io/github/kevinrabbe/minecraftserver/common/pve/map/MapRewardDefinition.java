package io.github.kevinrabbe.minecraftserver.common.pve.map;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** One server-resolved reward grant before it is persisted as an entitlement. */
public record MapRewardDefinition(
        UUID playerId,
        MapRewardKind kind,
        String definitionId,
        long quantity,
        MapRunDefinition successorMapDefinition
) {
    private static final Pattern DEFINITION_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public MapRewardDefinition {
        playerId = Objects.requireNonNull(playerId, "playerId");
        kind = Objects.requireNonNull(kind, "kind");
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        definitionId = definitionId.trim();
        if (!DEFINITION_ID.matcher(definitionId).matches()) {
            throw new IllegalArgumentException("definitionId has invalid format: " + definitionId);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        if (kind == MapRewardKind.MAP) {
            if (quantity != 1 || successorMapDefinition == null) {
                throw new IllegalArgumentException("MAP reward requires quantity=1 and successorMapDefinition");
            }
        } else if (successorMapDefinition != null) {
            throw new IllegalArgumentException("Only MAP rewards may carry successorMapDefinition");
        }
        if (kind == MapRewardKind.UNIQUE_ITEM && quantity != 1) {
            throw new IllegalArgumentException("UNIQUE_ITEM reward requires quantity=1");
        }
    }

    public static MapRewardDefinition commodity(UUID playerId, String definitionId, long quantity) {
        return new MapRewardDefinition(playerId, MapRewardKind.COMMODITY, definitionId, quantity, null);
    }

    public static MapRewardDefinition uniqueItem(UUID playerId, String definitionId) {
        return new MapRewardDefinition(playerId, MapRewardKind.UNIQUE_ITEM, definitionId, 1, null);
    }

    public static MapRewardDefinition map(UUID playerId, String definitionId, MapRunDefinition definition) {
        return new MapRewardDefinition(playerId, MapRewardKind.MAP, definitionId, 1, definition);
    }
}

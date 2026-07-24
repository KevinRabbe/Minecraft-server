package io.github.kevinrabbe.minecraftserver.common.item;

import java.util.Objects;
import java.util.UUID;

/** One authoritative custody/location for an individual item. */
public record ItemLocation(ItemLocationKind kind, UUID locationId) {
    public ItemLocation {
        kind = Objects.requireNonNull(kind, "kind");
        if (kind == ItemLocationKind.PLAYER_INVENTORY && locationId == null) {
            throw new IllegalArgumentException("PLAYER_INVENTORY requires a player_id locationId");
        }
        if (kind != ItemLocationKind.PLAYER_INVENTORY && locationId != null) {
            throw new IllegalArgumentException(kind + " must not carry a locationId");
        }
    }

    public static ItemLocation playerInventory(UUID playerId) {
        return new ItemLocation(ItemLocationKind.PLAYER_INVENTORY, Objects.requireNonNull(playerId, "playerId"));
    }

    public static ItemLocation quarantine() {
        return new ItemLocation(ItemLocationKind.QUARANTINE, null);
    }

    public static ItemLocation destroyed() {
        return new ItemLocation(ItemLocationKind.DESTROYED, null);
    }
}

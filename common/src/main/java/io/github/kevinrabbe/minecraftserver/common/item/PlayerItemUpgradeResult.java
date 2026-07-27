package io.github.kevinrabbe.minecraftserver.common.item;

import java.util.Objects;
import java.util.UUID;

/** Result of one atomic player-state + unique-item upgrade commit. */
public record PlayerItemUpgradeResult(
        UUID playerId,
        long playerStateVersion,
        ItemUpgradeResult itemUpgrade
) {
    public PlayerItemUpgradeResult {
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (playerStateVersion < 0) {
            throw new IllegalArgumentException("playerStateVersion must be >= 0");
        }
        itemUpgrade = Objects.requireNonNull(itemUpgrade, "itemUpgrade");
        if (!ItemLocation.playerInventory(playerId).equals(itemUpgrade.location())) {
            throw new IllegalArgumentException("upgraded item must remain in the upgraded player's inventory custody");
        }
    }
}

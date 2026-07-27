package io.github.kevinrabbe.minecraftserver.common.item;

import java.util.Objects;
import java.util.UUID;

/** Exactly-once result of advancing one authoritative unique-item upgrade level. */
public record ItemUpgradeResult(
        UUID itemInstanceId,
        String definitionId,
        int fromUpgradeLevel,
        int toUpgradeLevel,
        long fromStateVersion,
        long toStateVersion,
        ItemLocation location,
        String reason
) {
    public ItemUpgradeResult {
        itemInstanceId = Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        definitionId = definitionId.trim();
        new UpgradeState(fromUpgradeLevel);
        new UpgradeState(toUpgradeLevel);
        if (toUpgradeLevel != fromUpgradeLevel + 1) {
            throw new IllegalArgumentException("upgrade must advance exactly one level");
        }
        if (fromStateVersion < 0 || toStateVersion != fromStateVersion + 1) {
            throw new IllegalArgumentException("state version must advance exactly once");
        }
        location = Objects.requireNonNull(location, "location");
        if (location.kind() != ItemLocationKind.PLAYER_INVENTORY) {
            throw new IllegalArgumentException("item upgrade result must remain in player inventory custody");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        reason = reason.trim();
    }
}

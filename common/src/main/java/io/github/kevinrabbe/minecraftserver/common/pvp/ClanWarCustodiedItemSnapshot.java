package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.util.Objects;
import java.util.UUID;

/** Persistent item identity plus the data needed to derive a disposable 1.8.9 combat representation. */
public record ClanWarCustodiedItemSnapshot(
        UUID warId,
        UUID playerId,
        UUID itemInstanceId,
        String definitionId,
        long itemStateVersion,
        String rollStateJson,
        int upgradeLevel
) {
    public ClanWarCustodiedItemSnapshot {
        warId = Objects.requireNonNull(warId, "warId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        itemInstanceId = Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        rollStateJson = Objects.requireNonNull(rollStateJson, "rollStateJson");
        if (itemStateVersion < 0 || upgradeLevel < 0) {
            throw new IllegalArgumentException("item state/upgrade values must be >= 0");
        }
    }
}

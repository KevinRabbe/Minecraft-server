package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable declaration that one live roster player has finalized their current WAR_CUSTODY selection. */
public record ClanWarLoadoutConfirmation(
        UUID warId,
        UUID playerId,
        Instant confirmedAt
) {
    public ClanWarLoadoutConfirmation {
        warId = Objects.requireNonNull(warId, "warId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt");
    }
}

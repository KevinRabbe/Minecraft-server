package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Exactly-once completed war result, including rating movement and durable item-return deliveries. */
public record ClanWarCompletionResult(
        ClanWarSnapshot war,
        UUID losingClanId,
        ClanWarRatingSnapshot challengerBefore,
        ClanWarRatingSnapshot challengerAfter,
        ClanWarRatingSnapshot defenderBefore,
        ClanWarRatingSnapshot defenderAfter,
        List<UUID> returnDeliveryIds
) {
    public ClanWarCompletionResult {
        war = Objects.requireNonNull(war, "war");
        losingClanId = Objects.requireNonNull(losingClanId, "losingClanId");
        challengerBefore = Objects.requireNonNull(challengerBefore, "challengerBefore");
        challengerAfter = Objects.requireNonNull(challengerAfter, "challengerAfter");
        defenderBefore = Objects.requireNonNull(defenderBefore, "defenderBefore");
        defenderAfter = Objects.requireNonNull(defenderAfter, "defenderAfter");
        returnDeliveryIds = List.copyOf(Objects.requireNonNull(returnDeliveryIds, "returnDeliveryIds"));
    }
}

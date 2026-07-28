package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Cancelled/failed war resolution with durable recovery deliveries and no rating movement. */
public record ClanWarTerminalResult(
        ClanWarSnapshot war,
        List<UUID> returnDeliveryIds
) {
    public ClanWarTerminalResult {
        war = Objects.requireNonNull(war, "war");
        returnDeliveryIds = List.copyOf(Objects.requireNonNull(returnDeliveryIds, "returnDeliveryIds"));
        if (war.status() != ClanWarStatus.CANCELLED && war.status() != ClanWarStatus.FAILED) {
            throw new IllegalArgumentException("terminal recovery result requires CANCELLED or FAILED war");
        }
    }
}

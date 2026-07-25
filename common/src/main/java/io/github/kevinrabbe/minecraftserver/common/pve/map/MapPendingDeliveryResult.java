package io.github.kevinrabbe.minecraftserver.common.pve.map;

import java.util.Objects;
import java.util.UUID;

/** Exactly-once pending delivery for one newly created individualized Map reward. */
public record MapPendingDeliveryResult(
        UUID deliveryId,
        UUID recipientPlayerId,
        MapItemProfile mapProfile,
        long itemStateVersion
) {
    public MapPendingDeliveryResult {
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        recipientPlayerId = Objects.requireNonNull(recipientPlayerId, "recipientPlayerId");
        mapProfile = Objects.requireNonNull(mapProfile, "mapProfile");
        if (itemStateVersion < 0) {
            throw new IllegalArgumentException("itemStateVersion must be >= 0");
        }
    }
}

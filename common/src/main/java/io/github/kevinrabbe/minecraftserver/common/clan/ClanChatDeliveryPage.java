package io.github.kevinrabbe.minecraftserver.common.clan;

import java.util.List;
import java.util.Objects;

/** Bounded backend delivery page. scannedThroughSequence advances even when a message has no local recipients. */
public record ClanChatDeliveryPage(
        long scannedThroughSequence,
        List<ClanChatDelivery> deliveries
) {
    public ClanChatDeliveryPage {
        if (scannedThroughSequence < 0) {
            throw new IllegalArgumentException("scannedThroughSequence must be >= 0");
        }
        Objects.requireNonNull(deliveries, "deliveries");
        deliveries = List.copyOf(deliveries);
    }
}

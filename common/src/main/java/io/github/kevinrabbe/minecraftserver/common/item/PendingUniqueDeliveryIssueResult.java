package io.github.kevinrabbe.minecraftserver.common.item;

import java.util.Objects;
import java.util.UUID;

public record PendingUniqueDeliveryIssueResult(
        UUID deliveryId,
        UUID recipientPlayerId,
        UUID itemInstanceId,
        String definitionId,
        long itemStateVersion
) {
    public PendingUniqueDeliveryIssueResult {
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        recipientPlayerId = Objects.requireNonNull(recipientPlayerId, "recipientPlayerId");
        itemInstanceId = Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        definitionId = definitionId.trim();
        if (itemStateVersion < 0) {
            throw new IllegalArgumentException("itemStateVersion must be >= 0");
        }
    }
}

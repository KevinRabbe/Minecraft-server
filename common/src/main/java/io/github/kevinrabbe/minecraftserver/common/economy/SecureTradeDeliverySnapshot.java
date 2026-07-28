package io.github.kevinrabbe.minecraftserver.common.economy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable evidence for one item/commodity delivery created by trade settlement or cancellation. */
public record SecureTradeDeliverySnapshot(
        UUID tradeId,
        UUID deliveryId,
        SecureTradeDeliveryKind kind,
        UUID sourceOwnerPlayerId,
        UUID recipientPlayerId,
        UUID itemInstanceId,
        String commodityDefinitionId,
        Long quantity,
        Instant createdAt
) {
    public SecureTradeDeliverySnapshot {
        tradeId = Objects.requireNonNull(tradeId, "tradeId");
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        kind = Objects.requireNonNull(kind, "kind");
        sourceOwnerPlayerId = Objects.requireNonNull(sourceOwnerPlayerId, "sourceOwnerPlayerId");
        recipientPlayerId = Objects.requireNonNull(recipientPlayerId, "recipientPlayerId");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (kind == SecureTradeDeliveryKind.UNIQUE_ITEM) {
            itemInstanceId = Objects.requireNonNull(itemInstanceId, "itemInstanceId");
            if (commodityDefinitionId != null || quantity != null) {
                throw new IllegalArgumentException("UNIQUE_ITEM trade delivery cannot carry commodity fields");
            }
        } else {
            if (itemInstanceId != null || commodityDefinitionId == null || commodityDefinitionId.isBlank()
                    || quantity == null || quantity <= 0) {
                throw new IllegalArgumentException("COMMODITY trade delivery shape is invalid");
            }
        }
    }
}

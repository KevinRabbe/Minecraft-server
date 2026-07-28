package io.github.kevinrabbe.minecraftserver.common.item;

import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * Safe high-level materialization path for one pending unique item.
 *
 * <p>The low-level repository historically accepts a prebuilt serialized payload. This service removes authority-version
 * guessing from adapters: it loads the item, projects the exact post-claim version/location, lets the adapter
 * deterministically add that representation to the current payload, then performs the existing atomic player-state +
 * item-custody claim. A retry of the same committed operation reconstructs the same projected representation and is
 * forwarded to the repository's existing idempotency evidence.</p>
 */
public final class PendingUniqueDeliveryClaimService {
    private final PendingUniqueDeliveryRepository deliveries;
    private final UniqueItemAuthorityRepository items;
    private final PendingUniqueDeliveryStateMutator stateMutator;

    public PendingUniqueDeliveryClaimService(
            PendingUniqueDeliveryRepository deliveries,
            UniqueItemAuthorityRepository items,
            PendingUniqueDeliveryStateMutator stateMutator
    ) {
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
        this.items = Objects.requireNonNull(items, "items");
        this.stateMutator = Objects.requireNonNull(stateMutator, "stateMutator");
    }

    public PendingUniqueDeliveryMaterializationResult claim(
            UUID operationId,
            UUID deliveryId,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            String logicalZoneId,
            String entryPoint,
            byte[] currentStatePayload,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(currentStatePayload, "currentStatePayload");

        PendingUniqueDelivery delivery = deliveries.load(deliveryId);
        UniqueItemInstance currentItem = items.load(delivery.itemInstanceId());
        UniqueItemInstance projectedItem = projectedItem(operationId, delivery, currentItem);
        byte[] nextPayload = stateMutator.add(
                delivery.recipientPlayerId(),
                projectedItem,
                currentStatePayload
        );

        PendingUniqueDeliveryClaimResult claimed = deliveries.claimToPlayerState(
                operationId,
                deliveryId,
                sessionId,
                backendId,
                expectedPlayerStateVersion,
                logicalZoneId,
                entryPoint,
                nextPayload,
                reason
        );
        return new PendingUniqueDeliveryMaterializationResult(claimed, nextPayload);
    }

    private static UniqueItemInstance projectedItem(
            UUID operationId,
            PendingUniqueDelivery delivery,
            UniqueItemInstance currentItem
    ) {
        long projectedVersion;
        if (delivery.status() == PendingDeliveryStatus.PENDING) {
            ItemLocation expectedLocation = ItemLocation.pendingDelivery(delivery.deliveryId());
            if (!currentItem.location().equals(expectedLocation)) {
                throw new PendingUniqueDeliveryException(
                        "Pending delivery no longer owns authoritative item custody: " + delivery.deliveryId()
                );
            }
            try {
                projectedVersion = Math.addExact(currentItem.stateVersion(), 1L);
            } catch (ArithmeticException exception) {
                throw new PendingUniqueDeliveryException(
                        "Pending delivery item state_version overflow: " + currentItem.itemInstanceId(),
                        exception
                );
            }
        } else if (delivery.status() == PendingDeliveryStatus.CLAIMED
                && operationId.equals(delivery.claimOperationId())) {
            ItemLocation claimedLocation = ItemLocation.playerInventory(delivery.recipientPlayerId());
            if (!currentItem.location().equals(claimedLocation)) {
                throw new PendingUniqueDeliveryException(
                        "Claimed delivery item is not in recipient inventory authority: " + delivery.deliveryId()
                );
            }
            projectedVersion = currentItem.stateVersion();
        } else {
            throw new PendingUniqueDeliveryException("Delivery is already claimed: " + delivery.deliveryId());
        }

        // The serialized representation must depend only on stable authority fields so retries reproduce identical bytes.
        return new UniqueItemInstance(
                currentItem.itemInstanceId(),
                currentItem.definitionId(),
                ItemLocation.playerInventory(delivery.recipientPlayerId()),
                projectedVersion,
                currentItem.originalOwnerPlayerId(),
                currentItem.createdByOperationId(),
                currentItem.createdReason(),
                currentItem.createdAt(),
                currentItem.createdAt()
        );
    }
}

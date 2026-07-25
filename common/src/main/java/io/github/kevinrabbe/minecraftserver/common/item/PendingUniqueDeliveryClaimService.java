package io.github.kevinrabbe.minecraftserver.common.item;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Safe high-level materialization path for one pending unique item.
 *
 * <p>The low-level repository historically accepts a prebuilt serialized payload. This service removes authority-version
 * guessing from adapters: it loads the pending item, projects the exact post-claim version/location, lets the adapter
 * deterministically add that representation to the current payload, then performs the existing atomic player-state +
 * item-custody claim.</p>
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
        if (delivery.status() != PendingDeliveryStatus.PENDING) {
            throw new PendingUniqueDeliveryException("Delivery is already claimed: " + deliveryId);
        }

        UniqueItemInstance currentItem = items.load(delivery.itemInstanceId());
        ItemLocation expectedLocation = ItemLocation.pendingDelivery(deliveryId);
        if (!currentItem.location().equals(expectedLocation)) {
            throw new PendingUniqueDeliveryException(
                    "Pending delivery no longer owns authoritative item custody: " + deliveryId
            );
        }

        long projectedVersion;
        try {
            projectedVersion = Math.addExact(currentItem.stateVersion(), 1L);
        } catch (ArithmeticException exception) {
            throw new PendingUniqueDeliveryException(
                    "Pending delivery item state_version overflow: " + currentItem.itemInstanceId(),
                    exception
            );
        }

        UniqueItemInstance projectedItem = new UniqueItemInstance(
                currentItem.itemInstanceId(),
                currentItem.definitionId(),
                ItemLocation.playerInventory(delivery.recipientPlayerId()),
                projectedVersion,
                currentItem.originalOwnerPlayerId(),
                currentItem.createdByOperationId(),
                currentItem.createdReason(),
                currentItem.createdAt(),
                Instant.now()
        );
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
        if (!claimed.recipientPlayerId().equals(delivery.recipientPlayerId())
                || !claimed.itemInstanceId().equals(projectedItem.itemInstanceId())
                || !claimed.definitionId().equals(projectedItem.definitionId())
                || claimed.itemStateVersion() != projectedVersion) {
            throw new IllegalStateException("Pending unique delivery claim returned inconsistent authority state");
        }
        return new PendingUniqueDeliveryMaterializationResult(claimed, nextPayload);
    }
}

package io.github.kevinrabbe.minecraftserver.common.pve.map;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable entitlement created by successful Map reward settlement. */
public record MapRewardGrantSnapshot(
        UUID grantId,
        UUID runId,
        UUID playerId,
        int ordinal,
        MapRewardKind kind,
        String definitionId,
        long quantity,
        MapRunDefinition successorMapDefinition,
        MapRewardGrantStatus status,
        UUID fulfillmentOperationId,
        Instant createdAt,
        Instant fulfilledAt
) {
    public MapRewardGrantSnapshot {
        grantId = Objects.requireNonNull(grantId, "grantId");
        runId = Objects.requireNonNull(runId, "runId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be >= 0");
        }
        kind = Objects.requireNonNull(kind, "kind");
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        if ((kind == MapRewardKind.MAP) != (successorMapDefinition != null)) {
            throw new IllegalArgumentException("MAP grant/profile shape is invalid");
        }
        status = Objects.requireNonNull(status, "status");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (status == MapRewardGrantStatus.PENDING && (fulfillmentOperationId != null || fulfilledAt != null)) {
            throw new IllegalArgumentException("PENDING grant cannot carry fulfillment evidence");
        }
        if (status == MapRewardGrantStatus.FULFILLED && (fulfillmentOperationId == null || fulfilledAt == null)) {
            throw new IllegalArgumentException("FULFILLED grant requires fulfillment evidence");
        }
    }
}

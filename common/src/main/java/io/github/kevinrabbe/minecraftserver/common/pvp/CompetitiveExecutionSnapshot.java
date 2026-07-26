package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable assignment/lease owned by common authority for one isolated competitive activity. */
public record CompetitiveExecutionSnapshot(
        UUID executionId,
        UUID assignmentOperationId,
        CompetitiveActivityKind activityKind,
        UUID activityId,
        String backendId,
        CompetitiveExecutionStatus status,
        Instant leaseExpiresAt,
        long stateVersion,
        CompetitiveExecutionCloseReason closeReason,
        UUID settlementOperationId,
        Instant assignedAt,
        Instant activatedAt,
        Instant closedAt
) {
    public CompetitiveExecutionSnapshot {
        executionId = Objects.requireNonNull(executionId, "executionId");
        assignmentOperationId = Objects.requireNonNull(assignmentOperationId, "assignmentOperationId");
        activityKind = Objects.requireNonNull(activityKind, "activityKind");
        activityId = Objects.requireNonNull(activityId, "activityId");
        status = Objects.requireNonNull(status, "status");
        leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        assignedAt = Objects.requireNonNull(assignedAt, "assignedAt");
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("backendId must not be blank");
        }
        backendId = backendId.trim();
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion must be >= 0");
        }
        switch (status) {
            case ASSIGNED -> {
                if (activatedAt != null || closeReason != null || settlementOperationId != null || closedAt != null) {
                    throw new IllegalArgumentException("ASSIGNED execution has terminal/activation fields");
                }
            }
            case ACTIVE -> {
                if (activatedAt == null || closeReason != null || settlementOperationId != null || closedAt != null) {
                    throw new IllegalArgumentException("ACTIVE execution shape is invalid");
                }
            }
            case CLOSED -> {
                if (closeReason == null || settlementOperationId == null || closedAt == null) {
                    throw new IllegalArgumentException("CLOSED execution requires close evidence");
                }
            }
        }
    }
}

package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable bounded outcome report submitted by an isolated competitive runtime. */
public record CompetitiveResultReportSnapshot(
        UUID reportId,
        UUID reportOperationId,
        UUID executionId,
        String backendId,
        CompetitiveReportKind reportKind,
        UUID winnerId,
        CompetitiveReportStatus status,
        UUID settlementOperationId,
        Instant submittedAt,
        Instant processedAt
) {
    public CompetitiveResultReportSnapshot {
        reportId = Objects.requireNonNull(reportId, "reportId");
        reportOperationId = Objects.requireNonNull(reportOperationId, "reportOperationId");
        executionId = Objects.requireNonNull(executionId, "executionId");
        reportKind = Objects.requireNonNull(reportKind, "reportKind");
        status = Objects.requireNonNull(status, "status");
        submittedAt = Objects.requireNonNull(submittedAt, "submittedAt");
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("backendId must not be blank");
        }
        backendId = backendId.trim();
        if ((reportKind == CompetitiveReportKind.WINNER) != (winnerId != null)) {
            throw new IllegalArgumentException("WINNER report requires winnerId and FAILURE report forbids it");
        }
        switch (status) {
            case PENDING -> {
                if (settlementOperationId != null || processedAt != null) {
                    throw new IllegalArgumentException("PENDING report cannot contain settlement evidence");
                }
            }
            case APPLIED -> {
                if (settlementOperationId == null || processedAt == null) {
                    throw new IllegalArgumentException("APPLIED report requires settlement evidence");
                }
            }
        }
    }
}

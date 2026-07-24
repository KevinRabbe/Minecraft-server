package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BountySummonSnapshot(
        UUID summonId,
        UUID contractId,
        BountySummonStatus status,
        String ownerBackendId,
        Instant leaseExpiresAt,
        long stateVersion,
        Instant createdAt,
        Instant activatedAt,
        Instant resolvedAt
) {
    public BountySummonSnapshot {
        summonId = Objects.requireNonNull(summonId, "summonId");
        contractId = Objects.requireNonNull(contractId, "contractId");
        status = Objects.requireNonNull(status, "status");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion must be >= 0");
        }
        if (status == BountySummonStatus.READY
                && (ownerBackendId != null || leaseExpiresAt != null || activatedAt != null || resolvedAt != null)) {
            throw new IllegalArgumentException("READY summon must not carry runtime/resolution metadata");
        }
        if (status == BountySummonStatus.ACTIVE
                && (ownerBackendId == null || ownerBackendId.isBlank() || leaseExpiresAt == null
                || activatedAt == null || resolvedAt != null)) {
            throw new IllegalArgumentException("ACTIVE summon requires backend lease metadata");
        }
        if ((status == BountySummonStatus.DEFEATED || status == BountySummonStatus.FAILED)
                && (ownerBackendId == null || ownerBackendId.isBlank() || leaseExpiresAt != null
                || activatedAt == null || resolvedAt == null)) {
            throw new IllegalArgumentException("terminal summon requires resolved runtime metadata");
        }
        if (ownerBackendId != null) {
            ownerBackendId = ownerBackendId.trim();
        }
    }
}

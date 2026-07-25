package io.github.kevinrabbe.minecraftserver.common.pve.map;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persistent reservation linking one source Map/player to one exact disposable encounter instance. */
public record MapEncounterReservationSnapshot(
        UUID reservationId,
        UUID sourceMapItemId,
        UUID playerId,
        UUID targetInstanceId,
        String targetBackendId,
        String targetZoneId,
        String targetTemplateVersion,
        MapEncounterReservationStatus status,
        UUID runId,
        Instant leaseExpiresAt,
        long stateVersion,
        Instant createdAt,
        Instant boundAt,
        Instant resolvedAt
) {
    public MapEncounterReservationSnapshot {
        reservationId = Objects.requireNonNull(reservationId, "reservationId");
        sourceMapItemId = Objects.requireNonNull(sourceMapItemId, "sourceMapItemId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        targetInstanceId = Objects.requireNonNull(targetInstanceId, "targetInstanceId");
        targetBackendId = requireText(targetBackendId, "targetBackendId");
        targetZoneId = requireText(targetZoneId, "targetZoneId");
        targetTemplateVersion = requireText(targetTemplateVersion, "targetTemplateVersion");
        status = Objects.requireNonNull(status, "status");
        leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion must be >= 0");
        }
        if (status == MapEncounterReservationStatus.RESERVED
                && (runId != null || boundAt != null || resolvedAt != null)) {
            throw new IllegalArgumentException("RESERVED encounter reservation cannot carry run/resolution state");
        }
        if (status == MapEncounterReservationStatus.BOUND
                && (runId == null || boundAt == null || resolvedAt != null)) {
            throw new IllegalArgumentException("BOUND encounter reservation requires run/boundAt and no resolvedAt");
        }
        if (status == MapEncounterReservationStatus.EXPIRED
                && (runId != null || boundAt != null || resolvedAt == null)) {
            throw new IllegalArgumentException("EXPIRED encounter reservation has invalid shape");
        }
        if (status == MapEncounterReservationStatus.RELEASED && resolvedAt == null) {
            throw new IllegalArgumentException("RELEASED encounter reservation requires resolvedAt");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}

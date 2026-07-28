package io.github.kevinrabbe.minecraftserver.common.pve.map;

import io.github.kevinrabbe.minecraftserver.common.session.SessionConflictException;
import io.github.kevinrabbe.minecraftserver.common.session.TransferRecoveryRepository;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Resolves persisted Map handoff failures without restoring the consumed Map.
 * A concurrent target start wins: failRun rejects once the scanned CREATED state_version is stale, so the bound slot
 * remains live.
 */
public final class MapEncounterRecoveryService {
    private static final String FAIL_REASON = "map.encounter_recovery";

    private final MapEncounterRecoveryRepository recovery;
    private final MapAuthorityRepository maps;
    private final MapEncounterReservationReleaseRepository releases;
    private final TransferRecoveryRepository transfers;
    private final Duration noHandoffGrace;
    private final Duration targetStartGrace;
    private final int limit;

    public MapEncounterRecoveryService(
            MapEncounterRecoveryRepository recovery,
            MapAuthorityRepository maps,
            MapEncounterReservationReleaseRepository releases,
            TransferRecoveryRepository transfers,
            Duration noHandoffGrace,
            Duration targetStartGrace,
            int limit
    ) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.maps = Objects.requireNonNull(maps, "maps");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.transfers = Objects.requireNonNull(transfers, "transfers");
        this.noHandoffGrace = Objects.requireNonNull(noHandoffGrace, "noHandoffGrace");
        this.targetStartGrace = Objects.requireNonNull(targetStartGrace, "targetStartGrace");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        this.limit = limit;
    }

    /** Returns the number of CREATED Map runs transitioned to FAILED and released during this bounded pass. */
    public int recoverOnce() throws SQLException {
        List<MapEncounterRecoveryCandidate> candidates = recovery.listRecoverable(
                noHandoffGrace,
                targetStartGrace,
                limit
        );
        int recovered = 0;
        for (MapEncounterRecoveryCandidate candidate : candidates) {
            if (candidate.reason() == MapEncounterRecoveryReason.TRANSFER_EXPIRED) {
                try {
                    transfers.abortAttachedTransfer(
                            candidate.sourceBackendId(),
                            candidate.sessionId(),
                            candidate.transferId()
                    );
                } catch (SessionConflictException ignored) {
                    // The exact transfer/session changed after the scan. The run transition below remains authoritative.
                }
            }

            try {
                maps.failRun(
                        failOperationId(candidate.runId()),
                        candidate.runId(),
                        candidate.runStateVersion(),
                        FAIL_REASON
                );
            } catch (MapAuthorityException concurrentStateChange) {
                // Target start/completion may have won after the scan. Never release a slot for a changed run here.
                continue;
            }

            releases.releaseTerminalRun(candidate.reservationId(), candidate.runId());
            recovered++;
        }
        return recovered;
    }

    private static UUID failOperationId(UUID runId) {
        return UUID.nameUUIDFromBytes(
                ("map-encounter-recovery:" + runId).getBytes(StandardCharsets.UTF_8)
        );
    }
}

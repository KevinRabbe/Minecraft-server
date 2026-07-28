package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityException;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapCompletedEncounterCandidate;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapCompletedEncounterRecoveryRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterReservationReleaseRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRewardFulfillmentRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRewardGrantSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRewardSettlementRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRewardSettlementResult;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunStatus;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/** Completes Maps and closes the durable completion -> reward -> fulfillment -> slot-release chain idempotently. */
final class PaperMapCompletionService {
    private static final String COMPLETE_REASON = "map.extermination_complete";
    private static final int RECOVERY_LIMIT = 50;

    private final MinecraftServerPlugin plugin;
    private final MapAuthorityRepository maps;
    private final MapRewardSettlementRepository settlements;
    private final MapRewardFulfillmentRepository fulfillment;
    private final MapEncounterReservationReleaseRepository releases;
    private final MapCompletedEncounterRecoveryRepository recovery;

    PaperMapCompletionService(
            MinecraftServerPlugin plugin,
            MapAuthorityRepository maps,
            MapRewardSettlementRepository settlements,
            MapRewardFulfillmentRepository fulfillment,
            MapEncounterReservationReleaseRepository releases,
            MapCompletedEncounterRecoveryRepository recovery
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.maps = Objects.requireNonNull(maps, "maps");
        this.settlements = Objects.requireNonNull(settlements, "settlements");
        this.fulfillment = Objects.requireNonNull(fulfillment, "fulfillment");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
    }

    void completeAndFulfill(UUID runId, UUID reservationId, long elapsedMillis) throws SQLException {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(reservationId, "reservationId");
        MapRunSnapshot current = maps.loadRun(runId);
        if (current.status() == MapRunStatus.ACTIVE) {
            maps.completeRun(
                    deterministicOperation("complete", runId),
                    runId,
                    current.stateVersion(),
                    Math.max(1L, elapsedMillis),
                    COMPLETE_REASON
            );
        } else if (current.status() != MapRunStatus.COMPLETED) {
            throw new MapAuthorityException("Map run is not completable: " + runId + " status=" + current.status());
        }
        fulfillCompleted(runId, reservationId, maps.loadRun(runId).stateVersion());
    }

    int recoverCompletedOnce() throws SQLException {
        List<MapCompletedEncounterCandidate> candidates = recovery.listRecoverable(RECOVERY_LIMIT);
        int recovered = 0;
        for (MapCompletedEncounterCandidate candidate : candidates) {
            try {
                fulfillCompleted(
                        candidate.runId(),
                        candidate.reservationId(),
                        candidate.runStateVersion()
                );
                recovered++;
            } catch (SQLException | RuntimeException exception) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Could not recover completed Map encounter " + candidate.runId(),
                        exception
                );
            }
        }
        return recovered;
    }

    private void fulfillCompleted(UUID runId, UUID reservationId, long expectedRunStateVersion) throws SQLException {
        MapRewardSettlementResult settlement = settlements.settle(
                deterministicOperation("reward", runId),
                runId,
                expectedRunStateVersion
        );
        for (MapRewardGrantSnapshot grant : settlement.grants()) {
            fulfillment.fulfill(grant.grantId());
        }
        releases.releaseTerminalRun(reservationId, runId);
    }

    static UUID deterministicOperation(String action, UUID runId) {
        return UUID.nameUUIDFromBytes(
                ("minecraft-server:map:" + action + ":" + runId).getBytes(StandardCharsets.UTF_8)
        );
    }
}

package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationClaim;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityException;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterReservationRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterReservationSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapItemProfile;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapPlayerStateOpenRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapPlayerStateOpenResult;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Safe Paper source boundary for reserving one disposable encounter, consuming the exact represented Map, and handing
 * the player to that encounter through the ordinary transfer protocol.
 */
final class PaperMapOpenService {
    private static final String OPEN_REASON = "map.open";
    private static final Duration RESERVATION_LEASE = Duration.ofSeconds(30);

    private final MinecraftServerPlugin plugin;
    private final PaperSessionController sessions;
    private final PaperItemIdentityCodec itemIdentity;
    private final PaperUniqueItemStateRemovalMutator itemRemoval;
    private final MapAuthorityRepository mapAuthority;
    private final MapPlayerStateOpenRepository maps;
    private final MapEncounterReservationRepository reservations;
    private final PaperMapEncounterRouteCatalog routes;

    PaperMapOpenService(
            MinecraftServerPlugin plugin,
            PaperSessionController sessions,
            MapAuthorityRepository mapAuthority,
            MapPlayerStateOpenRepository maps,
            MapEncounterReservationRepository reservations,
            PaperMapEncounterRouteCatalog routes,
            PaperUniqueItemStateRemovalMutator itemRemoval
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.itemIdentity = new PaperItemIdentityCodec(plugin);
        this.mapAuthority = Objects.requireNonNull(mapAuthority, "mapAuthority");
        this.maps = Objects.requireNonNull(maps, "maps");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.itemRemoval = Objects.requireNonNull(itemRemoval, "itemRemoval");
    }

    CompletableFuture<MapPlayerStateOpenResult> open(Player player, ItemStack representedMap) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(representedMap, "representedMap");
        Optional<ItemRepresentationClaim> optional = itemIdentity.readClaim(representedMap, "map-open-request");
        if (optional.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new PaperItemRepresentationException("Map open requires an authoritative individualized item")
            );
        }
        ItemRepresentationClaim claim = optional.orElseThrow();
        if (!claim.individualClaim() || claim.itemInstanceId() == null || claim.authorityVersion() == null) {
            return CompletableFuture.failedFuture(
                    new PaperItemRepresentationException("Map open requires an individualized item identity")
            );
        }

        UUID operationId = UUID.randomUUID();
        AtomicReference<MapPlayerStateOpenResult> committed = new AtomicReference<>();
        AtomicReference<MapEncounterReservationSnapshot> reserved = new AtomicReference<>();
        AtomicReference<PaperMapEncounterRoute> selectedRoute = new AtomicReference<>();
        CompletableFuture<MapPlayerStateOpenResult> result = new CompletableFuture<>();

        sessions.mutateAuthoritativeState(player, context -> {
            if (context.logicalZoneId() == null || context.logicalZoneId().isBlank()) {
                throw new MapAuthorityException(
                        "Map opening requires a persistent source logical zone for safe encounter return routing"
                );
            }
            MapItemProfile profile = mapAuthority.loadMapProfile(claim.itemInstanceId());
            PaperMapEncounterRoute route = routes.require(profile.runDefinition().environmentId());
            MapEncounterReservationSnapshot reservation = reservations.reserve(
                    operationId,
                    context.playerId(),
                    claim.itemInstanceId(),
                    route.zoneId(),
                    route.templateVersion(),
                    RESERVATION_LEASE
            );
            reserved.set(reservation);
            selectedRoute.set(route);

            byte[] nextPayload = itemRemoval.remove(
                    context.playerId(),
                    claim.itemInstanceId(),
                    claim.authorityVersion(),
                    context.currentStatePayload()
            );
            MapPlayerStateOpenResult opened = maps.openMap(
                    operationId,
                    claim.itemInstanceId(),
                    context.sessionId(),
                    context.backendId(),
                    context.stateVersion(),
                    claim.authorityVersion(),
                    context.logicalZoneId(),
                    context.entryPoint(),
                    nextPayload,
                    OPEN_REASON
            );
            committed.set(opened);
            return new PaperAuthoritativeStateMutation.Result(
                    opened.playerStateVersion(),
                    nextPayload
            );
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                releaseReservedBestEffort(reserved.get());
                result.completeExceptionally(failure);
                return;
            }
            MapPlayerStateOpenResult opened = committed.get();
            PaperMapEncounterRoute route = selectedRoute.get();
            if (opened == null || route == null) {
                result.completeExceptionally(
                        new IllegalStateException("Map state committed without captured encounter handoff state")
                );
                return;
            }

            scheduleTransferAfterLiveStateApply(player, route);
            result.complete(opened);
        });
        return result;
    }

    private void scheduleTransferAfterLiveStateApply(Player player, PaperMapEncounterRoute route) {
        try {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    sessions.requestZoneTransfer(player, route.zoneId());
                }
            }, 1L);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Map run opened but encounter transfer could not be scheduled; persisted recovery will resolve it",
                    exception
            );
        }
    }

    private void releaseReservedBestEffort(MapEncounterReservationSnapshot reservation) {
        if (reservation == null) {
            return;
        }
        try {
            reservations.releaseReserved(reservation.reservationId(), reservation.playerId());
        } catch (Exception exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not release pre-open Map encounter reservation; persisted recovery/lease expiry will resolve it",
                    exception
            );
        }
    }
}

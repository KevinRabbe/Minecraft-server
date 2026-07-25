package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationClaim;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapPlayerStateOpenRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapPlayerStateOpenResult;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Safe Paper boundary for consuming one represented Map into one persistent CREATED run.
 *
 * <p>This service intentionally does not expose a command or portal by itself. Runtime provisioning must succeed as a
 * real product path before players are allowed to consume Maps through it.</p>
 */
final class PaperMapOpenService {
    private static final String OPEN_REASON = "map.open";

    private final PaperSessionController sessions;
    private final PaperItemIdentityCodec itemIdentity;
    private final PaperUniqueItemStateRemovalMutator itemRemoval;
    private final MapPlayerStateOpenRepository maps;

    PaperMapOpenService(
            MinecraftServerPlugin plugin,
            PaperSessionController sessions,
            MapPlayerStateOpenRepository maps,
            PaperUniqueItemStateRemovalMutator itemRemoval
    ) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.itemIdentity = new PaperItemIdentityCodec(Objects.requireNonNull(plugin, "plugin"));
        this.maps = Objects.requireNonNull(maps, "maps");
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
        CompletableFuture<MapPlayerStateOpenResult> result = new CompletableFuture<>();

        sessions.mutateAuthoritativeState(player, context -> {
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
                result.completeExceptionally(failure);
                return;
            }
            MapPlayerStateOpenResult opened = committed.get();
            if (opened == null) {
                result.completeExceptionally(
                        new IllegalStateException("Map state committed without a captured Map-open result")
                );
                return;
            }
            result.complete(opened);
        });
        return result;
    }
}

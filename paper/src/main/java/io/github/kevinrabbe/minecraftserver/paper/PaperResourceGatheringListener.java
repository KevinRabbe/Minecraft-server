package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceGatheringService;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceHarvestFulfillmentResult;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceException;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceRepository;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceSnapshot;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;

/**
 * Paper representation bridge for authored renewable block sources.
 *
 * <p>Only exact version-controlled placements are intercepted. The physical block is never the economic authority:
 * PostgreSQL consumes the source cycle and creates the recoverable reward entitlement. Visible block/AIR state is
 * derived from the authoritative cooldown and is reconstructed after restart.</p>
 */
final class PaperResourceGatheringListener implements Listener {
    private static final String HARVEST_REASON = "resource.harvest";

    private final JavaPlugin plugin;
    private final String backendId;
    private final PaperSessionController sessions;
    private final PaperResourceSessionResolver sessionResolver;
    private final PaperCommodityDeliveryController commodityDeliveries;
    private final PaperItemUseEligibilityController itemUseEligibility;
    private final ResourceSourceRepository sourceRepository;
    private final ResourceGatheringService gathering;
    private final Map<PaperResourceSourcePlacement.BlockKey, RegisteredSource> sourcesByBlock;

    PaperResourceGatheringListener(
            JavaPlugin plugin,
            String backendId,
            PaperSessionController sessions,
            BootstrapZoneInstance zoneInstance,
            PaperResourceSourcePlacementCatalog placements,
            ResourceSourceRepository sourceRepository,
            ResourceGatheringService gathering,
            PaperResourceSessionResolver sessionResolver,
            PaperCommodityDeliveryController commodityDeliveries,
            PaperItemUseEligibilityController itemUseEligibility
    ) throws SQLException {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("backendId must not be blank");
        }
        this.backendId = backendId.trim();
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.sourceRepository = Objects.requireNonNull(sourceRepository, "sourceRepository");
        this.gathering = Objects.requireNonNull(gathering, "gathering");
        this.sessionResolver = Objects.requireNonNull(sessionResolver, "sessionResolver");
        this.commodityDeliveries = Objects.requireNonNull(commodityDeliveries, "commodityDeliveries");
        this.itemUseEligibility = Objects.requireNonNull(itemUseEligibility, "itemUseEligibility");
        Objects.requireNonNull(zoneInstance, "zoneInstance");
        Objects.requireNonNull(placements, "placements");

        HashMap<PaperResourceSourcePlacement.BlockKey, RegisteredSource> registered = new HashMap<>();
        for (PaperResourceSourcePlacement placement : placements.forZone(
                zoneInstance.zoneId(), zoneInstance.templateVersion()
        )) {
            World world = plugin.getServer().getWorld(placement.worldName());
            if (world == null) {
                throw new IllegalStateException(
                        "Configured resource source world is not loaded: " + placement.worldName()
                );
            }

            ResourceSourceSnapshot source = sourceRepository.ensureSource(
                    zoneInstance.instanceId(), placement.sourceKey(), placement.definitionId()
            );
            RegisteredSource registeredSource = new RegisteredSource(placement, source.sourceId());
            reconcileVisualOnMainThread(registeredSource, source);

            RegisteredSource previous = registered.put(placement.blockKey(), registeredSource);
            if (previous != null) {
                throw new IllegalStateException("Duplicate registered resource block: " + placement.blockKey());
            }
        }
        this.sourcesByBlock = Map.copyOf(registered);
    }

    int registeredSourceCount() {
        return sourcesByBlock.size();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        PaperResourceSourcePlacement.BlockKey blockKey = new PaperResourceSourcePlacement.BlockKey(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ()
        );
        RegisteredSource registered = sourcesByBlock.get(blockKey);
        if (registered == null) {
            return;
        }

        // An authored coordinate is controlled by this system even if the rendered block was modified unexpectedly.
        // Never let a representation mismatch become a vanilla drop/value path.
        event.setCancelled(true);
        event.setDropItems(false);
        event.setExpToDrop(0);

        Player player = event.getPlayer();
        if (block.getType() != registered.placement().expectedBlock()) {
            plugin.getLogger().warning(
                    "Resource source representation mismatch at " + blockKey + ": expected "
                            + registered.placement().expectedBlock() + " but found " + block.getType()
            );
            player.sendMessage(Component.text("This resource source is temporarily unavailable."));
            refreshVisual(registered);
            return;
        }
        if (sessions.isMutationFrozen(player.getUniqueId())) {
            player.sendMessage(Component.text("Your persistent state is busy. Try again shortly."));
            return;
        }

        UUID operationId = UUID.randomUUID();
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> harvest(
                    player.getUniqueId(), operationId, registered
            ));
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule resource harvest", exception);
            player.sendMessage(Component.text("Resource service is temporarily unavailable."));
        }
    }

    private void harvest(UUID minecraftUuid, UUID operationId, RegisteredSource registered) {
        try {
            Optional<PaperResourceSessionResolver.ResourceSessionHint> resolved = sessionResolver.resolve(minecraftUuid);
            if (resolved.isEmpty()) {
                sendIfOnline(minecraftUuid, "This resource source is not attached to your active instance.");
                return;
            }
            PaperResourceSessionResolver.ResourceSessionHint hint = resolved.orElseThrow();

            ResourceHarvestFulfillmentResult result = gathering.harvestAndFulfill(
                    operationId,
                    hint.sessionId(),
                    backendId,
                    hint.stateVersion(),
                    registered.sourceId(),
                    HARVEST_REASON
            );
            if (result.experienceAward() != null) {
                itemUseEligibility.applyCommittedAward(result.experienceAward());
            }
            ResourceSourceSnapshot source = sourceRepository.loadSource(registered.sourceId());
            runOnMainThread(() -> reconcileVisualOnMainThread(registered, source));
            commodityDeliveries.requestDrain(minecraftUuid);
            sendIfOnline(
                    minecraftUuid,
                    "Harvest secured: " + result.entitlement().commodityQuantity() + " × "
                            + result.entitlement().commodityDefinitionId()
            );
        } catch (ResourceSourceException exception) {
            // Expected authority rejection: cooldown, stale session/version, wrong instance, etc.
            refreshVisual(registered);
            sendIfOnline(minecraftUuid, "That resource is not available yet.");
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Resource harvest persistence failed", exception);
            sendIfOnline(minecraftUuid, "Resource service is temporarily unavailable.");
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Resource harvest failed closed", exception);
            sendIfOnline(minecraftUuid, "Resource service is temporarily unavailable.");
        }
    }

    private void refreshVisual(RegisteredSource registered) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    ResourceSourceSnapshot source = sourceRepository.loadSource(registered.sourceId());
                    runOnMainThread(() -> reconcileVisualOnMainThread(registered, source));
                } catch (SQLException | RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING, "Could not reconcile resource source visual", exception);
                }
            });
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule resource visual reconciliation", exception);
        }
    }

    private void reconcileVisualOnMainThread(RegisteredSource registered, ResourceSourceSnapshot source) {
        PaperResourceSourcePlacement placement = registered.placement();
        World world = plugin.getServer().getWorld(placement.worldName());
        if (world == null) {
            plugin.getLogger().warning("Resource source world unloaded during visual reconciliation: " + placement.worldName());
            return;
        }
        Block block = world.getBlockAt(placement.blockX(), placement.blockY(), placement.blockZ());
        boolean available = !source.nextAvailableAt().isAfter(Instant.now());
        Material expected = placement.expectedBlock();

        if (available) {
            if (block.getType() != Material.AIR && block.getType() != expected) {
                throw new IllegalStateException(
                        "Resource source " + placement.sourceKey() + " has unexpected block " + block.getType()
                                + " at " + placement.blockKey()
                );
            }
            if (block.getType() != expected) {
                block.setType(expected, false);
            }
            return;
        }

        if (block.getType() != Material.AIR && block.getType() != expected) {
            throw new IllegalStateException(
                    "Cooling resource source " + placement.sourceKey() + " has unexpected block " + block.getType()
                            + " at " + placement.blockKey()
            );
        }
        if (block.getType() != Material.AIR) {
            block.setType(Material.AIR, false);
        }

        long delayMillis = Math.max(1L, Duration.between(Instant.now(), source.nextAvailableAt()).toMillis());
        long delayTicks = Math.max(1L, (delayMillis + 49L) / 50L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> refreshVisual(registered), delayTicks);
    }

    private void sendIfOnline(UUID minecraftUuid, String message) {
        runOnMainThread(() -> {
            Player player = plugin.getServer().getPlayer(minecraftUuid);
            if (player != null && player.isOnline()) {
                player.sendMessage(Component.text(message));
            }
        });
    }

    private void runOnMainThread(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    private record RegisteredSource(PaperResourceSourcePlacement placement, UUID sourceId) {
        private RegisteredSource {
            placement = Objects.requireNonNull(placement, "placement");
            sourceId = Objects.requireNonNull(sourceId, "sourceId");
        }
    }
}

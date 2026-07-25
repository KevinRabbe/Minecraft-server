package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.artifact.ArtifactDiscoveryResult;
import io.github.kevinrabbe.minecraftserver.common.artifact.ArtifactException;
import io.github.kevinrabbe.minecraftserver.common.artifact.ArtifactRepository;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;

import static org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK;

/** Paper representation bridge for permanent per-player hidden Artifact discoveries. */
final class PaperArtifactDiscoveryListener implements Listener {
    private final JavaPlugin plugin;
    private final PaperPlayerIdentityResolver playerIdentities;
    private final ArtifactRepository artifacts;
    private final PaperArtifactPlacementCatalog placements;

    PaperArtifactDiscoveryListener(
            JavaPlugin plugin,
            PaperPlayerIdentityResolver playerIdentities,
            ArtifactRepository artifacts,
            PaperArtifactPlacementCatalog placements
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.playerIdentities = Objects.requireNonNull(playerIdentities, "playerIdentities");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.placements = Objects.requireNonNull(placements, "placements");
        reconcileAllOnMainThread();
    }

    int registeredArtifactCount() {
        return placements.all().size();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        PaperArtifactPlacementCatalog.PaperArtifactPlacement placement = find(block);
        if (placement == null) {
            return;
        }
        event.setCancelled(true);

        Player player = event.getPlayer();
        if (!placement.enabled()) {
            player.sendMessage(Component.text("This Artifact is currently dormant."));
            return;
        }
        if (block.getType() != placement.expectedBlock()) {
            plugin.getLogger().warning(
                    "Artifact representation mismatch at " + placement.blockKey() + ": expected "
                            + placement.expectedBlock() + " but found " + block.getType()
            );
            player.sendMessage(Component.text("This Artifact is temporarily unavailable."));
            return;
        }

        UUID operationId = UUID.randomUUID();
        UUID minecraftUuid = player.getUniqueId();
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> discover(
                    minecraftUuid,
                    operationId,
                    placement
            ));
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule Artifact discovery", exception);
            player.sendMessage(Component.text("Artifact service is temporarily unavailable."));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (find(event.getBlock()) != null) {
            event.setCancelled(true);
            event.setDropItems(false);
            event.setExpToDrop(0);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        protectExplosionList(event.blockList());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        protectExplosionList(event.blockList());
    }

    private void discover(
            UUID minecraftUuid,
            UUID operationId,
            PaperArtifactPlacementCatalog.PaperArtifactPlacement placement
    ) {
        try {
            Optional<UUID> playerId = playerIdentities.resolve(minecraftUuid);
            if (playerId.isEmpty()) {
                sendIfOnline(minecraftUuid, "Artifact service could not resolve your persistent player identity.");
                return;
            }
            ArtifactDiscoveryResult result = artifacts.discover(
                    operationId,
                    playerId.orElseThrow(),
                    placement.artifactId(),
                    placement.locationRevision(),
                    null
            );
            if (result.newlyDiscovered()) {
                sendIfOnline(
                        minecraftUuid,
                        "Artifact discovered. Attunement Points: " + result.totalAttunementPoints()
                );
            } else {
                sendIfOnline(
                        minecraftUuid,
                        "You already discovered this Artifact. Attunement Points: "
                                + result.totalAttunementPoints()
                );
            }
        } catch (ArtifactException exception) {
            plugin.getLogger().log(Level.WARNING, "Artifact discovery was rejected by authority", exception);
            sendIfOnline(minecraftUuid, "This Artifact is temporarily unavailable.");
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Artifact discovery persistence failed", exception);
            sendIfOnline(minecraftUuid, "Artifact service is temporarily unavailable.");
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Artifact discovery failed closed", exception);
            sendIfOnline(minecraftUuid, "Artifact service is temporarily unavailable.");
        }
    }

    private void reconcileAllOnMainThread() {
        for (PaperArtifactPlacementCatalog.PaperArtifactPlacement placement : placements.all()) {
            World world = plugin.getServer().getWorld(placement.worldName());
            if (world == null) {
                throw new IllegalStateException("Configured Artifact world is not loaded: " + placement.worldName());
            }
            Block block = world.getBlockAt(placement.blockX(), placement.blockY(), placement.blockZ());
            if (!placement.enabled()) {
                if (block.getType() == placement.expectedBlock()) {
                    block.setType(Material.AIR, false);
                } else if (block.getType() != Material.AIR) {
                    throw new IllegalStateException(
                            "Disabled Artifact has unexpected block at " + placement.blockKey() + ": " + block.getType()
                    );
                }
                continue;
            }
            if (block.getType() == Material.AIR) {
                block.setType(placement.expectedBlock(), false);
            } else if (block.getType() != placement.expectedBlock()) {
                throw new IllegalStateException(
                        "Artifact " + placement.artifactId() + " expects " + placement.expectedBlock()
                                + " at " + placement.blockKey() + " but found " + block.getType()
                );
            }
        }
    }

    private PaperArtifactPlacementCatalog.PaperArtifactPlacement find(Block block) {
        return placements.find(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private void protectExplosionList(List<Block> blocks) {
        blocks.removeIf(block -> find(block) != null);
    }

    private void sendIfOnline(UUID minecraftUuid, String message) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(minecraftUuid);
            if (player != null && player.isOnline()) {
                player.sendMessage(Component.text(message));
            }
        });
    }
}

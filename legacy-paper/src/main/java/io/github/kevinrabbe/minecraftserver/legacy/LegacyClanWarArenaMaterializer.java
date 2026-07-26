package io.github.kevinrabbe.minecraftserver.legacy;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Applies an already-validated pure Clan-War materialization plan to disposable Minecraft-1.8 state.
 * Persistent item identity never enters this class.
 */
final class LegacyClanWarArenaMaterializer {
    private final World world;
    private final LegacyClanWarArenaBuilder arenaBuilder;

    LegacyClanWarArenaMaterializer(World world, LegacyClanWarArenaSettings settings) {
        this.world = Objects.requireNonNull(world, "world");
        this.arenaBuilder = new LegacyClanWarArenaBuilder(world, Objects.requireNonNull(settings, "settings"));
    }

    /**
     * Reserves the current single-runtime combat slot, rebuilds the disposable arena, applies every projected item and
     * spawn, then leaves the combat gate open. If any mutation fails, combat is closed and all touched players are put
     * back into spectator state before the failure escapes to the caller.
     */
    boolean materialize(
            LegacyClanWarMaterializationPlan plan,
            Map<UUID, Player> playersByMinecraftUuid,
            LegacyCompetitiveCombatGate combatGate
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(playersByMinecraftUuid, "playersByMinecraftUuid");
        Objects.requireNonNull(combatGate, "combatGate");

        LegacyExecution execution = plan.getWar().getExecution();
        LinkedHashSet<UUID> expectedPlayers = new LinkedHashSet<UUID>();
        for (LegacyParticipant participant : execution.getParticipants()) {
            expectedPlayers.add(participant.getMinecraftUuid());
        }
        if (!playersByMinecraftUuid.keySet().equals(expectedPlayers)) {
            throw new IllegalArgumentException("Clan-War materializer requires the exact frozen online roster");
        }
        for (UUID minecraftUuid : expectedPlayers) {
            Player player = playersByMinecraftUuid.get(minecraftUuid);
            if (player == null || !player.isOnline() || !minecraftUuid.equals(player.getUniqueId())) {
                throw new IllegalArgumentException("Clan-War materializer received an unavailable/mismatched player");
            }
        }

        UUID executionId = execution.getExecutionId();
        if (!combatGate.enableExclusive(executionId)) {
            return false;
        }

        try {
            arenaBuilder.rebuild();
            for (LegacyParticipant participant : execution.getParticipants()) {
                UUID minecraftUuid = participant.getMinecraftUuid();
                Player player = playersByMinecraftUuid.get(minecraftUuid);
                List<LegacyClanWarInventoryProjection.SlotItem> items =
                        plan.getInventoryProjection().getItemsByMinecraftUuid().get(minecraftUuid);
                LegacyClanWarSpawnLayout.SpawnPoint spawn = plan.getSpawnLayout().get(minecraftUuid);
                if (items == null || spawn == null) {
                    throw new IllegalStateException("Clan-War pure plan lost exact frozen roster coverage");
                }
                resetPlayer(player, items);
                requireTeleport(player, spawn);
            }
            return true;
        } catch (RuntimeException exception) {
            combatGate.disable(executionId);
            for (Player player : playersByMinecraftUuid.values()) {
                try {
                    player.setGameMode(GameMode.SPECTATOR);
                } catch (RuntimeException ignored) {
                    // Best-effort local quarantine; the caller still reports FAILURE through the trusted result boundary.
                }
            }
            throw exception;
        }
    }

    private void resetPlayer(
            Player player,
            List<LegacyClanWarInventoryProjection.SlotItem> projectedItems
    ) {
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setHelmet(null);
        inventory.setChestplate(null);
        inventory.setLeggings(null);
        inventory.setBoots(null);
        player.setItemOnCursor(null);

        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }

        for (LegacyClanWarInventoryProjection.SlotItem item : projectedItems) {
            Material material = Material.matchMaterial(item.getMaterialId());
            if (material == null || material == Material.AIR) {
                throw new IllegalArgumentException(
                        "Clan-War projected material is unavailable in Minecraft 1.8: " + item.getMaterialId()
                );
            }
            inventory.setItem(item.getInventorySlot(), new ItemStack(material, 1));
        }

        inventory.setHeldItemSlot(0);
        player.closeInventory();
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setGameMode(GameMode.SURVIVAL);
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setSaturation(20.0F);
        player.setExhaustion(0.0F);
        player.setFireTicks(0);
        player.setFallDistance(0.0F);
        player.setExp(0.0F);
        player.setLevel(0);
        player.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
        player.updateInventory();
    }

    private void requireTeleport(Player player, LegacyClanWarSpawnLayout.SpawnPoint spawn) {
        Location location = new Location(
                world,
                spawn.getX(),
                spawn.getY(),
                spawn.getZ(),
                spawn.getYaw(),
                0.0F
        );
        if (!player.teleport(location)) {
            throw new IllegalStateException("Clan-War player teleport was rejected for " + player.getUniqueId());
        }
    }
}

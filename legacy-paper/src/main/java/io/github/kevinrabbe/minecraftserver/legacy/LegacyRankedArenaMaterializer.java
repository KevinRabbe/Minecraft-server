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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Materializes the first disposable single-arena Ranked 1v1 runtime state. */
final class LegacyRankedArenaMaterializer {
    private final World world;
    private final LegacyRankedArenaSettings settings;
    private final Material floorMaterial;
    private final Material borderMaterial;
    private final Material wallMaterial;
    private final List<ResolvedLoadoutEntry> loadout;

    LegacyRankedArenaMaterializer(World world, LegacyRankedArenaSettings settings) {
        this.world = Objects.requireNonNull(world, "world");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.floorMaterial = requireMaterial(settings.getFloorMaterial(), "floor material");
        this.borderMaterial = requireMaterial(settings.getBorderMaterial(), "border material");
        this.wallMaterial = requireMaterial(settings.getWallMaterial(), "wall material");
        if (!floorMaterial.isBlock() || !borderMaterial.isBlock() || !wallMaterial.isBlock()) {
            throw new IllegalArgumentException("ranked arena floor/border/wall materials must be blocks");
        }

        ArrayList<ResolvedLoadoutEntry> resolved = new ArrayList<ResolvedLoadoutEntry>();
        for (LegacyRankedArenaSettings.LoadoutEntry entry : settings.getLoadout()) {
            Material material = requireMaterial(entry.getMaterial(), "loadout material");
            if (material == Material.AIR) {
                throw new IllegalArgumentException("ranked temporary loadout cannot contain AIR");
            }
            resolved.add(new ResolvedLoadoutEntry(entry.getSlot(), material, entry.getAmount()));
        }
        this.loadout = resolved;
    }

    /**
     * Reserves the single V1 arena, rebuilds its disposable geometry, resets both temporary players, and only then
     * returns with combat enabled. Bukkit event execution is main-thread serialized, so the exclusive reservation cannot
     * interleave with another materialization while this method is running.
     */
    boolean materialize(
            LegacyRankedExecution ranked,
            Player playerA,
            Player playerB,
            LegacyCompetitiveCombatGate combatGate
    ) {
        Objects.requireNonNull(ranked, "ranked");
        Objects.requireNonNull(playerA, "playerA");
        Objects.requireNonNull(playerB, "playerB");
        Objects.requireNonNull(combatGate, "combatGate");
        requireParticipant(ranked.getPlayerA(), playerA);
        requireParticipant(ranked.getPlayerB(), playerB);

        UUID executionId = ranked.getExecution().getExecutionId();
        if (!combatGate.enableExclusive(executionId)) {
            return false;
        }

        try {
            rebuildArena();
            resetPlayer(playerA);
            resetPlayer(playerB);
            playerA.teleport(spawnA());
            playerB.teleport(spawnB());
            return true;
        } catch (RuntimeException exception) {
            combatGate.disable(executionId);
            throw exception;
        }
    }

    private void rebuildArena() {
        world.setPVP(true);
        int minX = settings.getOriginX() - settings.getHalfSize();
        int maxX = settings.getOriginX() + settings.getHalfSize();
        int minZ = settings.getOriginZ() - settings.getHalfSize();
        int maxZ = settings.getOriginZ() + settings.getHalfSize();
        int floorY = settings.getFloorY();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean edge = x == minX || x == maxX || z == minZ || z == maxZ;
                world.getBlockAt(x, floorY, z).setType(edge ? borderMaterial : floorMaterial);
                for (int height = 1; height <= settings.getWallHeight(); height++) {
                    world.getBlockAt(x, floorY + height, z).setType(edge ? wallMaterial : Material.AIR);
                }
            }
        }
    }

    private void resetPlayer(Player player) {
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

        for (ResolvedLoadoutEntry entry : loadout) {
            ItemStack stack = new ItemStack(entry.material, entry.amount);
            if (entry.slot.equals("helmet")) inventory.setHelmet(stack);
            else if (entry.slot.equals("chestplate")) inventory.setChestplate(stack);
            else if (entry.slot.equals("leggings")) inventory.setLeggings(stack);
            else if (entry.slot.equals("boots")) inventory.setBoots(stack);
            else inventory.setItem(Integer.parseInt(entry.slot), stack);
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

    private Location spawnA() {
        return new Location(
                world,
                settings.getOriginX() - settings.getSpawnOffset() + 0.5D,
                settings.getFloorY() + 1.0D,
                settings.getOriginZ() + 0.5D,
                -90.0F,
                0.0F
        );
    }

    private Location spawnB() {
        return new Location(
                world,
                settings.getOriginX() + settings.getSpawnOffset() + 0.5D,
                settings.getFloorY() + 1.0D,
                settings.getOriginZ() + 0.5D,
                90.0F,
                0.0F
        );
    }

    private static void requireParticipant(LegacyParticipant expected, Player actual) {
        if (!expected.getMinecraftUuid().equals(actual.getUniqueId())) {
            throw new IllegalArgumentException(
                    "Ranked materializer player does not match frozen participant " + expected.getParticipantIndex()
            );
        }
    }

    private static Material requireMaterial(String name, String field) {
        Material material = Material.matchMaterial(name);
        if (material == null) {
            throw new IllegalArgumentException(field + " is unknown to Minecraft 1.8.9: " + name);
        }
        return material;
    }

    private static final class ResolvedLoadoutEntry {
        private final String slot;
        private final Material material;
        private final int amount;

        private ResolvedLoadoutEntry(String slot, Material material, int amount) {
            this.slot = slot;
            this.material = material;
            this.amount = amount;
        }
    }
}

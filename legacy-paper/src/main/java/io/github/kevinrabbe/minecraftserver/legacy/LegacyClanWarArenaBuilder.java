package io.github.kevinrabbe.minecraftserver.legacy;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Objects;

/** Builds only the disposable Clan-War world shell. It does not place gear, players, or open combat. */
final class LegacyClanWarArenaBuilder {
    private final World world;
    private final LegacyClanWarArenaSettings settings;
    private final Material floorMaterial;
    private final Material borderMaterial;
    private final Material wallMaterial;

    LegacyClanWarArenaBuilder(World world, LegacyClanWarArenaSettings settings) {
        this.world = Objects.requireNonNull(world, "world");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.floorMaterial = requireBlockMaterial(settings.getFloorMaterial(), "floor material");
        this.borderMaterial = requireBlockMaterial(settings.getBorderMaterial(), "border material");
        this.wallMaterial = requireBlockMaterial(settings.getWallMaterial(), "wall material");
    }

    void rebuild() {
        int minX = settings.getOriginX() - settings.getHalfSize();
        int maxX = settings.getOriginX() + settings.getHalfSize();
        int minZ = settings.getOriginZ() - settings.getHalfSize();
        int maxZ = settings.getOriginZ() + settings.getHalfSize();
        int floorY = settings.getFloorY();

        clearDisposableEntities(minX, maxX, minZ, maxZ, floorY);

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

    private void clearDisposableEntities(int minX, int maxX, int minZ, int maxZ, int floorY) {
        int minY = floorY - 4;
        int maxY = floorY + settings.getWallHeight() + 8;
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Player) continue;
            Location location = entity.getLocation();
            if (location.getX() >= minX
                    && location.getX() <= maxX + 1.0D
                    && location.getY() >= minY
                    && location.getY() <= maxY
                    && location.getZ() >= minZ
                    && location.getZ() <= maxZ + 1.0D) {
                entity.remove();
            }
        }
    }

    private static Material requireBlockMaterial(String materialName, String field) {
        if (materialName == null || materialName.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = materialName.trim().toUpperCase(Locale.ROOT);
        Material material = Material.matchMaterial(normalized);
        if (material == null || material == Material.AIR || !material.isBlock()) {
            throw new IllegalArgumentException(field + " is not a valid Minecraft-1.8 block: " + normalized);
        }
        return material;
    }
}

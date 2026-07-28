package io.github.kevinrabbe.minecraftserver.legacy;

/** Validated cheap local geometry for the first disposable Clan-War arena. */
final class LegacyClanWarArenaSettings {
    private final int originX;
    private final int floorY;
    private final int originZ;
    private final int halfSize;
    private final int wallHeight;
    private final int spawnOffset;
    private final double spawnSpacing;
    private final String floorMaterial;
    private final String borderMaterial;
    private final String wallMaterial;

    LegacyClanWarArenaSettings(
            int originX,
            int floorY,
            int originZ,
            int halfSize,
            int wallHeight,
            int spawnOffset,
            double spawnSpacing,
            String floorMaterial,
            String borderMaterial,
            String wallMaterial
    ) {
        if (floorY < 1 || floorY > 250) {
            throw new IllegalArgumentException("Clan-War arena floorY must be between 1 and 250");
        }
        if (halfSize < 6 || halfSize > 128) {
            throw new IllegalArgumentException("Clan-War arena halfSize must be between 6 and 128");
        }
        if (wallHeight < 2 || wallHeight > 24) {
            throw new IllegalArgumentException("Clan-War arena wallHeight must be between 2 and 24");
        }
        if (spawnOffset < 1 || spawnOffset >= halfSize - 1) {
            throw new IllegalArgumentException("Clan-War spawnOffset must be inside the arena");
        }
        if (!Double.isFinite(spawnSpacing) || spawnSpacing < 1.0D || spawnSpacing > 16.0D) {
            throw new IllegalArgumentException("Clan-War spawnSpacing must be between 1 and 16 blocks");
        }
        this.originX = originX;
        this.floorY = floorY;
        this.originZ = originZ;
        this.halfSize = halfSize;
        this.wallHeight = wallHeight;
        this.spawnOffset = spawnOffset;
        this.spawnSpacing = spawnSpacing;
        this.floorMaterial = requireText(floorMaterial, "floorMaterial");
        this.borderMaterial = requireText(borderMaterial, "borderMaterial");
        this.wallMaterial = requireText(wallMaterial, "wallMaterial");
    }

    int getOriginX() { return originX; }
    int getFloorY() { return floorY; }
    int getOriginZ() { return originZ; }
    int getHalfSize() { return halfSize; }
    int getWallHeight() { return wallHeight; }
    int getSpawnOffset() { return spawnOffset; }
    double getSpawnSpacing() { return spawnSpacing; }
    String getFloorMaterial() { return floorMaterial; }
    String getBorderMaterial() { return borderMaterial; }
    String getWallMaterial() { return wallMaterial; }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}

package io.github.kevinrabbe.minecraftserver.legacy;

/** Pure coordinate predicate for the first Clan-War control point; world/Bukkit state stays outside this model. */
final class LegacyClanWarControlPointGeometry {
    private final double centerX;
    private final double centerY;
    private final double centerZ;
    private final double radiusSquared;

    LegacyClanWarControlPointGeometry(double centerX, double centerY, double centerZ, double radiusBlocks) {
        if (!Double.isFinite(centerX) || !Double.isFinite(centerY) || !Double.isFinite(centerZ)) {
            throw new IllegalArgumentException("Clan-War control-point center must be finite");
        }
        if (!Double.isFinite(radiusBlocks) || radiusBlocks < 1.0D || radiusBlocks > 64.0D) {
            throw new IllegalArgumentException("Clan-War control-point radius must be between 1 and 64 blocks");
        }
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.radiusSquared = radiusBlocks * radiusBlocks;
    }

    boolean contains(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return false;
        double dx = x - centerX;
        double dy = y - centerY;
        double dz = z - centerZ;
        return dx * dx + dy * dy + dz * dz <= radiusSquared;
    }

    double getCenterX() {
        return centerX;
    }

    double getCenterY() {
        return centerY;
    }

    double getCenterZ() {
        return centerZ;
    }
}

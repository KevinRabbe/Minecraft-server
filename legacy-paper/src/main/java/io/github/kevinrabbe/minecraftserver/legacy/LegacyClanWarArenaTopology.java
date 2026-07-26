package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.Objects;

/** Pure consistency checks between disposable Clan-War arena geometry and its control point. */
final class LegacyClanWarArenaTopology {
    private LegacyClanWarArenaTopology() { }

    static void requireObjectiveInsideArena(
            LegacyClanWarArenaSettings arena,
            LegacyClanWarControlPointGeometry controlPoint
    ) {
        Objects.requireNonNull(arena, "arena");
        Objects.requireNonNull(controlPoint, "controlPoint");

        double centerX = arena.getOriginX() + 0.5D;
        double centerZ = arena.getOriginZ() + 0.5D;
        double maxHorizontalOffset = arena.getHalfSize() - 1.0D;
        double dx = Math.abs(controlPoint.getCenterX() - centerX);
        double dz = Math.abs(controlPoint.getCenterZ() - centerZ);
        double minimumY = arena.getFloorY() + 1.0D;
        double maximumY = arena.getFloorY() + arena.getWallHeight();

        if (dx > maxHorizontalOffset || dz > maxHorizontalOffset
                || controlPoint.getCenterY() < minimumY
                || controlPoint.getCenterY() > maximumY) {
            throw new IllegalArgumentException("Clan-War control point must be inside the disposable arena interior");
        }
    }
}

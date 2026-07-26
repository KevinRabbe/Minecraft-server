package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.Objects;

/** Pure consistency checks for disposable competitive arena geometry. */
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

    static void requireDisjointFromRanked(
            LegacyClanWarArenaSettings clanWar,
            LegacyRankedArenaSettings ranked
    ) {
        Objects.requireNonNull(clanWar, "clanWar");
        Objects.requireNonNull(ranked, "ranked");

        boolean separatedX = clanWar.getOriginX() + clanWar.getHalfSize()
                < ranked.getOriginX() - ranked.getHalfSize()
                || ranked.getOriginX() + ranked.getHalfSize()
                < clanWar.getOriginX() - clanWar.getHalfSize();
        boolean separatedZ = clanWar.getOriginZ() + clanWar.getHalfSize()
                < ranked.getOriginZ() - ranked.getHalfSize()
                || ranked.getOriginZ() + ranked.getHalfSize()
                < clanWar.getOriginZ() - clanWar.getHalfSize();
        boolean separatedY = clanWar.getFloorY() + clanWar.getWallHeight() < ranked.getFloorY()
                || ranked.getFloorY() + ranked.getWallHeight() < clanWar.getFloorY();

        if (!separatedX && !separatedZ && !separatedY) {
            throw new IllegalArgumentException("Ranked and Clan-War disposable arena regions must not overlap");
        }
    }
}

package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Deterministic symmetric spawn placement for any configured Clan-War team size that fits the local arena. */
final class LegacyClanWarSpawnLayout {
    private LegacyClanWarSpawnLayout() { }

    static Map<UUID, SpawnPoint> build(LegacyClanWarExecution war, LegacyClanWarArenaSettings settings) {
        Objects.requireNonNull(war, "war");
        Objects.requireNonNull(settings, "settings");

        int challengerSlot = 0;
        int defenderSlot = 0;
        LinkedHashMap<UUID, SpawnPoint> result = new LinkedHashMap<UUID, SpawnPoint>();
        for (LegacyParticipant participant : war.getExecution().getParticipants()) {
            final int sideSlot;
            final double x;
            final float yaw;
            if ("CHALLENGER".equals(participant.getSideKey())) {
                sideSlot = challengerSlot++;
                x = settings.getOriginX() - settings.getSpawnOffset() + 0.5D;
                yaw = -90.0F;
            } else if ("DEFENDER".equals(participant.getSideKey())) {
                sideSlot = defenderSlot++;
                x = settings.getOriginX() + settings.getSpawnOffset() + 0.5D;
                yaw = 90.0F;
            } else {
                throw new IllegalArgumentException("unsupported Clan-War side " + participant.getSideKey());
            }

            double centeredSlot = sideSlot - (war.getExecution().getTeamSize() - 1) / 2.0D;
            double z = settings.getOriginZ() + 0.5D + centeredSlot * settings.getSpawnSpacing();
            double interiorLimit = settings.getHalfSize() - 1.0D;
            if (Math.abs(z - (settings.getOriginZ() + 0.5D)) > interiorLimit) {
                throw new IllegalArgumentException(
                        "configured Clan-War team size does not fit arena spawn spacing"
                );
            }

            SpawnPoint previous = result.put(
                    participant.getMinecraftUuid(),
                    new SpawnPoint(x, settings.getFloorY() + 1.0D, z, yaw)
            );
            if (previous != null) {
                throw new IllegalArgumentException("duplicate Minecraft UUID in Clan-War spawn layout");
            }
        }

        if (challengerSlot != war.getExecution().getTeamSize()
                || defenderSlot != war.getExecution().getTeamSize()) {
            throw new IllegalArgumentException("Clan-War spawn layout did not receive complete frozen sides");
        }
        return Collections.unmodifiableMap(result);
    }

    static final class SpawnPoint {
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;

        SpawnPoint(double x, double y, double z, float yaw) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
        }

        double getX() { return x; }
        double getY() { return y; }
        double getZ() { return z; }
        float getYaw() { return yaw; }
    }
}

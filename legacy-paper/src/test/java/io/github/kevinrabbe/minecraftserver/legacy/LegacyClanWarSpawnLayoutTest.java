package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyClanWarSpawnLayoutTest {
    @Test
    void laysOutConfiguredTeamSizeSymmetricallyWithoutOneVsOneAssumption() {
        LegacyClanWarExecution war = war(3);
        LegacyClanWarArenaSettings settings = settings(24, 2.0D);

        Map<UUID, LegacyClanWarSpawnLayout.SpawnPoint> spawns = LegacyClanWarSpawnLayout.build(war, settings);

        assertEquals(6, spawns.size());
        LegacyParticipant challengerFirst = war.getExecution().getParticipants().get(0);
        LegacyParticipant challengerMiddle = war.getExecution().getParticipants().get(1);
        LegacyParticipant challengerLast = war.getExecution().getParticipants().get(2);
        assertEquals(-1.5D, spawns.get(challengerFirst.getMinecraftUuid()).getZ());
        assertEquals(0.5D, spawns.get(challengerMiddle.getMinecraftUuid()).getZ());
        assertEquals(2.5D, spawns.get(challengerLast.getMinecraftUuid()).getZ());
        assertEquals(-90.0F, spawns.get(challengerMiddle.getMinecraftUuid()).getYaw());

        LegacyParticipant defenderMiddle = war.getExecution().getParticipants().get(4);
        assertEquals(90.0F, spawns.get(defenderMiddle.getMinecraftUuid()).getYaw());
    }

    @Test
    void failsClosedWhenConfiguredTeamCannotFitDisposableArena() {
        LegacyClanWarExecution war = war(8);
        LegacyClanWarArenaSettings settings = settings(6, 4.0D);

        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyClanWarSpawnLayout.build(war, settings)
        );
    }

    private static LegacyClanWarArenaSettings settings(int halfSize, double spacing) {
        return new LegacyClanWarArenaSettings(
                0, 200, 0, halfSize, 4, 3, spacing,
                "STONE", "BEDROCK", "GLASS"
        );
    }

    private static LegacyClanWarExecution war(int teamSize) {
        UUID challengerClan = UUID.randomUUID();
        UUID defenderClan = UUID.randomUUID();
        ArrayList<LegacyParticipant> participants = new ArrayList<LegacyParticipant>();
        for (int index = 0; index < teamSize; index++) {
            participants.add(new LegacyParticipant(
                    index,
                    "CHALLENGER",
                    challengerClan,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "SpawnC" + index
            ));
        }
        for (int index = 0; index < teamSize; index++) {
            participants.add(new LegacyParticipant(
                    teamSize + index,
                    "DEFENDER",
                    defenderClan,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "SpawnD" + index
            ));
        }
        LegacyExecution execution = new LegacyExecution(
                UUID.randomUUID(),
                "CLAN_WAR",
                UUID.randomUUID(),
                1,
                Instant.parse("2026-08-01T18:00:00Z"),
                "war.legacy_1_8_9",
                1,
                teamSize,
                participants
        );
        return LegacyClanWarExecution.requireSupported(execution);
    }
}

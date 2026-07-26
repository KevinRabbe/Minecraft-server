package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyClanWarControlPointPresenceTest {
    @Test
    void countsFrozenParticipantsWithoutAssumingOneVsOne() {
        UUID challengerClan = UUID.randomUUID();
        UUID defenderClan = UUID.randomUUID();
        ArrayList<LegacyParticipant> participants = new ArrayList<LegacyParticipant>();
        Set<UUID> inside = new HashSet<UUID>();

        for (int index = 0; index < 3; index++) {
            LegacyParticipant participant = participant(index, "CHALLENGER", challengerClan);
            participants.add(participant);
            if (index < 2) inside.add(participant.getMinecraftUuid());
        }
        for (int index = 3; index < 6; index++) {
            LegacyParticipant participant = participant(index, "DEFENDER", defenderClan);
            participants.add(participant);
            if (index == 3) inside.add(participant.getMinecraftUuid());
        }

        LegacyExecution execution = new LegacyExecution(
                UUID.randomUUID(),
                "CLAN_WAR",
                UUID.randomUUID(),
                1,
                Instant.parse("2026-08-01T18:00:00Z"),
                "war.legacy_1_8_9",
                1,
                3,
                participants
        );
        LegacyClanWarExecution war = LegacyClanWarExecution.requireSupported(execution);

        LegacyClanWarControlPointPresence.Counts counts = LegacyClanWarControlPointPresence.count(
                war,
                inside::contains
        );
        assertEquals(2, counts.getChallenger());
        assertEquals(1, counts.getDefender());
    }

    private static LegacyParticipant participant(int index, String side, UUID clanId) {
        return new LegacyParticipant(index, side, clanId, UUID.randomUUID(), UUID.randomUUID(), "Presence" + index);
    }
}

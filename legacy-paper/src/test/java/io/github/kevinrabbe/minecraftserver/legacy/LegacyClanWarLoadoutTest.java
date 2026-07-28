package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyClanWarLoadoutTest {
    @Test
    void acceptsExactOrderedSnapshotIncludingEmptyPlayerSelections() {
        LegacyClanWarExecution war = supportedWar();
        LegacyClanWarLoadout empty = LegacyClanWarLoadout.requireValid(war, Collections.<LegacyLoadoutItem>emptyList());
        assertTrue(empty.getItems().isEmpty(), "runtime structure must not invent a minimum kit size");

        LegacyClanWarLoadout populated = LegacyClanWarLoadout.requireValid(
                war,
                Arrays.asList(
                        item(0, 0, "war.bow"),
                        item(0, 1, "war.sword"),
                        item(1, 0, "war.sword")
                )
        );
        assertEquals(3, populated.getItems().size());
        assertEquals(war, populated.getWar());
        assertThrows(
                UnsupportedOperationException.class,
                () -> populated.getItems().add(item(1, 1, "war.extra"))
        );
    }

    @Test
    void rejectsGapsReorderingAndUnknownParticipants() {
        LegacyClanWarExecution war = supportedWar();

        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyClanWarLoadout.requireValid(war, Arrays.asList(item(0, 1, "war.sword")))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyClanWarLoadout.requireValid(
                        war,
                        Arrays.asList(item(1, 0, "war.sword"), item(0, 0, "war.bow"))
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyClanWarLoadout.requireValid(war, Arrays.asList(item(2, 0, "war.sword")))
        );
    }

    private static LegacyClanWarExecution supportedWar() {
        UUID challengerClan = UUID.randomUUID();
        UUID defenderClan = UUID.randomUUID();
        LegacyExecution execution = new LegacyExecution(
                UUID.randomUUID(),
                "CLAN_WAR",
                UUID.randomUUID(),
                1,
                Instant.parse("2026-08-01T18:00:00Z"),
                "war.legacy_1_8_9",
                1,
                1,
                Arrays.asList(
                        participant(0, "CHALLENGER", challengerClan),
                        participant(1, "DEFENDER", defenderClan)
                )
        );
        return LegacyClanWarExecution.requireSupported(execution);
    }

    private static LegacyParticipant participant(int index, String side, UUID clanId) {
        UUID playerId = UUID.randomUUID();
        return new LegacyParticipant(index, side, clanId, playerId, UUID.randomUUID(), "Loadout" + index);
    }

    private static LegacyLoadoutItem item(int participantIndex, int itemIndex, String definitionId) {
        return new LegacyLoadoutItem(participantIndex, itemIndex, definitionId, "{}", 0);
    }
}

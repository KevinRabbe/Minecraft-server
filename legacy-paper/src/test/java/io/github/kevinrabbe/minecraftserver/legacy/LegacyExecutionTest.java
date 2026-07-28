package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyExecutionTest {
    @Test
    void resolvesWinnerSideFromMinecraftIdentityAndRenewsImmutably() {
        UUID executionId = UUID.randomUUID();
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        UUID minecraftA = UUID.randomUUID();
        UUID minecraftB = UUID.randomUUID();
        LegacyExecution execution = new LegacyExecution(
                executionId,
                "RANKED_ARENA",
                UUID.randomUUID(),
                1,
                Instant.parse("2026-08-01T18:00:00Z"),
                "arena.legacy_1_8_9",
                1,
                1,
                Arrays.asList(
                        new LegacyParticipant(0, "A", playerA, playerA, minecraftA, "LegacyA"),
                        new LegacyParticipant(1, "B", playerB, playerB, minecraftB, "LegacyB")
                )
        );

        assertTrue(execution.containsMinecraftUuid(minecraftA));
        assertTrue(execution.hasSideId(playerA));
        assertTrue(execution.hasSideId(playerB));
        assertFalse(execution.hasSideId(UUID.randomUUID()));
        assertEquals(playerA, execution.sideIdForMinecraftUuid(minecraftA));
        assertEquals(playerB, execution.sideIdForMinecraftUuid(minecraftB));
        LegacyExecution renewed = execution.withLease(2, Instant.parse("2026-08-01T18:01:00Z"));
        assertEquals(1, execution.getStateVersion());
        assertEquals(2, renewed.getStateVersion());
        assertEquals(executionId, renewed.getExecutionId());
    }

    @Test
    void allParticipantsMustBePresentForExecutionReadiness() {
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        UUID minecraftA = UUID.randomUUID();
        UUID minecraftB = UUID.randomUUID();
        LegacyExecution execution = new LegacyExecution(
                UUID.randomUUID(),
                "RANKED_ARENA",
                UUID.randomUUID(),
                1,
                Instant.parse("2026-08-01T18:00:00Z"),
                "arena.legacy_1_8_9",
                1,
                1,
                Arrays.asList(
                        new LegacyParticipant(0, "A", playerA, playerA, minecraftA, "PresenceA"),
                        new LegacyParticipant(1, "B", playerB, playerB, minecraftB, "PresenceB")
                )
        );

        assertFalse(execution.allParticipantsOnline(Collections.<UUID>emptySet()));
        assertFalse(execution.allParticipantsOnline(Collections.singleton(minecraftA)));
        assertFalse(execution.allParticipantsOnline(Collections.singleton(minecraftB)));

        HashSet<UUID> bothPlayers = new HashSet<UUID>();
        bothPlayers.add(minecraftA);
        bothPlayers.add(minecraftB);
        assertTrue(execution.allParticipantsOnline(bothPlayers));

        Set<UUID> withUnrelatedPlayer = new HashSet<UUID>(bothPlayers);
        withUnrelatedPlayer.add(UUID.randomUUID());
        assertTrue(execution.allParticipantsOnline(withUnrelatedPlayer));
    }

    @Test
    void rejectsMalformedSanitizedManifest() {
        UUID player = UUID.randomUUID();
        UUID minecraft = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new LegacyExecution(
                UUID.randomUUID(),
                "RANKED_ARENA",
                UUID.randomUUID(),
                0,
                Instant.now(),
                "arena.legacy_1_8_9",
                1,
                1,
                Arrays.asList(new LegacyParticipant(0, "A", player, player, minecraft, "Legacy"))
        ));
    }
}

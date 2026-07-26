package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyRankedExecutionTest {
    @Test
    void acceptsFrozenV1RankedManifestAndResolvesOpponentAndWinnerSide() {
        LegacyParticipant playerA = participant(0, "A", "RankedA");
        LegacyParticipant playerB = participant(1, "B", "RankedB");
        LegacyExecution execution = execution(
                "RANKED_ARENA",
                "arena.legacy_1_8_9",
                1,
                playerA,
                playerB
        );

        LegacyRankedExecution ranked = LegacyRankedExecution.requireSupported(execution);

        assertSame(execution, ranked.getExecution());
        assertSame(playerA, ranked.getPlayerA());
        assertSame(playerB, ranked.getPlayerB());
        assertSame(playerB, ranked.opponent(playerA.getMinecraftUuid()));
        assertSame(playerA, ranked.opponent(playerB.getMinecraftUuid()));
        assertEquals(playerA.getPlayerId(), ranked.winnerSideId(playerA.getMinecraftUuid()));
        assertEquals(playerB.getPlayerId(), ranked.winnerSideId(playerB.getMinecraftUuid()));
    }

    @Test
    void rejectsNonRankedExecution() {
        LegacyParticipant playerA = participant(0, "A", "WrongKindA");
        LegacyParticipant playerB = participant(1, "B", "WrongKindB");
        LegacyExecution execution = execution(
                "CLAN_WAR",
                "arena.legacy_1_8_9",
                1,
                playerA,
                playerB
        );

        assertThrows(IllegalArgumentException.class, () -> LegacyRankedExecution.requireSupported(execution));
    }

    @Test
    void rejectsUnknownRulesetVersion() {
        LegacyParticipant playerA = participant(0, "A", "WrongRuleA");
        LegacyParticipant playerB = participant(1, "B", "WrongRuleB");
        LegacyExecution execution = execution(
                "RANKED_ARENA",
                "arena.legacy_1_8_9",
                2,
                playerA,
                playerB
        );

        assertThrows(IllegalArgumentException.class, () -> LegacyRankedExecution.requireSupported(execution));
    }

    @Test
    void rejectsMalformedSideIdentityEvenWhenManifestShapeIsOtherwiseValid() {
        UUID playerAId = UUID.randomUUID();
        LegacyParticipant playerA = new LegacyParticipant(
                0,
                "A",
                UUID.randomUUID(),
                playerAId,
                UUID.randomUUID(),
                "BadSideA"
        );
        LegacyParticipant playerB = participant(1, "B", "BadSideB");
        LegacyExecution execution = execution(
                "RANKED_ARENA",
                "arena.legacy_1_8_9",
                1,
                playerA,
                playerB
        );

        assertThrows(IllegalArgumentException.class, () -> LegacyRankedExecution.requireSupported(execution));
    }

    @Test
    void rejectsDuplicateRankedSide() {
        LegacyParticipant playerA = participant(0, "A", "DuplicateA");
        LegacyParticipant duplicateA = participant(1, "A", "DuplicateB");
        LegacyExecution execution = execution(
                "RANKED_ARENA",
                "arena.legacy_1_8_9",
                1,
                playerA,
                duplicateA
        );

        assertThrows(IllegalArgumentException.class, () -> LegacyRankedExecution.requireSupported(execution));
    }

    private static LegacyExecution execution(
            String activityKind,
            String rulesetId,
            int rulesetVersion,
            LegacyParticipant first,
            LegacyParticipant second
    ) {
        return new LegacyExecution(
                UUID.randomUUID(),
                activityKind,
                UUID.randomUUID(),
                1,
                Instant.parse("2026-08-01T18:00:00Z"),
                rulesetId,
                rulesetVersion,
                1,
                Arrays.asList(first, second)
        );
    }

    private static LegacyParticipant participant(int index, String side, String name) {
        UUID playerId = UUID.randomUUID();
        return new LegacyParticipant(
                index,
                side,
                playerId,
                playerId,
                UUID.randomUUID(),
                name
        );
    }
}

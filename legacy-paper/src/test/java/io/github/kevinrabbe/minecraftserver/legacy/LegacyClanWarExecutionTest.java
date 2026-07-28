package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyClanWarExecutionTest {
    @Test
    void validatesFrozenClanSidesWithoutHardcodingTeamSize() {
        UUID challengerClan = UUID.randomUUID();
        UUID defenderClan = UUID.randomUUID();
        LegacyExecution execution = execution(
                2,
                participant(0, "CHALLENGER", challengerClan),
                participant(1, "CHALLENGER", challengerClan),
                participant(2, "DEFENDER", defenderClan),
                participant(3, "DEFENDER", defenderClan)
        );

        LegacyClanWarExecution war = LegacyClanWarExecution.requireSupported(execution);
        assertEquals(execution, war.getExecution());
        assertEquals(challengerClan, war.getChallengerClanId());
        assertEquals(defenderClan, war.getDefenderClanId());
    }

    @Test
    void rejectsMalformedOrUnsupportedClanWarManifests() {
        UUID challengerClan = UUID.randomUUID();
        UUID defenderClan = UUID.randomUUID();

        LegacyExecution unsupportedRuleset = new LegacyExecution(
                UUID.randomUUID(),
                "CLAN_WAR",
                UUID.randomUUID(),
                1,
                Instant.parse("2026-08-01T18:00:00Z"),
                "war.unknown",
                1,
                1,
                Arrays.asList(
                        participant(0, "CHALLENGER", challengerClan),
                        participant(1, "DEFENDER", defenderClan)
                )
        );
        assertThrows(IllegalArgumentException.class, () -> LegacyClanWarExecution.requireSupported(unsupportedRuleset));

        LegacyExecution mixedChallengerClan = execution(
                2,
                participant(0, "CHALLENGER", challengerClan),
                participant(1, "CHALLENGER", UUID.randomUUID()),
                participant(2, "DEFENDER", defenderClan),
                participant(3, "DEFENDER", defenderClan)
        );
        assertThrows(IllegalArgumentException.class, () -> LegacyClanWarExecution.requireSupported(mixedChallengerClan));

        LegacyExecution wrongSideCount = execution(
                2,
                participant(0, "CHALLENGER", challengerClan),
                participant(1, "CHALLENGER", challengerClan),
                participant(2, "CHALLENGER", challengerClan),
                participant(3, "DEFENDER", defenderClan)
        );
        assertThrows(IllegalArgumentException.class, () -> LegacyClanWarExecution.requireSupported(wrongSideCount));

        LegacyExecution sameClanBothSides = execution(
                1,
                participant(0, "CHALLENGER", challengerClan),
                participant(1, "DEFENDER", challengerClan)
        );
        assertThrows(IllegalArgumentException.class, () -> LegacyClanWarExecution.requireSupported(sameClanBothSides));
    }

    private static LegacyExecution execution(int teamSize, LegacyParticipant... participants) {
        return new LegacyExecution(
                UUID.randomUUID(),
                "CLAN_WAR",
                UUID.randomUUID(),
                1,
                Instant.parse("2026-08-01T18:00:00Z"),
                "war.legacy_1_8_9",
                1,
                teamSize,
                Arrays.asList(participants)
        );
    }

    private static LegacyParticipant participant(int index, String sideKey, UUID clanId) {
        UUID playerId = UUID.randomUUID();
        return new LegacyParticipant(
                index,
                sideKey,
                clanId,
                playerId,
                UUID.randomUUID(),
                "War" + index
        );
    }
}

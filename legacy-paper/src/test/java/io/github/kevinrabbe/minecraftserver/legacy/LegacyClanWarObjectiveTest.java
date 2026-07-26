package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LegacyClanWarObjectiveTest {
    @Test
    void resolvesOnlyTheFrozenClanSideThatWinsControlProgress() {
        UUID challengerClan = UUID.randomUUID();
        UUID defenderClan = UUID.randomUUID();
        LegacyClanWarExecution war = war(challengerClan, defenderClan);
        LegacyClanWarObjective objective = new LegacyClanWarObjective(
                war,
                new LegacyClanWarObjectiveSettings(3.0D, 20, 2, 900)
        );

        assertNull(objective.evaluate(1, 0));
        assertNull(objective.evaluate(1, 1));
        assertEquals(challengerClan, objective.evaluate(2, 0));
        assertEquals(challengerClan, objective.evaluate(0, 10));
        assertEquals(2, objective.getChallengerProgress());
        assertEquals(0, objective.getDefenderProgress());
    }

    private static LegacyClanWarExecution war(UUID challengerClan, UUID defenderClan) {
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
        return new LegacyParticipant(index, side, clanId, UUID.randomUUID(), UUID.randomUUID(), "Objective" + index);
    }
}

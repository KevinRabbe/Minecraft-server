package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyExecutionLeasePolicyTest {
    @Test
    void rankedRenewsOnlyAfterMaterializationAndWhileBothPlayersRemainOnline() {
        LegacyExecution ranked = rankedExecution();
        LegacyCompetitiveCombatGate gate = new LegacyCompetitiveCombatGate();
        Set<UUID> online = minecraftUuids(ranked);

        assertFalse(LegacyExecutionLeasePolicy.shouldRenew(ranked, gate, online));
        gate.enable(ranked.getExecutionId());
        assertTrue(LegacyExecutionLeasePolicy.shouldRenew(ranked, gate, online));

        online.remove(ranked.getParticipants().get(0).getMinecraftUuid());
        assertFalse(LegacyExecutionLeasePolicy.shouldRenew(ranked, gate, online));
    }

    @Test
    void clanWarDoesNotRenewUntilARealCombatMaterializerOpensItsGate() {
        LegacyExecution war = clanWarExecution();
        LegacyCompetitiveCombatGate gate = new LegacyCompetitiveCombatGate();
        Set<UUID> online = minecraftUuids(war);

        assertFalse(LegacyExecutionLeasePolicy.shouldRenew(war, gate, online));
        gate.enable(war.getExecutionId());
        assertTrue(LegacyExecutionLeasePolicy.shouldRenew(war, gate, online));
    }

    private static LegacyExecution rankedExecution() {
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        return new LegacyExecution(
                UUID.randomUUID(),
                "RANKED_ARENA",
                UUID.randomUUID(),
                1,
                Instant.parse("2026-08-01T18:00:00Z"),
                "arena.legacy_1_8_9",
                1,
                1,
                Arrays.asList(
                        participant(0, "A", playerA, playerA),
                        participant(1, "B", playerB, playerB)
                )
        );
    }

    private static LegacyExecution clanWarExecution() {
        return new LegacyExecution(
                UUID.randomUUID(),
                "CLAN_WAR",
                UUID.randomUUID(),
                1,
                Instant.parse("2026-08-01T18:00:00Z"),
                "war.legacy_1_8_9",
                1,
                1,
                Arrays.asList(
                        participant(0, "CHALLENGER", UUID.randomUUID(), UUID.randomUUID()),
                        participant(1, "DEFENDER", UUID.randomUUID(), UUID.randomUUID())
                )
        );
    }

    private static LegacyParticipant participant(int index, String side, UUID sideId, UUID playerId) {
        return new LegacyParticipant(index, side, sideId, playerId, UUID.randomUUID(), "Lease" + index);
    }

    private static Set<UUID> minecraftUuids(LegacyExecution execution) {
        HashSet<UUID> result = new HashSet<UUID>();
        for (LegacyParticipant participant : execution.getParticipants()) {
            result.add(participant.getMinecraftUuid());
        }
        return result;
    }
}

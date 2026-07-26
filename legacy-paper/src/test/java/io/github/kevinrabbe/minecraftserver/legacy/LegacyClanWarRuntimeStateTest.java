package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyClanWarRuntimeStateTest {
    @Test
    void preparationKeepsFrozenIdentityFreeRepresentationAndObjectiveTogether() {
        UUID challengerClan = UUID.randomUUID();
        UUID defenderClan = UUID.randomUUID();
        LegacyClanWarExecution war = war(challengerClan, defenderClan);
        LegacyClanWarLoadout loadout = LegacyClanWarLoadout.requireValid(
                war,
                Collections.singletonList(
                        new LegacyLoadoutItem(0, 0, "equipment.starter_sword", "{}", 0)
                )
        );
        LinkedHashMap<String, String> configured = new LinkedHashMap<String, String>();
        configured.put("equipment.starter_sword", "IRON_SWORD");
        LegacyClanWarObjectiveSettings settings = new LegacyClanWarObjectiveSettings(3.0D, 20, 1, 900);

        LegacyClanWarRuntimeState state = LegacyClanWarRuntimeState.prepare(
                war,
                loadout,
                new LegacyClanWarRepresentationCatalog(configured),
                settings
        );

        assertEquals(war, state.getWar());
        assertEquals(1, state.getRepresentationPlan().getItems().size());
        assertEquals("IRON_SWORD", state.getRepresentationPlan().getItems().get(0).getMaterialId());
        assertEquals(challengerClan, state.getObjective().evaluate(1, 0));
        assertEquals(900, state.getObjectiveSettings().getMatchTimeoutSeconds());
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
                        new LegacyParticipant(
                                0, "CHALLENGER", challengerClan, UUID.randomUUID(), UUID.randomUUID(), "StateA"
                        ),
                        new LegacyParticipant(
                                1, "DEFENDER", defenderClan, UUID.randomUUID(), UUID.randomUUID(), "StateB"
                        )
                )
        );
        return LegacyClanWarExecution.requireSupported(execution);
    }
}

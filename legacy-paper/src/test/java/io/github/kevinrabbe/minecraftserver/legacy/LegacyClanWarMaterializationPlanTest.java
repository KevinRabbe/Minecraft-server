package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyClanWarMaterializationPlanTest {
    @Test
    void coversExactFrozenRosterEvenWhenSomePlayersSelectedNoItems() {
        LegacyClanWarExecution war = war(2);
        LegacyClanWarLoadout loadout = LegacyClanWarLoadout.requireValid(
                war,
                Arrays.asList(
                        new LegacyLoadoutItem(0, 0, "equipment.starter_sword", "{}", 0),
                        new LegacyLoadoutItem(2, 0, "equipment.starter_sword", "{}", 0)
                )
        );

        LegacyClanWarMaterializationPlan plan = LegacyClanWarMaterializationPlan.build(
                war,
                loadout,
                catalog(),
                arena()
        );

        assertEquals(war, plan.getWar());
        assertEquals(2, plan.getRepresentationPlan().getItems().size());
        assertEquals(4, plan.getInventoryProjection().getItemsByMinecraftUuid().size());
        assertEquals(4, plan.getSpawnLayout().size());
        for (LegacyParticipant participant : war.getExecution().getParticipants()) {
            UUID minecraftUuid = participant.getMinecraftUuid();
            assertTrue(plan.getInventoryProjection().getItemsByMinecraftUuid().containsKey(minecraftUuid));
            assertTrue(plan.getSpawnLayout().containsKey(minecraftUuid));
        }
    }

    @Test
    void refusesInventoryOverflowBeforeAnyWorldMutationCanBegin() {
        LegacyClanWarExecution war = war(1);
        ArrayList<LegacyLoadoutItem> rows = new ArrayList<LegacyLoadoutItem>();
        for (int index = 0; index < 37; index++) {
            rows.add(new LegacyLoadoutItem(0, index, "equipment.starter_sword", "{}", 0));
        }
        LegacyClanWarLoadout loadout = LegacyClanWarLoadout.requireValid(war, rows);

        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyClanWarMaterializationPlan.build(war, loadout, catalog(), arena())
        );
    }

    private static LegacyClanWarRepresentationCatalog catalog() {
        Map<String, String> configured = new LinkedHashMap<String, String>();
        configured.put("equipment.starter_sword", "IRON_SWORD");
        return new LegacyClanWarRepresentationCatalog(configured);
    }

    private static LegacyClanWarArenaSettings arena() {
        return new LegacyClanWarArenaSettings(
                128, 200, 0, 24, 4, 16, 2.0D,
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
                    "MaterializeC" + index
            ));
        }
        for (int index = 0; index < teamSize; index++) {
            participants.add(new LegacyParticipant(
                    teamSize + index,
                    "DEFENDER",
                    defenderClan,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "MaterializeD" + index
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

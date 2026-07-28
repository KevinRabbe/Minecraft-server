package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyClanWarLoadoutLoaderTest {
    @Test
    void pagesBeyondTransportBoundWithoutCreatingKitCap() throws Exception {
        LegacyClanWarExecution war = supportedWar();
        ArrayList<LegacyLoadoutItem> sourceItems = new ArrayList<LegacyLoadoutItem>();
        for (int index = 0; index < 129; index++) {
            sourceItems.add(new LegacyLoadoutItem(0, index, "war.item_" + index, "{}", 0));
        }
        AtomicInteger calls = new AtomicInteger();

        LegacyClanWarLoadout loadout = LegacyClanWarLoadoutLoader.load(
                war,
                (executionId, afterParticipant, afterItem, limit) -> {
                    calls.incrementAndGet();
                    assertEquals(war.getExecution().getExecutionId(), executionId);
                    assertEquals(128, limit);
                    if (afterParticipant == null) {
                        return new ArrayList<LegacyLoadoutItem>(sourceItems.subList(0, 128));
                    }
                    if (afterParticipant == 0 && afterItem == 127) {
                        return Collections.singletonList(sourceItems.get(128));
                    }
                    return Collections.emptyList();
                }
        );

        assertEquals(129, loadout.getItems().size());
        assertEquals(2, calls.get());
    }

    @Test
    void acceptsExplicitlyEmptyFinalSelection() throws Exception {
        LegacyClanWarLoadout loadout = LegacyClanWarLoadoutLoader.load(
                supportedWar(),
                (executionId, afterParticipant, afterItem, limit) -> Collections.emptyList()
        );
        assertTrue(loadout.getItems().isEmpty());
    }

    @Test
    void rejectsNonAdvancingOrOversizedPages() {
        LegacyClanWarExecution war = supportedWar();
        List<LegacyLoadoutItem> fullPage = new ArrayList<LegacyLoadoutItem>();
        for (int index = 0; index < 128; index++) {
            fullPage.add(new LegacyLoadoutItem(0, index, "war.item_" + index, "{}", 0));
        }

        assertThrows(
                SQLException.class,
                () -> LegacyClanWarLoadoutLoader.load(
                        war,
                        (executionId, afterParticipant, afterItem, limit) -> fullPage
                )
        );

        ArrayList<LegacyLoadoutItem> oversized = new ArrayList<LegacyLoadoutItem>(fullPage);
        oversized.add(new LegacyLoadoutItem(0, 128, "war.item_128", "{}", 0));
        assertThrows(
                SQLException.class,
                () -> LegacyClanWarLoadoutLoader.load(
                        war,
                        (executionId, afterParticipant, afterItem, limit) -> oversized
                )
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
        return new LegacyParticipant(index, side, clanId, playerId, UUID.randomUUID(), "Pager" + index);
    }
}

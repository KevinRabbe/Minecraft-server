package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyExecutionAdmissionTest {
    @Test
    void rankedAdmissionDoesNotTouchClanWarLoadoutApi() throws Exception {
        LegacyExecution ranked = rankedExecution();
        AtomicInteger pageCalls = new AtomicInteger();

        LegacyClanWarLoadout loadout = LegacyExecutionAdmission.prepare(
                ranked,
                (executionId, afterParticipant, afterItem, limit) -> {
                    pageCalls.incrementAndGet();
                    throw new AssertionError("Ranked admission must not query Clan-War loadout state");
                }
        );

        assertNull(loadout);
        assertEquals(0, pageCalls.get());
    }

    @Test
    void clanWarAdmissionRequiresCompleteValidatedSnapshot() throws Exception {
        LegacyExecution war = clanWarExecution();
        AtomicInteger pageCalls = new AtomicInteger();

        LegacyClanWarLoadout loadout = LegacyExecutionAdmission.prepare(
                war,
                (executionId, afterParticipant, afterItem, limit) -> {
                    pageCalls.incrementAndGet();
                    assertEquals(war.getExecutionId(), executionId);
                    return Collections.singletonList(
                            new LegacyLoadoutItem(0, 0, "war.snapshot_sword", "{}", 0)
                    );
                }
        );

        assertEquals(1, pageCalls.get());
        assertEquals(1, loadout.getItems().size());
    }

    @Test
    void clanWarAdmissionCanRequireFaithfulLegacyRepresentation() throws Exception {
        LegacyExecution war = clanWarExecution();
        LinkedHashMap<String, String> configured = new LinkedHashMap<String, String>();
        configured.put("equipment.starter_sword", "IRON_SWORD");
        LegacyClanWarRepresentationCatalog catalog = new LegacyClanWarRepresentationCatalog(configured);

        LegacyClanWarLoadout loadout = LegacyExecutionAdmission.prepare(
                war,
                (executionId, afterParticipant, afterItem, limit) -> Collections.singletonList(
                        new LegacyLoadoutItem(0, 0, "equipment.starter_sword", "{}", 0)
                ),
                catalog
        );
        assertEquals(1, loadout.getItems().size());

        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyExecutionAdmission.prepare(
                        war,
                        (executionId, afterParticipant, afterItem, limit) -> Collections.singletonList(
                                new LegacyLoadoutItem(0, 0, "equipment.starter_sword", "{\"damage\":9000}", 0)
                        ),
                        catalog
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyExecutionAdmission.prepare(
                        war,
                        (executionId, afterParticipant, afterItem, limit) -> Collections.singletonList(
                                new LegacyLoadoutItem(0, 0, "equipment.unknown", "{}", 0)
                        ),
                        catalog
                )
        );
    }

    @Test
    void clanWarAdmissionFailsClosedWhenSnapshotReadFails() {
        LegacyExecution war = clanWarExecution();

        assertThrows(
                SQLException.class,
                () -> LegacyExecutionAdmission.prepare(
                        war,
                        (executionId, afterParticipant, afterItem, limit) -> {
                            throw new SQLException("sealed snapshot unavailable");
                        }
                )
        );
    }

    @Test
    void unsupportedActivityCannotBeAdmitted() {
        LegacyExecution unsupported = new LegacyExecution(
                UUID.randomUUID(),
                "UNKNOWN",
                UUID.randomUUID(),
                1,
                Instant.parse("2026-08-01T18:00:00Z"),
                "unknown",
                1,
                1,
                Arrays.asList(
                        participant(0, "A", UUID.randomUUID()),
                        participant(1, "B", UUID.randomUUID())
                )
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> LegacyExecutionAdmission.prepare(
                        unsupported,
                        (executionId, afterParticipant, afterItem, limit) -> Collections.emptyList()
                )
        );
        assertTrue(exception.getMessage().contains("Unsupported competitive activity kind"));
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
        UUID challengerClan = UUID.randomUUID();
        UUID defenderClan = UUID.randomUUID();
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
                        participant(0, "CHALLENGER", challengerClan),
                        participant(1, "DEFENDER", defenderClan)
                )
        );
    }

    private static LegacyParticipant participant(int index, String side, UUID sideId) {
        return participant(index, side, sideId, UUID.randomUUID());
    }

    private static LegacyParticipant participant(int index, String side, UUID sideId, UUID playerId) {
        return new LegacyParticipant(index, side, sideId, playerId, UUID.randomUUID(), "Admit" + index);
    }
}

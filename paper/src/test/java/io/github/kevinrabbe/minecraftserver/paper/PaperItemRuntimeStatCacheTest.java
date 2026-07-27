package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemLocation;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRuntimeStatSnapshot;
import io.github.kevinrabbe.minecraftserver.common.item.UpgradeState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperItemRuntimeStatCacheTest {
    private UUID player;

    @AfterEach
    void clearCache() {
        PaperItemRuntimeStatCache.clear(player);
    }

    @Test
    void cacheLookupRequiresExactStableIdentityDefinitionAndAuthorityVersion() {
        player = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ItemRuntimeStatSnapshot snapshot = snapshot(itemId, playerId, 4, 11_000);

        PaperItemRuntimeStatCache.replaceNow(player, Map.of(itemId, snapshot));

        assertEquals(
                snapshot,
                PaperItemRuntimeStatCache.find(player, itemId, "equipment.test", 4).orElseThrow()
        );
        assertTrue(PaperItemRuntimeStatCache.find(player, itemId, "equipment.other", 4).isEmpty());
        assertTrue(PaperItemRuntimeStatCache.find(player, itemId, "equipment.test", 3).isEmpty());
        assertTrue(PaperItemRuntimeStatCache.find(player, UUID.randomUUID(), "equipment.test", 4).isEmpty());
    }

    @Test
    void olderRefreshCannotOverwriteOrInvalidateNewerGeneration() {
        player = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        long oldGeneration = PaperItemRuntimeStatCache.beginRefresh(player);
        long newGeneration = PaperItemRuntimeStatCache.beginRefresh(player);
        ItemRuntimeStatSnapshot oldSnapshot = snapshot(itemId, playerId, 1, 10_500);
        ItemRuntimeStatSnapshot newSnapshot = snapshot(itemId, playerId, 2, 11_500);

        PaperItemRuntimeStatCache.replaceIfCurrent(player, newGeneration, Map.of(itemId, newSnapshot));
        PaperItemRuntimeStatCache.replaceIfCurrent(player, oldGeneration, Map.of(itemId, oldSnapshot));
        assertFalse(PaperItemRuntimeStatCache.invalidateIfCurrent(player, oldGeneration));

        assertEquals(
                newSnapshot,
                PaperItemRuntimeStatCache.find(player, itemId, "equipment.test", 2).orElseThrow()
        );
        assertTrue(PaperItemRuntimeStatCache.find(player, itemId, "equipment.test", 1).isEmpty());
        assertTrue(PaperItemRuntimeStatCache.invalidateIfCurrent(player, newGeneration));
        assertTrue(PaperItemRuntimeStatCache.find(player, itemId, "equipment.test", 2).isEmpty());
    }

    private static ItemRuntimeStatSnapshot snapshot(
            UUID itemId,
            UUID playerId,
            long version,
            int damageMultiplier
    ) {
        return new ItemRuntimeStatSnapshot(
                itemId,
                "equipment.test",
                ItemLocation.playerInventory(playerId),
                version,
                Map.of("damage", 5_000),
                Map.of("damage", damageMultiplier),
                UpgradeState.NONE
        );
    }
}

package io.github.kevinrabbe.minecraftserver.common.economy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuctionBrowseListingTest {
    @Test
    void projectionUsesTechnicalUpgradeBoundRatherThanInventingGameplayCap() {
        assertDoesNotThrow(() -> listing(101));
        assertDoesNotThrow(() -> listing(10_000));
        assertThrows(IllegalArgumentException.class, () -> listing(10_001));
    }

    private static AuctionBrowseListing listing(int upgradeLevel) {
        return new AuctionBrowseListing(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "equipment.test",
                1,
                Map.of(),
                upgradeLevel,
                Instant.EPOCH
        );
    }
}

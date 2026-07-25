package io.github.kevinrabbe.minecraftserver.common.economy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BankTierCatalogLoaderTest {
    @Test
    void bundledCatalogLoadsContiguousProvisionalTiers() {
        BankTierCatalog catalog = new BankTierCatalogLoader().loadResource("/content/bank-tiers.json");

        assertEquals(2, catalog.maxTier());
        assertEquals(100_000L, catalog.require(0).capacityMinor());
        assertEquals(1_000_000L, catalog.require(1).capacityMinor());
        assertEquals(10_000_000L, catalog.require(2).capacityMinor());
        assertEquals(30, catalog.require(2).dailyInterestBasisPoints());
    }

    @Test
    void unknownFieldsAndNoncontiguousTiersFailClosed() {
        BankTierCatalogLoader loader = new BankTierCatalogLoader();
        String unknownField = """
                {
                  "schema_version": 1,
                  "tiers": [
                    {
                      "tier": 0,
                      "capacity_minor": 100,
                      "upgrade_cost_minor": 0,
                      "daily_interest_basis_points": 0,
                      "unexpected": true
                    }
                  ]
                }
                """;
        assertThrows(
                BankManagerException.class,
                () -> loader.load(stream(unknownField), "unknown-field-test")
        );

        String gap = """
                {
                  "schema_version": 1,
                  "tiers": [
                    {
                      "tier": 0,
                      "capacity_minor": 100,
                      "upgrade_cost_minor": 0,
                      "daily_interest_basis_points": 0
                    },
                    {
                      "tier": 2,
                      "capacity_minor": 1000,
                      "upgrade_cost_minor": 100,
                      "daily_interest_basis_points": 10
                    }
                  ]
                }
                """;
        assertThrows(
                IllegalArgumentException.class,
                () -> loader.load(stream(gap), "gap-test")
        );
    }

    private static ByteArrayInputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}

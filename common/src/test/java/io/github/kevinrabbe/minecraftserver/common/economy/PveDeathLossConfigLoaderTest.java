package io.github.kevinrabbe.minecraftserver.common.economy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PveDeathLossConfigLoaderTest {
    @Test
    void bundledConfigEnablesFivePercentOnlyForLockedCombatRegions() {
        PveDeathLossConfig config = new PveDeathLossConfigLoader().loadResource("/content/pve-death-loss.json");

        assertTrue(config.enabled());
        assertEquals("combat-regions-v1", config.policyVersion());
        assertEquals(500, config.lossBasisPoints());
        assertEquals(List.of("ashbound", "rootborn", "veilborn"), config.zoneIds());
        assertTrue(config.appliesToZone("rootborn"));
        assertTrue(config.appliesToZone("ashbound"));
        assertTrue(config.appliesToZone("veilborn"));
        assertFalse(config.appliesToZone("city"));
        assertFalse(config.appliesToZone("starter_pve"));
        assertFalse(config.appliesToZone("map_encounter"));
        assertEquals(50L, config.lossMinor(1_000L));
        assertEquals(0L, config.lossMinor(1L));
    }

    @Test
    void enabledProportionalPolicyUsesExactFloorWithoutOverflow() {
        PveDeathLossConfig config = new PveDeathLossConfig(
                true,
                "test-v1",
                250,
                List.of("combat")
        );

        assertEquals(25L, config.lossMinor(1_000L));
        assertEquals(0L, config.lossMinor(1L));
        long balance = Long.MAX_VALUE;
        long expected = (balance / 10_000L) * 250L + ((balance % 10_000L) * 250L) / 10_000L;
        assertEquals(expected, config.lossMinor(balance));
    }

    @Test
    void invalidOrAmbiguousConfigFailsClosed() {
        PveDeathLossConfigLoader loader = new PveDeathLossConfigLoader();
        String unknown = """
                {
                  "schema_version": 2,
                  "enabled": false,
                  "policy_version": "disabled-v1",
                  "loss_basis_points": 0,
                  "zone_ids": [],
                  "unexpected": true
                }
                """;
        assertThrows(CoinWalletException.class, () -> loader.load(stream(unknown), "unknown-test"));

        String hiddenActiveValue = """
                {
                  "schema_version": 2,
                  "enabled": false,
                  "policy_version": "disabled-v1",
                  "loss_basis_points": 100,
                  "zone_ids": []
                }
                """;
        assertThrows(CoinWalletException.class, () -> loader.load(stream(hiddenActiveValue), "disabled-test"));

        String enabledWithoutZones = """
                {
                  "schema_version": 2,
                  "enabled": true,
                  "policy_version": "bad-v1",
                  "loss_basis_points": 500,
                  "zone_ids": []
                }
                """;
        assertThrows(CoinWalletException.class, () -> loader.load(stream(enabledWithoutZones), "zones-test"));

        String outOfBounds = """
                {
                  "schema_version": 2,
                  "enabled": true,
                  "policy_version": "bad-v1",
                  "loss_basis_points": 10001,
                  "zone_ids": ["rootborn"]
                }
                """;
        assertThrows(CoinWalletException.class, () -> loader.load(stream(outOfBounds), "bounds-test"));

        String legacySchema = """
                {
                  "schema_version": 1,
                  "enabled": false,
                  "policy_version": "disabled-v1",
                  "loss_basis_points": 0
                }
                """;
        assertThrows(CoinWalletException.class, () -> loader.load(stream(legacySchema), "legacy-test"));
    }

    private static ByteArrayInputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}

package io.github.kevinrabbe.minecraftserver.common.economy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PveDeathLossConfigLoaderTest {
    @Test
    void bundledConfigIsDeliberatelyDisabled() {
        PveDeathLossConfig config = new PveDeathLossConfigLoader().loadResource("/content/pve-death-loss.json");

        assertFalse(config.enabled());
        assertEquals("disabled-v1", config.policyVersion());
        assertEquals(0, config.lossBasisPoints());
        assertEquals(0L, config.lossMinor(123_456L));
    }

    @Test
    void enabledProportionalPolicyUsesExactFloorWithoutOverflow() {
        PveDeathLossConfig config = new PveDeathLossConfig(true, "test-v1", 250);

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
                  "schema_version": 1,
                  "enabled": false,
                  "policy_version": "disabled-v1",
                  "loss_basis_points": 0,
                  "unexpected": true
                }
                """;
        assertThrows(CoinWalletException.class, () -> loader.load(stream(unknown), "unknown-test"));

        String hiddenActiveValue = """
                {
                  "schema_version": 1,
                  "enabled": false,
                  "policy_version": "disabled-v1",
                  "loss_basis_points": 100
                }
                """;
        assertThrows(CoinWalletException.class, () -> loader.load(stream(hiddenActiveValue), "disabled-test"));

        String outOfBounds = """
                {
                  "schema_version": 1,
                  "enabled": true,
                  "policy_version": "bad-v1",
                  "loss_basis_points": 10001
                }
                """;
        assertThrows(CoinWalletException.class, () -> loader.load(stream(outOfBounds), "bounds-test"));
    }

    private static ByteArrayInputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}

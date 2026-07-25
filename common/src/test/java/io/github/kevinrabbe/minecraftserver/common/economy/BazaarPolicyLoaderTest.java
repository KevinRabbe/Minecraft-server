package io.github.kevinrabbe.minecraftserver.common.economy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BazaarPolicyLoaderTest {
    @Test
    void bundledPolicyLoadsProvisionalExecutionValues() {
        BazaarPolicy policy = new BazaarPolicyLoader().loadResource("/content/bazaar-policy.json");
        assertEquals(25, policy.executionFeeBasisPoints());
        assertEquals(100, policy.maxFillsPerMatch());
    }

    @Test
    void unknownFieldsAndInvalidBoundsFailClosed() {
        BazaarPolicyLoader loader = new BazaarPolicyLoader();
        String unknown = """
                {
                  "schema_version": 1,
                  "execution_fee_basis_points": 25,
                  "max_fills_per_match": 100,
                  "unexpected": true
                }
                """;
        assertThrows(BazaarException.class, () -> loader.load(stream(unknown), "unknown-test"));

        String invalid = """
                {
                  "schema_version": 1,
                  "execution_fee_basis_points": 10001,
                  "max_fills_per_match": 0
                }
                """;
        assertThrows(BazaarException.class, () -> loader.load(stream(invalid), "invalid-test"));
    }

    private static ByteArrayInputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}

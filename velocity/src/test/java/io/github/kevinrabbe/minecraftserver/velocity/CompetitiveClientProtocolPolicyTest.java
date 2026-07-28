package io.github.kevinrabbe.minecraftserver.velocity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompetitiveClientProtocolPolicyTest {
    @Test
    void acceptsOnlyTheLegacyCompetitiveProtocolFamily() {
        assertTrue(CompetitiveClientProtocolPolicy.accepts(47));
        assertFalse(CompetitiveClientProtocolPolicy.accepts(5));
        assertFalse(CompetitiveClientProtocolPolicy.accepts(107));
        assertFalse(CompetitiveClientProtocolPolicy.accepts(765));
    }
}

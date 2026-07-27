package io.github.kevinrabbe.minecraftserver.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevToolsPolicyTest {
    @Test
    void developmentToolsAreDisabledUnlessExplicitlyTrue() {
        assertFalse(DevToolsPolicy.enabled(null));
        assertFalse(DevToolsPolicy.enabled(""));
        assertFalse(DevToolsPolicy.enabled("false"));
        assertFalse(DevToolsPolicy.enabled("1"));
        assertFalse(DevToolsPolicy.enabled("yes"));
        assertTrue(DevToolsPolicy.enabled("true"));
        assertTrue(DevToolsPolicy.enabled(" TRUE "));
    }
}

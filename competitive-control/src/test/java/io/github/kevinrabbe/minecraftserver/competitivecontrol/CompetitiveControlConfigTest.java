package io.github.kevinrabbe.minecraftserver.competitivecontrol;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompetitiveControlConfigTest {
    @Test
    void defaultsAreBoundedAndConservative() {
        CompetitiveControlConfig config = CompetitiveControlConfig.fromEnvironment(Map.of());

        assertEquals(Duration.ofSeconds(30), config.backendFreshness());
        assertEquals(Duration.ofMinutes(5), config.maxExecutionLease());
        assertEquals(Duration.ofSeconds(60), config.executionLease());
        assertEquals(50, config.batchLimit());
        assertEquals(Duration.ofSeconds(1), config.pollPeriod());
    }

    @Test
    void explicitEnvironmentOverridesAreParsed() {
        CompetitiveControlConfig config = CompetitiveControlConfig.fromEnvironment(Map.of(
                "COMPETITIVE_CONTROL_BACKEND_FRESHNESS_SECONDS", "45",
                "COMPETITIVE_CONTROL_MAX_EXECUTION_LEASE_SECONDS", "180",
                "COMPETITIVE_CONTROL_EXECUTION_LEASE_SECONDS", "90",
                "COMPETITIVE_CONTROL_BATCH_LIMIT", "25",
                "COMPETITIVE_CONTROL_POLL_PERIOD_MILLIS", "750"
        ));

        assertEquals(Duration.ofSeconds(45), config.backendFreshness());
        assertEquals(Duration.ofSeconds(180), config.maxExecutionLease());
        assertEquals(Duration.ofSeconds(90), config.executionLease());
        assertEquals(25, config.batchLimit());
        assertEquals(Duration.ofMillis(750), config.pollPeriod());
    }

    @Test
    void unsafeOperationalBoundsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CompetitiveControlConfig.fromEnvironment(Map.of(
                "COMPETITIVE_CONTROL_BATCH_LIMIT", "501"
        )));
        assertThrows(IllegalArgumentException.class, () -> CompetitiveControlConfig.fromEnvironment(Map.of(
                "COMPETITIVE_CONTROL_POLL_PERIOD_MILLIS", "99"
        )));
        assertThrows(IllegalArgumentException.class, () -> CompetitiveControlConfig.fromEnvironment(Map.of(
                "COMPETITIVE_CONTROL_MAX_EXECUTION_LEASE_SECONDS", "3601"
        )));
        assertThrows(IllegalArgumentException.class, () -> CompetitiveControlConfig.fromEnvironment(Map.of(
                "COMPETITIVE_CONTROL_MAX_EXECUTION_LEASE_SECONDS", "30",
                "COMPETITIVE_CONTROL_EXECUTION_LEASE_SECONDS", "60"
        )));
    }
}

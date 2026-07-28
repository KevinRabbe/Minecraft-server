package io.github.kevinrabbe.minecraftserver.common.world;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/** Persistent logical feature accessibility, independent from runtime backend activation. */
public record FeatureState(
        String featureId,
        FeatureAccessibility accessibility,
        UUID sourceOperationId,
        Instant changedAt,
        long stateVersion
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public FeatureState {
        if (featureId == null || featureId.isBlank()) {
            throw new IllegalArgumentException("featureId must not be blank");
        }
        featureId = featureId.trim();
        if (!ID.matcher(featureId).matches()) {
            throw new IllegalArgumentException("featureId has invalid format: " + featureId);
        }
        if (accessibility == null) {
            throw new NullPointerException("accessibility");
        }
        if (changedAt == null) {
            throw new NullPointerException("changedAt");
        }
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion must be >= 0");
        }
        if (accessibility == FeatureAccessibility.AVAILABLE && sourceOperationId == null) {
            throw new IllegalArgumentException("AVAILABLE feature state requires sourceOperationId");
        }
    }
}

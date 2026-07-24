package io.github.kevinrabbe.minecraftserver.common.world;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One valid player-facing expansion option. It describes capability actions, never a physical district blueprint.
 */
public record ExpansionCandidate(
        String candidateId,
        String displayName,
        List<String> featureIds,
        WorldEraId resultingWorldEraId
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public ExpansionCandidate {
        candidateId = requireId(candidateId, "candidateId");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        displayName = displayName.trim();
        if (displayName.length() > 128) {
            throw new IllegalArgumentException("displayName must be <= 128 characters");
        }
        Objects.requireNonNull(featureIds, "featureIds");
        if (featureIds.isEmpty()) {
            throw new IllegalArgumentException("featureIds must not be empty");
        }
        featureIds = featureIds.stream()
                .map(featureId -> requireId(featureId, "featureId"))
                .distinct()
                .toList();
    }

    private static String requireId(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        String normalized = value.trim();
        if (!ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(fieldName + " has invalid format: " + normalized);
        }
        return normalized;
    }
}

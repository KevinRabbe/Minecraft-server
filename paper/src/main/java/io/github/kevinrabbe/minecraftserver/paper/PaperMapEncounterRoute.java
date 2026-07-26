package io.github.kevinrabbe.minecraftserver.paper;

import java.util.regex.Pattern;

/** Version-controlled Paper routing target for one persistent Map environment ID. */
record PaperMapEncounterRoute(
        String environmentId,
        String zoneId,
        String templateVersion
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");

    PaperMapEncounterRoute {
        environmentId = requireId(environmentId, "environmentId");
        zoneId = requireId(zoneId, "zoneId");
        if (templateVersion == null || templateVersion.isBlank()) {
            throw new IllegalArgumentException("templateVersion must not be blank");
        }
        templateVersion = templateVersion.trim();
        if (templateVersion.length() > 96) {
            throw new IllegalArgumentException("templateVersion must not exceed 96 characters");
        }
    }

    private static String requireId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (!ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " has invalid format: " + normalized);
        }
        return normalized;
    }
}

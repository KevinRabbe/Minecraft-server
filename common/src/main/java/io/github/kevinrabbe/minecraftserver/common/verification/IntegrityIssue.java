package io.github.kevinrabbe.minecraftserver.common.verification;

import java.util.Objects;
import java.util.regex.Pattern;

/** Infrastructure-neutral result emitted by integrity verification jobs/commands. */
public record IntegrityIssue(
        IntegritySeverity severity,
        String code,
        String subjectId,
        String message
) {
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,95}");

    public IntegrityIssue {
        severity = Objects.requireNonNull(severity, "severity");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        code = code.trim();
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("code has invalid format: " + code);
        }
        subjectId = normalizeOptional(subjectId);
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        message = message.trim();
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}

package io.github.kevinrabbe.minecraftserver.common.history;

import io.github.kevinrabbe.minecraftserver.common.world.WorldEraId;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Immutable request to append one authoritative Chronicle event. */
public record ChronicleEventRequest(
        String eventType,
        String sourceKind,
        String sourceId,
        WorldEraId worldEraId,
        Instant occurredAt,
        Map<String, String> metadata
) {
    private static final Pattern UPPER_ID = Pattern.compile("[A-Z][A-Z0-9_]{0,95}");
    private static final Pattern METADATA_KEY = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final int MAX_SOURCE_ID_LENGTH = 256;
    private static final int MAX_METADATA_ENTRIES = 32;
    private static final int MAX_METADATA_VALUE_LENGTH = 512;

    public ChronicleEventRequest {
        eventType = requireUpperId(eventType, "eventType");
        sourceKind = requireUpperId(sourceKind, "sourceKind");
        sourceId = requireSourceId(sourceId);
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        metadata = canonicalMetadata(metadata);
    }

    private static String requireUpperId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (!UPPER_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be an uppercase stable identifier: " + normalized);
        }
        return normalized;
    }

    private static String requireSourceId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_SOURCE_ID_LENGTH) {
            throw new IllegalArgumentException("sourceId must be <= " + MAX_SOURCE_ID_LENGTH + " characters");
        }
        return normalized;
    }

    private static Map<String, String> canonicalMetadata(Map<String, String> values) {
        Objects.requireNonNull(values, "metadata");
        if (values.size() > MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException("metadata must contain <= " + MAX_METADATA_ENTRIES + " entries");
        }
        TreeMap<String, String> canonical = new TreeMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || !METADATA_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("metadata key is not a stable identifier: " + key);
            }
            if (value == null) {
                throw new IllegalArgumentException("metadata value must not be null for key " + key);
            }
            if (value.length() > MAX_METADATA_VALUE_LENGTH) {
                throw new IllegalArgumentException(
                        "metadata value must be <= " + MAX_METADATA_VALUE_LENGTH + " characters for key " + key
                );
            }
            canonical.put(key, value);
        }
        return Map.copyOf(canonical);
    }
}

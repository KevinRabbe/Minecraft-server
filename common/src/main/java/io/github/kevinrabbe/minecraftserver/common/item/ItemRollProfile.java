package io.github.kevinrabbe.minecraftserver.common.item;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Immutable type-level rolled-property configuration for an individualized item definition. */
public record ItemRollProfile(Map<String, RollRange> properties) {
    private static final Pattern PROPERTY_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    public static final ItemRollProfile NONE = new ItemRollProfile(Map.of());

    public ItemRollProfile {
        Objects.requireNonNull(properties, "properties");
        TreeMap<String, RollRange> normalized = new TreeMap<>();
        properties.forEach((propertyId, range) -> {
            String id = requirePropertyId(propertyId);
            RollRange previous = normalized.put(id, Objects.requireNonNull(range, "roll range"));
            if (previous != null) {
                throw new IllegalArgumentException("duplicate roll property: " + id);
            }
        });
        properties = Map.copyOf(normalized);
    }

    public boolean rolled() {
        return !properties.isEmpty();
    }

    private static String requirePropertyId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("roll property id must not be blank");
        }
        String normalized = value.trim();
        if (!PROPERTY_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("roll property id has invalid format: " + normalized);
        }
        return normalized;
    }
}

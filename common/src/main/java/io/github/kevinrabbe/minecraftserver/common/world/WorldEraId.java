package io.github.kevinrabbe.minecraftserver.common.world;

import java.util.regex.Pattern;

/** Stable historical era identifier, e.g. founding, nether, end. */
public record WorldEraId(String value) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public WorldEraId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("world era id must not be blank");
        }
        value = value.trim();
        if (!ID.matcher(value).matches()) {
            throw new IllegalArgumentException("world era id has invalid format: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}

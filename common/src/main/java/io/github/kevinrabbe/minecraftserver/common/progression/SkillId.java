package io.github.kevinrabbe.minecraftserver.common.progression;

import java.util.regex.Pattern;

/** Stable content identifier for one skill/progression track. */
public record SkillId(String value) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public SkillId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("skill id must not be blank");
        }
        value = value.trim();
        if (!ID.matcher(value).matches()) {
            throw new IllegalArgumentException("skill id has invalid format: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}

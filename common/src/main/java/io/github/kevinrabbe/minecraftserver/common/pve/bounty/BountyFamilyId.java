package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import java.util.regex.Pattern;

/** Stable mob-category bounty family identifier, e.g. spider, zombie, golem. */
public record BountyFamilyId(String value) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public BountyFamilyId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("bounty family id must not be blank");
        }
        value = value.trim();
        if (!ID.matcher(value).matches()) {
            throw new IllegalArgumentException("bounty family id has invalid format: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}

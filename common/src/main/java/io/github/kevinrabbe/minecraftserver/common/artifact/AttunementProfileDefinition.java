package io.github.kevinrabbe.minecraftserver.common.artifact;

import java.util.Objects;
import java.util.regex.Pattern;

/** Data/config identity for one selectable attunement specialization. Exact stat conversion remains tuning. */
public record AttunementProfileDefinition(
        String profileId,
        String statKey
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public AttunementProfileDefinition {
        profileId = Objects.requireNonNull(profileId, "profileId").trim();
        statKey = Objects.requireNonNull(statKey, "statKey").trim();
        if (!ID.matcher(profileId).matches()) {
            throw new IllegalArgumentException("profileId must be a stable lowercase identifier");
        }
        if (!ID.matcher(statKey).matches()) {
            throw new IllegalArgumentException("statKey must be a stable lowercase identifier");
        }
    }
}

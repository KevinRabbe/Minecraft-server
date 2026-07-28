package io.github.kevinrabbe.minecraftserver.common.clan;

import java.time.Instant;
import java.util.Objects;

/** Current shared clan balance policy. Exact values are tuning rather than persistent clan identity. */
public record ClanPolicySnapshot(
        int memberCap,
        Instant updatedAt
) {
    public ClanPolicySnapshot {
        if (memberCap < 1 || memberCap > 10000) {
            throw new IllegalArgumentException("memberCap must be between 1 and 10000");
        }
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}

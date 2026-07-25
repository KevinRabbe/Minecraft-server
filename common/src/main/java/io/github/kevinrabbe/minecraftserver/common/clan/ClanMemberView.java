package io.github.kevinrabbe.minecraftserver.common.clan;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Player-facing clan roster row; playerId remains identity and playerName is only the current display projection. */
public record ClanMemberView(
        UUID playerId,
        String playerName,
        ClanRole role,
        Instant joinedAt
) {
    public ClanMemberView {
        playerId = Objects.requireNonNull(playerId, "playerId");
        playerName = requirePlayerName(playerName);
        role = Objects.requireNonNull(role, "role");
        joinedAt = Objects.requireNonNull(joinedAt, "joinedAt");
    }

    private static String requirePlayerName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("playerName must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > 16) {
            throw new IllegalArgumentException("playerName must not exceed 16 characters");
        }
        return normalized;
    }
}

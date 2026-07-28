package io.github.kevinrabbe.minecraftserver.common.clan;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Player-facing pending-invitation row with stable identities plus current name projections. */
public record ClanInvitationView(
        UUID inviteId,
        UUID invitedPlayerId,
        String invitedPlayerName,
        UUID invitedByPlayerId,
        String invitedByPlayerName,
        Instant createdAt,
        Instant expiresAt
) {
    public ClanInvitationView {
        inviteId = Objects.requireNonNull(inviteId, "inviteId");
        invitedPlayerId = Objects.requireNonNull(invitedPlayerId, "invitedPlayerId");
        invitedPlayerName = requirePlayerName(invitedPlayerName, "invitedPlayerName");
        invitedByPlayerId = Objects.requireNonNull(invitedByPlayerId, "invitedByPlayerId");
        invitedByPlayerName = requirePlayerName(invitedByPlayerName, "invitedByPlayerName");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }

    private static String requirePlayerName(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > 16) {
            throw new IllegalArgumentException(field + " must not exceed 16 characters");
        }
        return normalized;
    }
}

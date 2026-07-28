package io.github.kevinrabbe.minecraftserver.common.clan;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Authoritative clan invitation snapshot. */
public record ClanInvitationSnapshot(
        UUID inviteId,
        UUID clanId,
        UUID invitedPlayerId,
        UUID invitedByPlayerId,
        ClanInvitationStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant acceptedAt,
        Instant closedAt
) {
    public ClanInvitationSnapshot {
        inviteId = Objects.requireNonNull(inviteId, "inviteId");
        clanId = Objects.requireNonNull(clanId, "clanId");
        invitedPlayerId = Objects.requireNonNull(invitedPlayerId, "invitedPlayerId");
        invitedByPlayerId = Objects.requireNonNull(invitedByPlayerId, "invitedByPlayerId");
        status = Objects.requireNonNull(status, "status");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        if ((status == ClanInvitationStatus.ACCEPTED) != (acceptedAt != null)) {
            throw new IllegalArgumentException("acceptedAt must match ACCEPTED status");
        }
        if ((status == ClanInvitationStatus.PENDING) != (closedAt == null)) {
            throw new IllegalArgumentException("closedAt must be null only while PENDING");
        }
    }
}

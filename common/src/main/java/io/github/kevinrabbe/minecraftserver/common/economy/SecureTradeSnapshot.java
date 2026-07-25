package io.github.kevinrabbe.minecraftserver.common.economy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Authoritative secure-trade lifecycle snapshot; offer assets live in dedicated escrow tables. */
public record SecureTradeSnapshot(
        UUID tradeId,
        UUID playerAId,
        UUID playerBId,
        SecureTradeStatus status,
        long revision,
        Long playerAConfirmedRevision,
        Long playerBConfirmedRevision,
        Instant createdAt,
        Instant updatedAt,
        Instant settledAt
) {
    public SecureTradeSnapshot {
        tradeId = Objects.requireNonNull(tradeId, "tradeId");
        playerAId = Objects.requireNonNull(playerAId, "playerAId");
        playerBId = Objects.requireNonNull(playerBId, "playerBId");
        status = Objects.requireNonNull(status, "status");
        if (playerAId.equals(playerBId)) {
            throw new IllegalArgumentException("secure trade requires two distinct players");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
        requireConfirmationRevision(playerAConfirmedRevision, revision, "playerAConfirmedRevision");
        requireConfirmationRevision(playerBConfirmedRevision, revision, "playerBConfirmedRevision");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (status == SecureTradeStatus.LOCKED || status == SecureTradeStatus.SETTLED) {
            if (!Long.valueOf(revision).equals(playerAConfirmedRevision)
                    || !Long.valueOf(revision).equals(playerBConfirmedRevision)) {
                throw new IllegalArgumentException("locked/settled trade requires both confirmations on current revision");
            }
        }
        if ((status == SecureTradeStatus.SETTLED || status == SecureTradeStatus.CANCELLED) != (settledAt != null)) {
            throw new IllegalArgumentException("terminal trade status/timestamp shape is invalid");
        }
    }

    public boolean participant(UUID playerId) {
        return playerAId.equals(playerId) || playerBId.equals(playerId);
    }

    public UUID otherParticipant(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (playerAId.equals(playerId)) {
            return playerBId;
        }
        if (playerBId.equals(playerId)) {
            return playerAId;
        }
        throw new IllegalArgumentException("player is not a secure-trade participant: " + playerId);
    }

    private static void requireConfirmationRevision(Long value, long revision, String field) {
        if (value != null && (value < 0 || value > revision)) {
            throw new IllegalArgumentException(field + " must be between 0 and current revision");
        }
    }
}

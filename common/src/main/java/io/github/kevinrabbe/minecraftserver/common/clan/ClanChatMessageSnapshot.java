package io.github.kevinrabbe.minecraftserver.common.clan;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable server-published clan chat transit message. */
public record ClanChatMessageSnapshot(
        long sequence,
        UUID messageId,
        UUID clanId,
        UUID senderPlayerId,
        String senderName,
        String body,
        Instant createdAt
) {
    public ClanChatMessageSnapshot {
        if (sequence < 1) throw new IllegalArgumentException("sequence must be >= 1");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(clanId, "clanId");
        Objects.requireNonNull(senderPlayerId, "senderPlayerId");
        senderName = requireText(senderName, "senderName");
        body = requireText(body, "body");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

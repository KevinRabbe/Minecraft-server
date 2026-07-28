package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.Objects;
import java.util.UUID;

final class LegacyParticipant {
    private final int participantIndex;
    private final String sideKey;
    private final UUID sideId;
    private final UUID playerId;
    private final UUID minecraftUuid;
    private final String playerName;

    LegacyParticipant(
            int participantIndex,
            String sideKey,
            UUID sideId,
            UUID playerId,
            UUID minecraftUuid,
            String playerName
    ) {
        if (participantIndex < 0) {
            throw new IllegalArgumentException("participantIndex must be >= 0");
        }
        this.participantIndex = participantIndex;
        this.sideKey = requireText(sideKey, "sideKey");
        this.sideId = Objects.requireNonNull(sideId, "sideId");
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.minecraftUuid = Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        this.playerName = requireText(playerName, "playerName");
        if (this.playerName.length() > 16) {
            throw new IllegalArgumentException("playerName must not exceed 16 characters");
        }
    }

    int getParticipantIndex() {
        return participantIndex;
    }

    String getSideKey() {
        return sideKey;
    }

    UUID getSideId() {
        return sideId;
    }

    UUID getPlayerId() {
        return playerId;
    }

    UUID getMinecraftUuid() {
        return minecraftUuid;
    }

    String getPlayerName() {
        return playerName;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}

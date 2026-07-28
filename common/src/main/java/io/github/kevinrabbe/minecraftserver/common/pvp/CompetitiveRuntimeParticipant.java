package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Runtime-safe participant identity; deliberately contains no persistent inventory/custody data. */
public record CompetitiveRuntimeParticipant(
        int participantIndex,
        String sideKey,
        UUID sideId,
        UUID playerId,
        UUID minecraftUuid,
        String playerName
) {
    private static final Set<String> SIDES = Set.of("A", "B", "CHALLENGER", "DEFENDER");

    public CompetitiveRuntimeParticipant {
        if (participantIndex < 0) {
            throw new IllegalArgumentException("participantIndex must be >= 0");
        }
        if (sideKey == null || !SIDES.contains(sideKey)) {
            throw new IllegalArgumentException("invalid sideKey: " + sideKey);
        }
        sideId = Objects.requireNonNull(sideId, "sideId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        minecraftUuid = Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        if (playerName == null || playerName.isBlank() || playerName.length() > 16) {
            throw new IllegalArgumentException("playerName must be 1..16 characters");
        }
        playerName = playerName.trim();
    }
}

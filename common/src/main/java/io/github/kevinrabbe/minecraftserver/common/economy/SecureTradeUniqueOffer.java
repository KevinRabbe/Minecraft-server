package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One participant-owned individualized item in a secure-trade offer snapshot. */
public record SecureTradeUniqueOffer(
        UUID ownerPlayerId,
        UUID itemInstanceId,
        long escrowItemVersion,
        String definitionId,
        Map<String, Integer> rollQualityBasisPoints
) {
    public SecureTradeUniqueOffer {
        ownerPlayerId = Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        itemInstanceId = Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        if (escrowItemVersion < 0) {
            throw new IllegalArgumentException("escrowItemVersion must be >= 0");
        }
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        definitionId = definitionId.trim();
        rollQualityBasisPoints = Map.copyOf(new LinkedHashMap<>(
                Objects.requireNonNull(rollQualityBasisPoints, "rollQualityBasisPoints")
        ));
        for (Map.Entry<String, Integer> entry : rollQualityBasisPoints.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null
                    || entry.getValue() < 0 || entry.getValue() > 10_000) {
                throw new IllegalArgumentException("invalid unique-item roll projection");
            }
        }
    }
}

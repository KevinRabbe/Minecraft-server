package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;
import java.util.UUID;

public record SecureTradeUniqueItemOfferResult(
        SecureTradeSnapshot trade,
        UUID playerId,
        UUID itemInstanceId,
        long escrowItemVersion,
        long playerStateVersion
) {
    public SecureTradeUniqueItemOfferResult {
        trade = Objects.requireNonNull(trade, "trade");
        playerId = Objects.requireNonNull(playerId, "playerId");
        itemInstanceId = Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        if (!trade.participant(playerId)) {
            throw new IllegalArgumentException("playerId must be a trade participant");
        }
        if (escrowItemVersion < 0 || playerStateVersion < 0) {
            throw new IllegalArgumentException("state versions must be >= 0");
        }
    }
}

package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;
import java.util.UUID;

public record SecureTradeCommodityOfferResult(
        SecureTradeSnapshot trade,
        UUID playerId,
        String commodityDefinitionId,
        long escrowQuantity,
        long playerStateVersion
) {
    public SecureTradeCommodityOfferResult {
        trade = Objects.requireNonNull(trade, "trade");
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (!trade.participant(playerId)) {
            throw new IllegalArgumentException("playerId must be a trade participant");
        }
        if (commodityDefinitionId == null || commodityDefinitionId.isBlank()) {
            throw new IllegalArgumentException("commodityDefinitionId must not be blank");
        }
        if (escrowQuantity <= 0 || playerStateVersion < 0) {
            throw new IllegalArgumentException("commodity escrow quantity must be > 0 and state version >= 0");
        }
    }
}

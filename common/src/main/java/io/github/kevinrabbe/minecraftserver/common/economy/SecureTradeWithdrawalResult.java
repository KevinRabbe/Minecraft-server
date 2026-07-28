package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;

/** One OPEN-trade offer withdrawal returned through durable pending delivery. */
public record SecureTradeWithdrawalResult(
        SecureTradeSnapshot trade,
        SecureTradeDeliverySnapshot delivery
) {
    public SecureTradeWithdrawalResult {
        trade = Objects.requireNonNull(trade, "trade");
        delivery = Objects.requireNonNull(delivery, "delivery");
        if (trade.status() != SecureTradeStatus.OPEN) {
            throw new IllegalArgumentException("withdrawal result requires OPEN trade");
        }
        if (!trade.tradeId().equals(delivery.tradeId())) {
            throw new IllegalArgumentException("delivery belongs to another trade");
        }
        if (!delivery.sourceOwnerPlayerId().equals(delivery.recipientPlayerId())) {
            throw new IllegalArgumentException("withdrawal delivery must return to original owner");
        }
    }
}

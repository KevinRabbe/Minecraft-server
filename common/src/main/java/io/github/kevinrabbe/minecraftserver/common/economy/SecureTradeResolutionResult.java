package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Terminal secure-trade settlement/cancellation result with durable delivery evidence. */
public record SecureTradeResolutionResult(
        SecureTradeSnapshot trade,
        Map<UUID, Long> walletBalancesMinor,
        List<SecureTradeDeliverySnapshot> deliveries
) {
    public SecureTradeResolutionResult {
        trade = Objects.requireNonNull(trade, "trade");
        if (trade.status() != SecureTradeStatus.SETTLED && trade.status() != SecureTradeStatus.CANCELLED) {
            throw new IllegalArgumentException("trade must be terminal");
        }
        walletBalancesMinor = Map.copyOf(Objects.requireNonNull(walletBalancesMinor, "walletBalancesMinor"));
        deliveries = List.copyOf(Objects.requireNonNull(deliveries, "deliveries"));
        for (Map.Entry<UUID, Long> balance : walletBalancesMinor.entrySet()) {
            if (!trade.participant(balance.getKey()) || balance.getValue() < 0) {
                throw new IllegalArgumentException("wallet result contains invalid participant/balance");
            }
        }
        for (SecureTradeDeliverySnapshot delivery : deliveries) {
            if (!delivery.tradeId().equals(trade.tradeId())) {
                throw new IllegalArgumentException("delivery belongs to a different trade");
            }
        }
    }
}

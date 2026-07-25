package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Complete bounded offer snapshot that a player may safely bind a confirmation to. */
public record SecureTradeOfferView(
        SecureTradeSnapshot trade,
        Map<UUID, Long> coinOffersMinor,
        List<SecureTradeCommodityOffer> commodityOffers,
        List<SecureTradeUniqueOffer> uniqueOffers
) {
    public SecureTradeOfferView {
        trade = Objects.requireNonNull(trade, "trade");
        coinOffersMinor = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(coinOffersMinor, "coinOffersMinor")));
        commodityOffers = List.copyOf(Objects.requireNonNull(commodityOffers, "commodityOffers"));
        uniqueOffers = List.copyOf(Objects.requireNonNull(uniqueOffers, "uniqueOffers"));

        for (Map.Entry<UUID, Long> entry : coinOffersMinor.entrySet()) {
            if (!trade.participant(entry.getKey()) || entry.getValue() == null || entry.getValue() <= 0) {
                throw new IllegalArgumentException("coin offer contains invalid participant or amount");
            }
        }
        for (SecureTradeCommodityOffer offer : commodityOffers) {
            if (!trade.participant(offer.ownerPlayerId())) {
                throw new IllegalArgumentException("commodity offer owner is not a trade participant");
            }
        }
        for (SecureTradeUniqueOffer offer : uniqueOffers) {
            if (!trade.participant(offer.ownerPlayerId())) {
                throw new IllegalArgumentException("unique offer owner is not a trade participant");
            }
        }
    }
}

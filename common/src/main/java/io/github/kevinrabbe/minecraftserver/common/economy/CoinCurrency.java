package io.github.kevinrabbe.minecraftserver.common.economy;

/** Network currency constants. Persistence and arithmetic always use integer minor units. */
public final class CoinCurrency {
    public static final long MINOR_UNITS_PER_COIN = 100L;
    public static final String LEDGER_ASSET_TYPE = "CURRENCY";
    public static final String LEDGER_ASSET_ID = "coin";

    private CoinCurrency() {
    }
}

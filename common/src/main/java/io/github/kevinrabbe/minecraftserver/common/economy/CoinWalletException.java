package io.github.kevinrabbe.minecraftserver.common.economy;

public final class CoinWalletException extends RuntimeException {
    public CoinWalletException(String message) {
        super(message);
    }

    public CoinWalletException(String message, Throwable cause) {
        super(message, cause);
    }
}

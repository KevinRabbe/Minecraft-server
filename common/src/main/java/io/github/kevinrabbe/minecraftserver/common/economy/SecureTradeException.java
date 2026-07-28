package io.github.kevinrabbe.minecraftserver.common.economy;

/** Secure direct-trade invariant violation. */
public final class SecureTradeException extends RuntimeException {
    public SecureTradeException(String message) {
        super(message);
    }

    public SecureTradeException(String message, Throwable cause) {
        super(message, cause);
    }
}

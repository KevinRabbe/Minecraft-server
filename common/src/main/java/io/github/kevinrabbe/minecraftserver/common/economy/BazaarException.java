package io.github.kevinrabbe.minecraftserver.common.economy;

public final class BazaarException extends RuntimeException {
    public BazaarException(String message) {
        super(message);
    }

    public BazaarException(String message, Throwable cause) {
        super(message, cause);
    }
}

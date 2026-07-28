package io.github.kevinrabbe.minecraftserver.common.economy;

public final class BankManagerException extends RuntimeException {
    public BankManagerException(String message) {
        super(message);
    }

    public BankManagerException(String message, Throwable cause) {
        super(message, cause);
    }
}

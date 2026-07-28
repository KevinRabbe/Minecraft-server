package io.github.kevinrabbe.minecraftserver.common.economy;

public final class SalvageException extends RuntimeException {
    public SalvageException(String message) {
        super(message);
    }

    public SalvageException(String message, Throwable cause) {
        super(message, cause);
    }
}

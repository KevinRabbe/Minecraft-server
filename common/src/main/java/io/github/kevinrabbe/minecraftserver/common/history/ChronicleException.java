package io.github.kevinrabbe.minecraftserver.common.history;

/** Persistent Chronicle/history invariant violation. */
public final class ChronicleException extends RuntimeException {
    public ChronicleException(String message) {
        super(message);
    }

    public ChronicleException(String message, Throwable cause) {
        super(message, cause);
    }
}

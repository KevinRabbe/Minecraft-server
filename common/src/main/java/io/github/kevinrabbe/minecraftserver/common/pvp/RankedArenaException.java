package io.github.kevinrabbe.minecraftserver.common.pvp;

public final class RankedArenaException extends RuntimeException {
    public RankedArenaException(String message) {
        super(message);
    }

    public RankedArenaException(String message, Throwable cause) {
        super(message, cause);
    }
}

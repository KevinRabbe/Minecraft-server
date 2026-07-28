package io.github.kevinrabbe.minecraftserver.common.pvp;

public final class ClanWarException extends RuntimeException {
    public ClanWarException(String message) {
        super(message);
    }

    public ClanWarException(String message, Throwable cause) {
        super(message, cause);
    }
}

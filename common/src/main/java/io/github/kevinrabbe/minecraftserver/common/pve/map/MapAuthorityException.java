package io.github.kevinrabbe.minecraftserver.common.pve.map;

/** Persistent Map item/run authority invariant violation. */
public final class MapAuthorityException extends RuntimeException {
    public MapAuthorityException(String message) {
        super(message);
    }

    public MapAuthorityException(String message, Throwable cause) {
        super(message, cause);
    }
}

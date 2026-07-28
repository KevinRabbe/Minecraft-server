package io.github.kevinrabbe.minecraftserver.common.world.resource;

public final class ResourceSourceException extends RuntimeException {
    public ResourceSourceException(String message) {
        super(message);
    }

    public ResourceSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}

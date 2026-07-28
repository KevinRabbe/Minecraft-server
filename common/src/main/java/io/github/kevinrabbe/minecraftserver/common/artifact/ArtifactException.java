package io.github.kevinrabbe.minecraftserver.common.artifact;

public final class ArtifactException extends RuntimeException {
    public ArtifactException(String message) {
        super(message);
    }

    public ArtifactException(String message, Throwable cause) {
        super(message, cause);
    }
}

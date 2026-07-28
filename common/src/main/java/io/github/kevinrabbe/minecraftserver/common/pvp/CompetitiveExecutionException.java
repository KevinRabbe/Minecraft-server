package io.github.kevinrabbe.minecraftserver.common.pvp;

public final class CompetitiveExecutionException extends RuntimeException {
    public CompetitiveExecutionException(String message) {
        super(message);
    }

    public CompetitiveExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}

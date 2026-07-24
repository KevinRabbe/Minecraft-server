package io.github.kevinrabbe.minecraftserver.common.item;

public final class UniqueItemAuthorityException extends RuntimeException {
    public UniqueItemAuthorityException(String message) {
        super(message);
    }

    public UniqueItemAuthorityException(String message, Throwable cause) {
        super(message, cause);
    }
}

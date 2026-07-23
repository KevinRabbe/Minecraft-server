package io.github.kevinrabbe.minecraftserver.common.session;

public final class SessionConflictException extends IllegalStateException {
    public SessionConflictException(String message) {
        super(message);
    }
}

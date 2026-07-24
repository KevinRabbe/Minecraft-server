package io.github.kevinrabbe.minecraftserver.common.world;

public class ExpansionVoteException extends RuntimeException {
    public ExpansionVoteException(String message) {
        super(message);
    }

    public ExpansionVoteException(String message, Throwable cause) {
        super(message, cause);
    }
}

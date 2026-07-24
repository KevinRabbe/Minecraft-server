package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

public final class BountyException extends RuntimeException {
    public BountyException(String message) {
        super(message);
    }

    public BountyException(String message, Throwable cause) {
        super(message, cause);
    }
}

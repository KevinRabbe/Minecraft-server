package io.github.kevinrabbe.minecraftserver.common.clan;

public final class ClanMembershipException extends RuntimeException {
    public ClanMembershipException(String message) {
        super(message);
    }

    public ClanMembershipException(String message, Throwable cause) {
        super(message, cause);
    }
}

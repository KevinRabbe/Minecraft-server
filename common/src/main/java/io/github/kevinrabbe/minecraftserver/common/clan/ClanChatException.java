package io.github.kevinrabbe.minecraftserver.common.clan;

/** Domain rejection for clan-chat authorization, replay binding, or bounded input. */
public final class ClanChatException extends RuntimeException {
    public ClanChatException(String message) {
        super(message);
    }
}

package io.github.kevinrabbe.minecraftserver.common.clan;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One transit message plus the currently leased Minecraft recipients on a specific backend. */
public record ClanChatDelivery(
        ClanChatMessageSnapshot message,
        List<UUID> recipientMinecraftUuids
) {
    public ClanChatDelivery {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(recipientMinecraftUuids, "recipientMinecraftUuids");
        recipientMinecraftUuids = List.copyOf(recipientMinecraftUuids);
    }
}

package io.github.kevinrabbe.minecraftserver.common.item;

import java.util.UUID;

/** Adapter-owned proof that one exact authority-versioned unique item disappeared and nothing else changed. */
@FunctionalInterface
public interface UniqueItemStateRemovalValidator {
    void verifyRemoval(
            UUID playerId,
            UUID itemInstanceId,
            long expectedItemStateVersion,
            byte[] currentStatePayload,
            byte[] nextStatePayload
    );
}

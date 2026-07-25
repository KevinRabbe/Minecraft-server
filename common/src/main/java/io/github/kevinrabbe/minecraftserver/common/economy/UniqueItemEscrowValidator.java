package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.UUID;

/** Adapter-owned proof that one exact unique item representation was removed from serialized player state. */
@FunctionalInterface
public interface UniqueItemEscrowValidator {
    void verifyRemoval(
            UUID playerId,
            UUID itemInstanceId,
            byte[] currentStatePayload,
            byte[] nextStatePayload
    );
}

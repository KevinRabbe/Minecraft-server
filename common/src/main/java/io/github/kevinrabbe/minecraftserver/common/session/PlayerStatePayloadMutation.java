package io.github.kevinrabbe.minecraftserver.common.session;

import java.util.UUID;

/**
 * Builds one sensitive next player-state payload from the transactionally locked current payload.
 * Implementations must be deterministic for the same inputs and must not mutate the supplied byte array.
 */
@FunctionalInterface
public interface PlayerStatePayloadMutation {
    byte[] apply(UUID playerId, byte[] currentStatePayload);
}

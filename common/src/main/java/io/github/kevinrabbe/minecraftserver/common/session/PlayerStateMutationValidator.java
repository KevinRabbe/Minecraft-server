package io.github.kevinrabbe.minecraftserver.common.session;

import java.util.UUID;

/**
 * Validates a sensitive serialized player-state transition after the session/state rows are locked but before commit.
 * Implementations must be deterministic and must not mutate the supplied payload arrays.
 */
@FunctionalInterface
public interface PlayerStateMutationValidator {
    void validate(UUID playerId, byte[] currentStatePayload, byte[] nextStatePayload);
}

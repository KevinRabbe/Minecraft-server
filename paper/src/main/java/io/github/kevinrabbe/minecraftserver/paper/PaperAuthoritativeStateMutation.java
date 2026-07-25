package io.github.kevinrabbe.minecraftserver.paper;

import java.util.Objects;
import java.util.UUID;

/** Feature-neutral transaction callback executed only after the live Bukkit state has been checkpointed and frozen. */
@FunctionalInterface
interface PaperAuthoritativeStateMutation {
    Result apply(Context context) throws Exception;

    record Context(
            UUID sessionId,
            UUID playerId,
            String backendId,
            long stateVersion,
            String logicalZoneId,
            String entryPoint,
            byte[] currentStatePayload
    ) {
        public Context {
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            playerId = Objects.requireNonNull(playerId, "playerId");
            if (backendId == null || backendId.isBlank()) {
                throw new IllegalArgumentException("backendId must not be blank");
            }
            backendId = backendId.trim();
            if (stateVersion < 0) {
                throw new IllegalArgumentException("stateVersion must be >= 0");
            }
            currentStatePayload = currentStatePayload == null ? null : currentStatePayload.clone();
        }

        @Override
        public byte[] currentStatePayload() {
            return currentStatePayload == null ? null : currentStatePayload.clone();
        }
    }

    record Result(long stateVersion, byte[] statePayload) {
        public Result {
            if (stateVersion < 0) {
                throw new IllegalArgumentException("stateVersion must be >= 0");
            }
            statePayload = Objects.requireNonNull(statePayload, "statePayload").clone();
        }

        @Override
        public byte[] statePayload() {
            return statePayload.clone();
        }
    }
}

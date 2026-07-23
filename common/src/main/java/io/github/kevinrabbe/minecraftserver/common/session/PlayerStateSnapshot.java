package io.github.kevinrabbe.minecraftserver.common.session;

import java.util.UUID;

public record PlayerStateSnapshot(
        UUID playerId,
        long stateVersion,
        String logicalZoneId,
        String entryPoint,
        byte[] statePayload
) {
    public PlayerStateSnapshot {
        statePayload = statePayload == null ? null : statePayload.clone();
    }

    @Override
    public byte[] statePayload() {
        return statePayload == null ? null : statePayload.clone();
    }
}

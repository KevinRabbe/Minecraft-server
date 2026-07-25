package io.github.kevinrabbe.minecraftserver.common.item;

import java.util.Arrays;
import java.util.Objects;

/** Atomic unique-delivery claim result plus the exact serialized state committed by that claim. */
public record PendingUniqueDeliveryMaterializationResult(
        PendingUniqueDeliveryClaimResult claim,
        byte[] statePayload
) {
    public PendingUniqueDeliveryMaterializationResult {
        claim = Objects.requireNonNull(claim, "claim");
        statePayload = Arrays.copyOf(Objects.requireNonNull(statePayload, "statePayload"), statePayload.length);
    }

    @Override
    public byte[] statePayload() {
        return Arrays.copyOf(statePayload, statePayload.length);
    }
}

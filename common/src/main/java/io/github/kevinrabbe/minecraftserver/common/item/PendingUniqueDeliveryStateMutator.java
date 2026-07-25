package io.github.kevinrabbe.minecraftserver.common.item;

import java.util.UUID;

/**
 * Adapter-owned deterministic mutation that adds one projected post-claim unique item to serialized player state.
 * The supplied item carries the exact authority version/location that will exist if the claim transaction commits.
 */
@FunctionalInterface
public interface PendingUniqueDeliveryStateMutator {
    byte[] add(
            UUID playerId,
            UniqueItemInstance projectedItem,
            byte[] currentStatePayload
    );
}

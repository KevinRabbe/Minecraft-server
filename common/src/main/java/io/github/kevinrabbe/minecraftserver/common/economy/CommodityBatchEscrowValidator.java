package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Map;
import java.util.UUID;

/** Adapter-owned proof that one exact set of fungible quantities was removed from serialized player state. */
@FunctionalInterface
public interface CommodityBatchEscrowValidator {
    void verifyRemoval(
            UUID playerId,
            Map<String, Long> commodityQuantities,
            byte[] currentStatePayload,
            byte[] nextStatePayload
    );
}

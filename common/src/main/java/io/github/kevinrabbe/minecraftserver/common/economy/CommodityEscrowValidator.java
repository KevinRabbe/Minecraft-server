package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.UUID;

/**
 * Adapter boundary that proves a sensitive serialized player-state transition removed exactly the requested commodity.
 * The Paper implementation understands Minecraft inventory/NBT representation; the common Bazaar repository does not.
 */
@FunctionalInterface
public interface CommodityEscrowValidator {
    void verifyRemoval(
            UUID playerId,
            String commodityDefinitionId,
            long quantity,
            byte[] currentStatePayload,
            byte[] nextStatePayload
    );
}

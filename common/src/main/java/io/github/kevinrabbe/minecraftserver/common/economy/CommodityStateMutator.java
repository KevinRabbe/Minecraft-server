package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Arrays;
import java.util.UUID;

/**
 * Adapter boundary for deterministic commodity mutations inside serialized player state.
 *
 * <p>The same implementation builds the candidate payload and proves it inside the fenced persistence transaction,
 * so a caller cannot use Bazaar/delivery operations to smuggle unrelated inventory changes.</p>
 */
public interface CommodityStateMutator extends CommodityEscrowValidator {
    byte[] remove(
            UUID playerId,
            String commodityDefinitionId,
            long quantity,
            byte[] currentStatePayload
    );

    byte[] add(
            UUID playerId,
            String commodityDefinitionId,
            long quantity,
            byte[] currentStatePayload
    );

    @Override
    default void verifyRemoval(
            UUID playerId,
            String commodityDefinitionId,
            long quantity,
            byte[] currentStatePayload,
            byte[] nextStatePayload
    ) {
        byte[] expected = remove(
                playerId,
                commodityDefinitionId,
                quantity,
                currentStatePayload == null ? null : currentStatePayload.clone()
        );
        if (!Arrays.equals(expected, nextStatePayload)) {
            throw new BazaarException("Serialized player state does not match exact commodity removal");
        }
    }

    default void verifyAddition(
            UUID playerId,
            String commodityDefinitionId,
            long quantity,
            byte[] currentStatePayload,
            byte[] nextStatePayload
    ) {
        byte[] expected = add(
                playerId,
                commodityDefinitionId,
                quantity,
                currentStatePayload == null ? null : currentStatePayload.clone()
        );
        if (!Arrays.equals(expected, nextStatePayload)) {
            throw new BazaarException("Serialized player state does not match exact commodity addition");
        }
    }
}

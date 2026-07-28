package io.github.kevinrabbe.minecraftserver.common.crafting;

import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * Feature-neutral high-level personal-craft execution contract.
 *
 * <p>PlayerStateRepository commits advance state_version exactly once. Personal crafting always performs one such
 * commit inside the craft transaction, so a successful craft deterministically commits expected+1. Overflow fails
 * before delegating, matching the underlying player-state authority bound.</p>
 */
public final class CraftingStateExecutionService {
    private final CraftingRepository crafting;

    public CraftingStateExecutionService(CraftingRepository crafting) {
        this.crafting = Objects.requireNonNull(crafting, "crafting");
    }

    public CraftingStateExecutionResult craftFromPlayerState(
            UUID operationId,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            String recipeId,
            int recipeVersion,
            String logicalZoneId,
            String entryPoint,
            byte[] nextPlayerStatePayload,
            String reason
    ) throws SQLException {
        long committedVersion;
        try {
            committedVersion = Math.addExact(expectedPlayerStateVersion, 1L);
        } catch (ArithmeticException exception) {
            throw new CraftingException("player state_version overflow during craft", exception);
        }
        CraftExecutionResult craft = crafting.craftFromPlayerState(
                operationId,
                sessionId,
                backendId,
                expectedPlayerStateVersion,
                recipeId,
                recipeVersion,
                logicalZoneId,
                entryPoint,
                nextPlayerStatePayload,
                reason
        );
        return new CraftingStateExecutionResult(craft, committedVersion);
    }
}

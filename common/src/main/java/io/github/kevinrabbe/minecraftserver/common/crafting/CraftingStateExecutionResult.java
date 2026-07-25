package io.github.kevinrabbe.minecraftserver.common.crafting;

import java.util.Objects;

/** High-level personal-craft result including the exact fenced player-state version committed by the craft. */
public record CraftingStateExecutionResult(
        CraftExecutionResult craft,
        long playerStateVersion
) {
    public CraftingStateExecutionResult {
        craft = Objects.requireNonNull(craft, "craft");
        if (playerStateVersion < 0) {
            throw new IllegalArgumentException("playerStateVersion must be >= 0");
        }
    }
}

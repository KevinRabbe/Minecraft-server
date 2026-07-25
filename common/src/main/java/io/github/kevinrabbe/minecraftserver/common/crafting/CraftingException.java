package io.github.kevinrabbe.minecraftserver.common.crafting;

public final class CraftingException extends RuntimeException {
    public CraftingException(String message) {
        super(message);
    }

    public CraftingException(String message, Throwable cause) {
        super(message, cause);
    }
}

package io.github.kevinrabbe.minecraftserver.common.economy;

public final class CraftingCommissionException extends RuntimeException {
    public CraftingCommissionException(String message) {
        super(message);
    }

    public CraftingCommissionException(String message, Throwable cause) {
        super(message, cause);
    }
}

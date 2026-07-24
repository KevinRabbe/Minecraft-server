package io.github.kevinrabbe.minecraftserver.common.item;

public final class ItemCatalogException extends RuntimeException {
    public ItemCatalogException(String message) {
        super(message);
    }

    public ItemCatalogException(String message, Throwable cause) {
        super(message, cause);
    }
}

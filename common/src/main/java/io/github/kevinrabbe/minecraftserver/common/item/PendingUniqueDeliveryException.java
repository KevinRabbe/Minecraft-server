package io.github.kevinrabbe.minecraftserver.common.item;

public final class PendingUniqueDeliveryException extends RuntimeException {
    public PendingUniqueDeliveryException(String message) {
        super(message);
    }

    public PendingUniqueDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}

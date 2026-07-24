package io.github.kevinrabbe.minecraftserver.common.item;

public final class PendingCommodityDeliveryException extends RuntimeException {
    public PendingCommodityDeliveryException(String message) {
        super(message);
    }

    public PendingCommodityDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}

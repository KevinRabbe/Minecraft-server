package io.github.kevinrabbe.minecraftserver.common.economy;

public final class AuctionHouseException extends RuntimeException {
    public AuctionHouseException(String message) {
        super(message);
    }

    public AuctionHouseException(String message, Throwable cause) {
        super(message, cause);
    }
}

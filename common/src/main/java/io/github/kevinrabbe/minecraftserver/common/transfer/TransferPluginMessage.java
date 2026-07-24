package io.github.kevinrabbe.minecraftserver.common.transfer;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;

/** Minimal authenticated-by-connection signal from source Paper to Velocity after a transfer ticket exists. */
public final class TransferPluginMessage {
    public static final String CHANNEL = "minecraftserver:transfer";
    private static final byte VERSION = 1;
    private static final int PAYLOAD_SIZE = 1 + Long.BYTES + Long.BYTES;

    private TransferPluginMessage() {
    }

    public static byte[] encode(UUID transferId) {
        Objects.requireNonNull(transferId, "transferId");
        return ByteBuffer.allocate(PAYLOAD_SIZE)
                .put(VERSION)
                .putLong(transferId.getMostSignificantBits())
                .putLong(transferId.getLeastSignificantBits())
                .array();
    }

    public static UUID decode(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length != PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Unexpected transfer message length: " + payload.length);
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        byte version = buffer.get();
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported transfer message version: " + version);
        }

        return new UUID(buffer.getLong(), buffer.getLong());
    }
}

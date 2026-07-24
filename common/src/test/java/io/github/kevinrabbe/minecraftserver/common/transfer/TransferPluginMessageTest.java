package io.github.kevinrabbe.minecraftserver.common.transfer;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransferPluginMessageTest {
    @Test
    void roundTripsTransferId() {
        UUID transferId = UUID.randomUUID();
        assertEquals(transferId, TransferPluginMessage.decode(TransferPluginMessage.encode(transferId)));
    }

    @Test
    void rejectsMalformedPayloads() {
        assertThrows(IllegalArgumentException.class, () -> TransferPluginMessage.decode(new byte[0]));

        byte[] wrongVersion = TransferPluginMessage.encode(UUID.randomUUID());
        wrongVersion[0] = 99;
        assertThrows(IllegalArgumentException.class, () -> TransferPluginMessage.decode(wrongVersion));
    }
}

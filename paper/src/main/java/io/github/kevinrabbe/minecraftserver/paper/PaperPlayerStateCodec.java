package io.github.kevinrabbe.minecraftserver.paper;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Versioned network-owned carried-inventory payload.
 *
 * <p>Paper's ItemStack NBT byte format carries Minecraft data-version information and is therefore the canonical
 * item representation inside this payload. This wrapper only separates player inventory sections and the held slot.</p>
 */
final class PaperPlayerStateCodec {
    private static final int MAGIC = 0x4D435053; // MCPS
    private static final int VERSION = 1;
    private static final int SECTION_COUNT = 3;
    private static final int MAX_SECTION_BYTES = 4 * 1024 * 1024;
    private static final int MAX_PAYLOAD_BYTES = 12 * 1024 * 1024 + 64;

    byte[] capture(Player player) {
        PlayerInventory inventory = player.getInventory();
        byte[] storage = ItemStack.serializeItemsAsBytes(inventory.getStorageContents());
        byte[] armor = ItemStack.serializeItemsAsBytes(inventory.getArmorContents());
        byte[] extra = ItemStack.serializeItemsAsBytes(inventory.getExtraContents());

        validateSectionSize(storage.length);
        validateSectionSize(armor.length);
        validateSectionSize(extra.length);

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(storage.length + armor.length + extra.length + 32);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(inventory.getHeldItemSlot());
                writeSection(output, storage);
                writeSection(output, armor);
                writeSection(output, extra);
            }

            byte[] payload = bytes.toByteArray();
            if (payload.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalStateException("Player inventory payload exceeds maximum size: " + payload.length);
            }
            return payload;
        } catch (IOException exception) {
            throw new IllegalStateException("Unexpected in-memory inventory serialization failure", exception);
        }
    }

    void apply(Player player, byte[] payload) {
        PlayerInventory inventory = player.getInventory();
        if (payload == null) {
            clearNetworkOwnedInventory(inventory);
            return;
        }
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Player inventory payload exceeds maximum size: " + payload.length);
        }

        DecodedState state = decode(payload);
        int storageCapacity = inventory.getStorageContents().length;
        int armorCapacity = inventory.getArmorContents().length;
        int extraCapacity = inventory.getExtraContents().length;

        if (state.storage().length > storageCapacity) {
            throw new IllegalArgumentException("Stored inventory has more storage slots than this server supports");
        }
        if (state.armor().length > armorCapacity) {
            throw new IllegalArgumentException("Stored inventory has more armor slots than this server supports");
        }
        if (state.extra().length > extraCapacity) {
            throw new IllegalArgumentException("Stored inventory has more extra slots than this server supports");
        }
        if (state.heldItemSlot() < 0 || state.heldItemSlot() > 8) {
            throw new IllegalArgumentException("Stored held-item slot is invalid: " + state.heldItemSlot());
        }

        clearNetworkOwnedInventory(inventory);
        inventory.setStorageContents(state.storage());
        inventory.setArmorContents(state.armor());
        inventory.setExtraContents(state.extra());
        inventory.setHeldItemSlot(state.heldItemSlot());
    }

    private DecodedState decode(byte[] payload) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int magic = input.readInt();
            if (magic != MAGIC) {
                throw new IllegalArgumentException("Unknown player-state payload magic");
            }
            int version = input.readInt();
            if (version != VERSION) {
                throw new IllegalArgumentException("Unsupported player-state payload version: " + version);
            }

            int heldItemSlot = input.readInt();
            byte[][] sections = new byte[SECTION_COUNT][];
            for (int i = 0; i < SECTION_COUNT; i++) {
                sections[i] = readSection(input);
            }
            if (input.available() != 0) {
                throw new IllegalArgumentException("Player-state payload contains trailing bytes");
            }

            return new DecodedState(
                    ItemStack.deserializeItemsFromBytes(sections[0]),
                    ItemStack.deserializeItemsFromBytes(sections[1]),
                    ItemStack.deserializeItemsFromBytes(sections[2]),
                    heldItemSlot
            );
        } catch (IOException exception) {
            throw new IllegalArgumentException("Malformed player-state payload", exception);
        }
    }

    private static void clearNetworkOwnedInventory(PlayerInventory inventory) {
        inventory.setStorageContents(new ItemStack[inventory.getStorageContents().length]);
        inventory.setArmorContents(new ItemStack[inventory.getArmorContents().length]);
        inventory.setExtraContents(new ItemStack[inventory.getExtraContents().length]);
        inventory.setHeldItemSlot(0);
    }

    private static void writeSection(DataOutputStream output, byte[] section) throws IOException {
        validateSectionSize(section.length);
        output.writeInt(section.length);
        output.write(section);
    }

    private static byte[] readSection(DataInputStream input) throws IOException {
        int length = input.readInt();
        validateSectionSize(length);
        byte[] section = input.readNBytes(length);
        if (section.length != length) {
            throw new IllegalArgumentException("Player-state payload ended inside an inventory section");
        }
        return section;
    }

    private static void validateSectionSize(int length) {
        if (length < 0 || length > MAX_SECTION_BYTES) {
            throw new IllegalArgumentException("Invalid inventory section byte length: " + length);
        }
    }

    private record DecodedState(
            ItemStack[] storage,
            ItemStack[] armor,
            ItemStack[] extra,
            int heldItemSlot
    ) {
    }
}

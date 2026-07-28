package io.github.kevinrabbe.minecraftserver.paper;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

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

    // Paper PlayerInventory contract for the carried network-owned state represented by this codec.
    private static final int STORAGE_SLOTS = 36;
    private static final int ARMOR_SLOTS = 4;
    private static final int EXTRA_SLOTS = 1;

    byte[] capture(Player player) {
        PlayerInventory inventory = player.getInventory();
        return encodeState(new InventoryState(
                inventory.getStorageContents(),
                inventory.getArmorContents(),
                inventory.getExtraContents(),
                inventory.getHeldItemSlot()
        ));
    }

    void apply(Player player, byte[] payload) {
        PlayerInventory inventory = player.getInventory();
        if (payload == null) {
            clearNetworkOwnedInventory(inventory);
            return;
        }
        InventoryState state = decodeState(payload);
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

        clearNetworkOwnedInventory(inventory);
        inventory.setStorageContents(state.storage());
        inventory.setArmorContents(state.armor());
        inventory.setExtraContents(state.extra());
        inventory.setHeldItemSlot(state.heldItemSlot());
    }

    /** Pure payload decode used by verified transactional inventory mutators. */
    InventoryState decodeState(byte[] payload) {
        if (payload == null) {
            return emptyState();
        }
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Player inventory payload exceeds maximum size: " + payload.length);
        }
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

            return new InventoryState(
                    ItemStack.deserializeItemsFromBytes(sections[0]),
                    ItemStack.deserializeItemsFromBytes(sections[1]),
                    ItemStack.deserializeItemsFromBytes(sections[2]),
                    heldItemSlot
            );
        } catch (IOException exception) {
            throw new IllegalArgumentException("Malformed player-state payload", exception);
        }
    }

    /** Pure deterministic encode used by the same mutator both before and inside the fenced DB transaction. */
    byte[] encodeState(InventoryState state) {
        Objects.requireNonNull(state, "state");
        byte[] storage = ItemStack.serializeItemsAsBytes(state.storage());
        byte[] armor = ItemStack.serializeItemsAsBytes(state.armor());
        byte[] extra = ItemStack.serializeItemsAsBytes(state.extra());

        validateSectionSize(storage.length);
        validateSectionSize(armor.length);
        validateSectionSize(extra.length);

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(storage.length + armor.length + extra.length + 32);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(state.heldItemSlot());
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

    InventoryState emptyState() {
        return new InventoryState(
                new ItemStack[STORAGE_SLOTS],
                new ItemStack[ARMOR_SLOTS],
                new ItemStack[EXTRA_SLOTS],
                0
        );
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

    record InventoryState(ItemStack[] storage, ItemStack[] armor, ItemStack[] extra, int heldItemSlot) {
        InventoryState {
            storage = Objects.requireNonNull(storage, "storage").clone();
            armor = Objects.requireNonNull(armor, "armor").clone();
            extra = Objects.requireNonNull(extra, "extra").clone();
            if (heldItemSlot < 0 || heldItemSlot > 8) {
                throw new IllegalArgumentException("Stored held-item slot is invalid: " + heldItemSlot);
            }
        }

        @Override
        public ItemStack[] storage() {
            return storage.clone();
        }

        @Override
        public ItemStack[] armor() {
            return armor.clone();
        }

        @Override
        public ItemStack[] extra() {
            return extra.clone();
        }
    }
}

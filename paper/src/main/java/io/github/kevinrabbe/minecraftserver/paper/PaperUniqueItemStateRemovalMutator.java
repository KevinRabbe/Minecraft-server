package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.economy.UniqueItemEscrowValidator;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationClaim;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemStateRemovalValidator;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Deterministic exact unique-item removal used both to build and verify sensitive serialized-state mutations. */
final class PaperUniqueItemStateRemovalMutator implements UniqueItemStateRemovalValidator, UniqueItemEscrowValidator {
    private final PaperPlayerStateCodec stateCodec = new PaperPlayerStateCodec();
    private final PaperItemIdentityCodec identityCodec;

    PaperUniqueItemStateRemovalMutator(MinecraftServerPlugin plugin) {
        this.identityCodec = new PaperItemIdentityCodec(Objects.requireNonNull(plugin, "plugin"));
    }

    byte[] remove(
            UUID playerId,
            UUID itemInstanceId,
            long expectedItemStateVersion,
            byte[] currentStatePayload
    ) {
        if (expectedItemStateVersion < 0) {
            throw new IllegalArgumentException("expectedItemStateVersion must be >= 0");
        }
        return remove(playerId, itemInstanceId, Long.valueOf(expectedItemStateVersion), currentStatePayload);
    }

    private byte[] remove(
            UUID playerId,
            UUID itemInstanceId,
            Long expectedItemStateVersion,
            byte[] currentStatePayload
    ) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(itemInstanceId, "itemInstanceId");

        PaperPlayerStateCodec.InventoryState current = stateCodec.decodeState(currentStatePayload);
        ItemStack[] storage = current.storage();
        ItemStack[] armor = current.armor();
        ItemStack[] extra = current.extra();

        Removal removal = findAndRemove(storage, "storage", itemInstanceId, expectedItemStateVersion, null);
        removal = findAndRemove(armor, "armor", itemInstanceId, expectedItemStateVersion, removal);
        removal = findAndRemove(extra, "extra", itemInstanceId, expectedItemStateVersion, removal);
        if (removal == null) {
            throw new PaperItemRepresentationException(
                    "Authoritative unique item is not represented in serialized player state: " + itemInstanceId
            );
        }

        return stateCodec.encodeState(new PaperPlayerStateCodec.InventoryState(
                storage,
                armor,
                extra,
                current.heldItemSlot()
        ));
    }

    @Override
    public void verifyRemoval(
            UUID playerId,
            UUID itemInstanceId,
            long expectedItemStateVersion,
            byte[] currentStatePayload,
            byte[] nextStatePayload
    ) {
        verifyExactRemoval(
                remove(playerId, itemInstanceId, expectedItemStateVersion, currentStatePayload),
                nextStatePayload
        );
    }

    @Override
    public void verifyRemoval(
            UUID playerId,
            UUID itemInstanceId,
            byte[] currentStatePayload,
            byte[] nextStatePayload
    ) {
        verifyExactRemoval(
                remove(playerId, itemInstanceId, null, currentStatePayload),
                nextStatePayload
        );
    }

    private static void verifyExactRemoval(byte[] expectedStatePayload, byte[] nextStatePayload) {
        Objects.requireNonNull(nextStatePayload, "nextStatePayload");
        if (!Arrays.equals(expectedStatePayload, nextStatePayload)) {
            throw new PaperItemRepresentationException(
                    "Serialized unique-item mutation changed more than the exact requested item removal"
            );
        }
    }

    private Removal findAndRemove(
            ItemStack[] contents,
            String section,
            UUID itemInstanceId,
            Long expectedItemStateVersion,
            Removal existing
    ) {
        Removal found = existing;
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Optional<ItemRepresentationClaim> optional = identityCodec.readClaim(stack, section + "[" + slot + "]");
            if (optional.isEmpty()) {
                continue;
            }
            ItemRepresentationClaim claim = optional.orElseThrow();
            if (!itemInstanceId.equals(claim.itemInstanceId())) {
                continue;
            }
            if (found != null) {
                throw new PaperItemRepresentationException(
                        "Unique item is represented more than once in serialized player state: " + itemInstanceId
                );
            }
            if (!claim.individualClaim() || claim.authorityVersion() == null || claim.amount() != 1) {
                throw new PaperItemRepresentationException(
                        "Unique item representation is malformed for removal: " + itemInstanceId
                );
            }
            if (expectedItemStateVersion != null
                    && claim.authorityVersion().longValue() != expectedItemStateVersion.longValue()) {
                throw new PaperItemRepresentationException(
                        "Unique item representation has stale authority_version for " + itemInstanceId
                                + ": expected " + expectedItemStateVersion
                                + " but found " + claim.authorityVersion()
                );
            }
            contents[slot] = null;
            found = new Removal(section, slot);
        }
        return found;
    }

    private record Removal(String section, int slot) { }
}

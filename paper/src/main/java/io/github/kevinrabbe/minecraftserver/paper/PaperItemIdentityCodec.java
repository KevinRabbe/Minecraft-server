package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationClaim;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemInstance;
import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Compact PDC identity envelope. Database rows remain authoritative; these tags are only representation claims. */
final class PaperItemIdentityCodec {
    private static final int SCHEMA_VERSION = 1;
    private static final int UUID_BYTES = 16;

    private final NamespacedKey schemaVersionKey;
    private final NamespacedKey definitionIdKey;
    private final NamespacedKey itemInstanceIdKey;
    private final NamespacedKey authorityVersionKey;

    PaperItemIdentityCodec(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        schemaVersionKey = new NamespacedKey(plugin, "item_schema");
        definitionIdKey = new NamespacedKey(plugin, "definition_id");
        itemInstanceIdKey = new NamespacedKey(plugin, "item_instance_id");
        authorityVersionKey = new NamespacedKey(plugin, "authority_version");
    }

    Optional<ItemRepresentationClaim> readClaim(ItemStack stack, String source) {
        Objects.requireNonNull(stack, "stack");
        PersistentDataContainerView view = stack.getPersistentDataContainer();
        if (!hasAnyIdentityKey(view)) {
            return Optional.empty();
        }

        Integer schemaVersion = requireTyped(view, schemaVersionKey, PersistentDataType.INTEGER, "item_schema");
        if (schemaVersion != SCHEMA_VERSION) {
            throw new PaperItemRepresentationException(
                    "Unsupported item identity schema " + schemaVersion + " at " + source
            );
        }

        String definitionId = requireTyped(
                view,
                definitionIdKey,
                PersistentDataType.STRING,
                "definition_id"
        );
        if (definitionId.isBlank()) {
            throw new PaperItemRepresentationException("Blank definition_id at " + source);
        }

        boolean hasInstanceId = view.has(itemInstanceIdKey);
        boolean hasAuthorityVersion = view.has(authorityVersionKey);
        if (hasInstanceId != hasAuthorityVersion) {
            throw new PaperItemRepresentationException(
                    "Partial individual item identity metadata at " + source
            );
        }

        UUID itemInstanceId = null;
        Long authorityVersion = null;
        if (hasInstanceId) {
            byte[] rawUuid = requireTyped(
                    view,
                    itemInstanceIdKey,
                    PersistentDataType.BYTE_ARRAY,
                    "item_instance_id"
            );
            itemInstanceId = decodeUuid(rawUuid, source);
            authorityVersion = requireTyped(
                    view,
                    authorityVersionKey,
                    PersistentDataType.LONG,
                    "authority_version"
            );
            if (authorityVersion < 0) {
                throw new PaperItemRepresentationException(
                        "Negative authority_version at " + source
                );
            }
        }

        return Optional.of(new ItemRepresentationClaim(
                source,
                definitionId,
                stack.getType().name(),
                stack.getAmount(),
                itemInstanceId,
                authorityVersion
        ));
    }

    void writeCommodity(ItemStack stack, ItemDefinition definition) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(definition, "definition");
        if (definition.identityKind() != ItemIdentityKind.COMMODITY) {
            throw new IllegalArgumentException("Definition is not COMMODITY: " + definition.definitionId());
        }
        if (!stack.getType().name().equals(definition.minecraftMaterial())) {
            throw new IllegalArgumentException("ItemStack material does not match definition");
        }

        boolean edited = stack.editPersistentDataContainer(container -> {
            writeBaseIdentity(container, definition.definitionId());
            container.remove(itemInstanceIdKey);
            container.remove(authorityVersionKey);
        });
        if (!edited) {
            throw new PaperItemRepresentationException("Could not write commodity identity metadata");
        }
    }

    void writeIndividual(ItemStack stack, ItemDefinition definition, UniqueItemInstance instance) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(instance, "instance");
        if (definition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
            throw new IllegalArgumentException("Definition is not INDIVIDUAL: " + definition.definitionId());
        }
        if (!definition.definitionId().equals(instance.definitionId())) {
            throw new IllegalArgumentException("Item instance definition does not match render definition");
        }
        if (!stack.getType().name().equals(definition.minecraftMaterial()) || stack.getAmount() != 1) {
            throw new IllegalArgumentException("Individual ItemStack must use the configured material and amount 1");
        }

        boolean edited = stack.editPersistentDataContainer(container -> {
            writeBaseIdentity(container, definition.definitionId());
            container.set(
                    itemInstanceIdKey,
                    PersistentDataType.BYTE_ARRAY,
                    encodeUuid(instance.itemInstanceId())
            );
            container.set(
                    authorityVersionKey,
                    PersistentDataType.LONG,
                    instance.stateVersion()
            );
        });
        if (!edited) {
            throw new PaperItemRepresentationException("Could not write individual item identity metadata");
        }
    }

    private void writeBaseIdentity(PersistentDataContainer container, String definitionId) {
        container.set(schemaVersionKey, PersistentDataType.INTEGER, SCHEMA_VERSION);
        container.set(definitionIdKey, PersistentDataType.STRING, definitionId);
    }

    private boolean hasAnyIdentityKey(PersistentDataContainerView view) {
        return view.has(schemaVersionKey)
                || view.has(definitionIdKey)
                || view.has(itemInstanceIdKey)
                || view.has(authorityVersionKey);
    }

    private static <P, C> C requireTyped(
            PersistentDataContainerView view,
            NamespacedKey key,
            PersistentDataType<P, C> type,
            String fieldName
    ) {
        if (!view.has(key)) {
            throw new PaperItemRepresentationException("Missing item identity field " + fieldName);
        }
        C value = view.get(key, type);
        if (value == null) {
            throw new PaperItemRepresentationException("Wrong persistent-data type for " + fieldName);
        }
        return value;
    }

    private static byte[] encodeUuid(UUID value) {
        return ByteBuffer.allocate(UUID_BYTES)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static UUID decodeUuid(byte[] value, String source) {
        if (value.length != UUID_BYTES) {
            throw new PaperItemRepresentationException(
                    "item_instance_id must be exactly 16 bytes at " + source
            );
        }
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}

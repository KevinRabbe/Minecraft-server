package io.github.kevinrabbe.minecraftserver.common.item;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Strict JSON loader for version-controlled item content. */
public final class ItemCatalogLoader {
    public static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    public ItemCatalogLoader() {
        objectMapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
    }

    public ItemCatalog load(Path path) {
        Objects.requireNonNull(path, "path");
        try (InputStream input = Files.newInputStream(path)) {
            return load(input, path.toString());
        } catch (IOException exception) {
            throw new ItemCatalogException("Could not read item catalog: " + path, exception);
        }
    }

    public ItemCatalog loadResource(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }

        InputStream input = ItemCatalogLoader.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new ItemCatalogException("Item catalog resource does not exist: " + resourcePath);
        }
        try (input) {
            return load(input, resourcePath);
        } catch (IOException exception) {
            throw new ItemCatalogException("Could not close item catalog resource: " + resourcePath, exception);
        }
    }

    public ItemCatalog load(InputStream input, String sourceDescription) {
        Objects.requireNonNull(input, "input");
        String source = sourceDescription == null || sourceDescription.isBlank()
                ? "<stream>"
                : sourceDescription.trim();

        final RawCatalog raw;
        try {
            raw = objectMapper.readValue(input, RawCatalog.class);
        } catch (IOException exception) {
            throw new ItemCatalogException("Invalid item catalog JSON in " + source, exception);
        }

        if (raw == null) {
            throw new ItemCatalogException("Item catalog is empty: " + source);
        }
        if (raw.schemaVersion() != SCHEMA_VERSION) {
            throw new ItemCatalogException(
                    "Unsupported item catalog schema_version " + raw.schemaVersion()
                            + " in " + source + "; expected " + SCHEMA_VERSION
            );
        }
        if (raw.items() == null) {
            throw new ItemCatalogException("Item catalog must contain an items array: " + source);
        }

        ArrayList<ItemDefinition> definitions = new ArrayList<>(raw.items().size());
        for (int index = 0; index < raw.items().size(); index++) {
            RawDefinition item = raw.items().get(index);
            if (item == null) {
                throw new ItemCatalogException("items[" + index + "] must not be null in " + source);
            }

            try {
                definitions.add(new ItemDefinition(
                        item.definitionId(),
                        item.minecraftMaterial(),
                        item.displayName(),
                        item.maxStackSize(),
                        parseEnum(ItemCategory.class, item.category(), "category"),
                        parseEnum(ItemIdentityKind.class, item.identityKind(), "identity_kind")
                ));
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new ItemCatalogException(
                        "Invalid item definition at items[" + index + "] in " + source + ": " + exception.getMessage(),
                        exception
                );
            }
        }

        try {
            return new ItemCatalog(definitions);
        } catch (ItemCatalogException exception) {
            throw new ItemCatalogException("Invalid item catalog " + source + ": " + exception.getMessage(), exception);
        }
    }

    private static <E extends Enum<E>> E parseEnum(
            Class<E> type,
            String rawValue,
            String fieldName
    ) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        try {
            return Enum.valueOf(type, rawValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    fieldName + " has unknown value '" + rawValue + "'",
                    exception
            );
        }
    }

    private record RawCatalog(
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("items") List<RawDefinition> items
    ) {
    }

    private record RawDefinition(
            @JsonProperty("definition_id") String definitionId,
            @JsonProperty("minecraft_material") String minecraftMaterial,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("max_stack_size") int maxStackSize,
            @JsonProperty("category") String category,
            @JsonProperty("identity_kind") String identityKind
    ) {
    }
}

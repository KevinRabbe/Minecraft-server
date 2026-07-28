package io.github.kevinrabbe.minecraftserver.common.economy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Strict JSON loader for irreversible unique-item salvage returns. */
public final class SalvageCatalogLoader {
    public static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    public SalvageCatalog load(Path path, ItemCatalog itemCatalog) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(itemCatalog, "itemCatalog");
        try (InputStream input = Files.newInputStream(path)) {
            return load(input, path.toString(), itemCatalog);
        } catch (IOException exception) {
            throw new SalvageException("Could not read salvage catalog: " + path, exception);
        }
    }

    public SalvageCatalog loadResource(String resourcePath, ItemCatalog itemCatalog) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        Objects.requireNonNull(itemCatalog, "itemCatalog");
        InputStream input = SalvageCatalogLoader.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new SalvageException("Salvage resource does not exist: " + resourcePath);
        }
        try (input) {
            return load(input, resourcePath, itemCatalog);
        } catch (IOException exception) {
            throw new SalvageException("Could not close salvage resource: " + resourcePath, exception);
        }
    }

    SalvageCatalog load(InputStream input, String sourceDescription, ItemCatalog itemCatalog) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(itemCatalog, "itemCatalog");
        String source = sourceDescription == null || sourceDescription.isBlank()
                ? "<stream>"
                : sourceDescription.trim();

        final RawCatalog raw;
        try {
            raw = objectMapper.readValue(input, RawCatalog.class);
        } catch (IOException exception) {
            throw new SalvageException("Invalid salvage JSON in " + source, exception);
        }
        if (raw == null || raw.salvage() == null) {
            throw new SalvageException("Salvage catalog must contain a salvage array: " + source);
        }
        if (raw.schemaVersion() != SCHEMA_VERSION) {
            throw new SalvageException(
                    "Unsupported salvage schema_version " + raw.schemaVersion()
                            + " in " + source + "; expected " + SCHEMA_VERSION
            );
        }

        ArrayList<SalvageDefinition> definitions = new ArrayList<>(raw.salvage().size());
        for (int index = 0; index < raw.salvage().size(); index++) {
            RawDefinition value = raw.salvage().get(index);
            if (value == null) {
                throw new SalvageException("salvage[" + index + "] must not be null in " + source);
            }
            try {
                definitions.add(new SalvageDefinition(
                        value.itemDefinitionId(),
                        value.coinReturnMinor(),
                        value.commodityReturns() == null ? Map.of() : value.commodityReturns()
                ));
            } catch (IllegalArgumentException exception) {
                throw new SalvageException(
                        "Invalid salvage entry at salvage[" + index + "] in " + source + ": "
                                + exception.getMessage(),
                        exception
                );
            }
        }

        try {
            return new SalvageCatalog(definitions, itemCatalog);
        } catch (RuntimeException exception) {
            throw new SalvageException("Invalid salvage authority references in " + source, exception);
        }
    }

    private record RawCatalog(
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("salvage") List<RawDefinition> salvage
    ) { }

    private record RawDefinition(
            @JsonProperty("item_definition_id") String itemDefinitionId,
            @JsonProperty("coin_return_minor") long coinReturnMinor,
            @JsonProperty("commodity_returns") Map<String, Long> commodityReturns
    ) { }
}

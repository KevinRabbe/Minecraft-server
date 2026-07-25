package io.github.kevinrabbe.minecraftserver.common.world.resource;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Strict versioned JSON loader for renewable resource-source definitions. */
public final class ResourceSourceCatalogLoader {
    public static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    public ResourceSourceCatalog load(
            Path path,
            ItemCatalog itemCatalog,
            SkillProgressionCatalog skillCatalog
    ) {
        Objects.requireNonNull(path, "path");
        try (InputStream input = Files.newInputStream(path)) {
            return load(input, path.toString(), itemCatalog, skillCatalog);
        } catch (IOException exception) {
            throw new ResourceSourceException("Could not read resource-source catalog: " + path, exception);
        }
    }

    public ResourceSourceCatalog loadResource(
            String resourcePath,
            ItemCatalog itemCatalog,
            SkillProgressionCatalog skillCatalog
    ) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        InputStream input = ResourceSourceCatalogLoader.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new ResourceSourceException("Resource-source catalog does not exist: " + resourcePath);
        }
        try (input) {
            return load(input, resourcePath, itemCatalog, skillCatalog);
        } catch (IOException exception) {
            throw new ResourceSourceException("Could not close resource-source catalog: " + resourcePath, exception);
        }
    }

    ResourceSourceCatalog load(
            InputStream input,
            String sourceDescription,
            ItemCatalog itemCatalog,
            SkillProgressionCatalog skillCatalog
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(itemCatalog, "itemCatalog");
        Objects.requireNonNull(skillCatalog, "skillCatalog");
        String source = sourceDescription == null || sourceDescription.isBlank()
                ? "<stream>"
                : sourceDescription.trim();

        final RawCatalog raw;
        try {
            raw = objectMapper.readValue(input, RawCatalog.class);
        } catch (IOException exception) {
            throw new ResourceSourceException("Invalid resource-source catalog JSON in " + source, exception);
        }
        if (raw == null || raw.sources() == null) {
            throw new ResourceSourceException("Resource-source catalog must contain a sources array: " + source);
        }
        if (raw.schemaVersion() != SCHEMA_VERSION) {
            throw new ResourceSourceException(
                    "Unsupported resource-source schema_version " + raw.schemaVersion()
                            + " in " + source + "; expected " + SCHEMA_VERSION
            );
        }

        ArrayList<ResourceSourceDefinition> definitions = new ArrayList<>(raw.sources().size());
        for (int index = 0; index < raw.sources().size(); index++) {
            RawSource value = raw.sources().get(index);
            if (value == null) {
                throw new ResourceSourceException("sources[" + index + "] must not be null in " + source);
            }
            try {
                SkillId skillId = value.skillId() == null || value.skillId().isBlank()
                        ? null
                        : new SkillId(value.skillId());
                definitions.add(new ResourceSourceDefinition(
                        value.definitionId(),
                        value.zoneId(),
                        value.templateVersion(),
                        value.commodityDefinitionId(),
                        value.commodityQuantity(),
                        skillId,
                        value.requestedExperience(),
                        Duration.ofMillis(value.respawnMillis())
                ));
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new ResourceSourceException(
                        "Invalid resource source at sources[" + index + "] in " + source + ": " + exception.getMessage(),
                        exception
                );
            }
        }
        return new ResourceSourceCatalog(definitions, itemCatalog, skillCatalog);
    }

    private record RawCatalog(
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("sources") List<RawSource> sources
    ) { }

    private record RawSource(
            @JsonProperty("definition_id") String definitionId,
            @JsonProperty("zone_id") String zoneId,
            @JsonProperty("template_version") String templateVersion,
            @JsonProperty("commodity_definition_id") String commodityDefinitionId,
            @JsonProperty("commodity_quantity") long commodityQuantity,
            @JsonProperty("skill_id") String skillId,
            @JsonProperty("requested_experience") long requestedExperience,
            @JsonProperty("respawn_millis") long respawnMillis
    ) { }
}

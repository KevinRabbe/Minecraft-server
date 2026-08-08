package io.github.kevinrabbe.minecraftserver.paper;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapDifficulty;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Strict product policy for the renewable starter elite -> first Map bridge. */
final class PaperStarterMapPolicy {
    private static final int SCHEMA_VERSION = 1;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");

    private final String sourceDefinitionId;
    private final String sourceZoneId;
    private final String mapDefinitionId;
    private final int difficulty;
    private final String environmentId;
    private final String enemyPackageId;
    private final String objectiveId;
    private final List<String> modifierIds;
    private final int generationVersion;
    private final int balanceVersion;

    private PaperStarterMapPolicy(
            String sourceDefinitionId,
            String sourceZoneId,
            String mapDefinitionId,
            int difficulty,
            String environmentId,
            String enemyPackageId,
            String objectiveId,
            List<String> modifierIds,
            int generationVersion,
            int balanceVersion
    ) {
        this.sourceDefinitionId = requireId(sourceDefinitionId, "sourceDefinitionId");
        this.sourceZoneId = requireId(sourceZoneId, "sourceZoneId");
        this.mapDefinitionId = requireId(mapDefinitionId, "mapDefinitionId");
        this.difficulty = new MapDifficulty(difficulty).value();
        this.environmentId = requireId(environmentId, "environmentId");
        this.enemyPackageId = requireId(enemyPackageId, "enemyPackageId");
        this.objectiveId = requireId(objectiveId, "objectiveId");
        this.modifierIds = List.copyOf(Objects.requireNonNull(modifierIds, "modifierIds")).stream()
                .map(value -> requireId(value, "modifierId"))
                .distinct()
                .sorted()
                .toList();
        if (generationVersion < 1 || balanceVersion < 1) {
            throw new IllegalArgumentException("generationVersion and balanceVersion must be >= 1");
        }
        this.generationVersion = generationVersion;
        this.balanceVersion = balanceVersion;
    }

    static PaperStarterMapPolicy loadResource(String resourcePath, ItemCatalog itemCatalog) {
        Objects.requireNonNull(itemCatalog, "itemCatalog");
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        InputStream input = PaperStarterMapPolicy.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new IllegalStateException("Starter Map policy resource does not exist: " + resourcePath);
        }
        ObjectMapper mapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
        try (input) {
            RawPolicy raw = mapper.readValue(input, RawPolicy.class);
            if (raw == null) {
                throw new IllegalStateException("Starter Map policy must not be null");
            }
            if (raw.schemaVersion() != SCHEMA_VERSION) {
                throw new IllegalStateException(
                        "Unsupported Starter Map policy schema_version " + raw.schemaVersion()
                                + "; expected " + SCHEMA_VERSION
                );
            }
            PaperStarterMapPolicy result = new PaperStarterMapPolicy(
                    raw.sourceDefinitionId(),
                    raw.sourceZoneId(),
                    raw.mapDefinitionId(),
                    raw.difficulty(),
                    raw.environmentId(),
                    raw.enemyPackageId(),
                    raw.objectiveId(),
                    raw.modifierIds(),
                    raw.generationVersion(),
                    raw.balanceVersion()
            );
            var mapDefinition = itemCatalog.require(result.mapDefinitionId());
            if (mapDefinition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
                throw new IllegalStateException(
                        "Starter Map definition must be INDIVIDUAL: " + mapDefinition.definitionId()
                );
            }
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("Invalid Starter Map policy JSON: " + resourcePath, exception);
        }
    }

    String sourceDefinitionId() {
        return sourceDefinitionId;
    }

    String sourceZoneId() {
        return sourceZoneId;
    }

    String mapDefinitionId() {
        return mapDefinitionId;
    }

    MapRunDefinition runDefinition(String worldEraId, long generationSeed) {
        return new MapRunDefinition(
                new MapDifficulty(difficulty),
                environmentId,
                enemyPackageId,
                objectiveId,
                modifierIds,
                generationSeed,
                generationVersion,
                balanceVersion,
                requireId(worldEraId, "worldEraId")
        );
    }

    private static String requireId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (!ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " has invalid format: " + normalized);
        }
        return normalized;
    }

    private record RawPolicy(
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("source_definition_id") String sourceDefinitionId,
            @JsonProperty("source_zone_id") String sourceZoneId,
            @JsonProperty("map_definition_id") String mapDefinitionId,
            @JsonProperty("difficulty") int difficulty,
            @JsonProperty("environment_id") String environmentId,
            @JsonProperty("enemy_package_id") String enemyPackageId,
            @JsonProperty("objective_id") String objectiveId,
            @JsonProperty("modifier_ids") List<String> modifierIds,
            @JsonProperty("generation_version") int generationVersion,
            @JsonProperty("balance_version") int balanceVersion
    ) { }
}

package io.github.kevinrabbe.minecraftserver.paper;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityException;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunDefinition;
import org.bukkit.entity.EntityType;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Strict Paper materialization catalog for the small set of launch Map encounter combinations. */
final class PaperMapEncounterContentCatalog {
    private static final int SCHEMA_VERSION = 1;
    private static final int SUPPORTED_GENERATION_VERSION = 1;
    private static final int SUPPORTED_BALANCE_VERSION = 1;
    private static final String SUPPORTED_OBJECTIVE_ID = "extermination";

    private final Map<Key, PaperMapEncounterDefinition> definitions;

    private PaperMapEncounterContentCatalog(Map<Key, PaperMapEncounterDefinition> definitions) {
        this.definitions = Map.copyOf(definitions);
    }

    static PaperMapEncounterContentCatalog loadResource(String resourcePath, ItemCatalog itemCatalog) {
        Objects.requireNonNull(itemCatalog, "itemCatalog");
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        InputStream input = PaperMapEncounterContentCatalog.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new IllegalStateException("Map encounter content resource does not exist: " + resourcePath);
        }

        ObjectMapper mapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
        try (input) {
            RawCatalog raw = mapper.readValue(input, RawCatalog.class);
            if (raw == null || raw.encounters() == null) {
                throw new IllegalStateException("Map encounter content must contain encounters array");
            }
            if (raw.schemaVersion() != SCHEMA_VERSION) {
                throw new IllegalStateException(
                        "Unsupported Map encounter content schema_version " + raw.schemaVersion()
                                + "; expected " + SCHEMA_VERSION
                );
            }

            LinkedHashMap<Key, PaperMapEncounterDefinition> definitions = new LinkedHashMap<>();
            for (int index = 0; index < raw.encounters().size(); index++) {
                RawEncounter value = raw.encounters().get(index);
                if (value == null) {
                    throw new IllegalStateException("encounters[" + index + "] must not be null");
                }
                EntityType entityType = requireEntityType(value.entityType());
                ItemDefinition rewardMap = itemCatalog.require(value.rewardMapDefinitionId());
                if (rewardMap.identityKind() != ItemIdentityKind.INDIVIDUAL) {
                    throw new IllegalStateException(
                            "Map encounter reward definition must be INDIVIDUAL: " + rewardMap.definitionId()
                    );
                }
                PaperMapEncounterDefinition definition = new PaperMapEncounterDefinition(
                        value.encounterId(),
                        value.environmentId(),
                        value.enemyFamilyId(),
                        value.objectiveId(),
                        entityType,
                        value.baseKills(),
                        value.difficultyPerExtraKill(),
                        value.maxKills(),
                        value.healthPerDifficulty(),
                        value.maxHealthMultiplier(),
                        value.damagePerDifficulty(),
                        value.maxDamageMultiplier(),
                        value.spawnRadius(),
                        rewardMap.definitionId(),
                        value.successorDifficultyDelta(),
                        value.maxSuccessorDifficulty()
                );
                Key key = new Key(
                        definition.environmentId(),
                        definition.enemyFamilyId(),
                        definition.objectiveId()
                );
                if (definitions.putIfAbsent(key, definition) != null) {
                    throw new IllegalStateException("Duplicate Map encounter tuple: " + key);
                }
            }
            if (definitions.isEmpty()) {
                throw new IllegalStateException("Map encounter content must not be empty");
            }
            return new PaperMapEncounterContentCatalog(definitions);
        } catch (IOException exception) {
            throw new IllegalStateException("Invalid Map encounter content JSON: " + resourcePath, exception);
        }
    }

    PaperMapEncounterDefinition require(MapRunDefinition run) {
        Objects.requireNonNull(run, "run");
        requireSupportedRuntimeSemantics(run);
        Key key = new Key(run.environmentId(), run.enemyFamilyId(), run.objectiveId());
        PaperMapEncounterDefinition definition = definitions.get(key);
        if (definition == null) {
            throw new MapAuthorityException("No Paper encounter materialization for Map tuple: " + key);
        }
        return definition;
    }

    private static void requireSupportedRuntimeSemantics(MapRunDefinition run) {
        if (!SUPPORTED_OBJECTIVE_ID.equals(run.objectiveId())) {
            throw new MapAuthorityException(
                    "Paper Map runtime does not support objective_id " + run.objectiveId()
                            + "; supported=" + SUPPORTED_OBJECTIVE_ID
            );
        }
        if (run.generationVersion() != SUPPORTED_GENERATION_VERSION) {
            throw new MapAuthorityException(
                    "Paper Map runtime does not support generation_version " + run.generationVersion()
                            + "; supported=" + SUPPORTED_GENERATION_VERSION
            );
        }
        if (run.balanceVersion() != SUPPORTED_BALANCE_VERSION) {
            throw new MapAuthorityException(
                    "Paper Map runtime does not support balance_version " + run.balanceVersion()
                            + "; supported=" + SUPPORTED_BALANCE_VERSION
            );
        }
        if (!run.modifierIds().isEmpty()) {
            throw new MapAuthorityException(
                    "Paper Map runtime does not yet materialize modifiers: " + run.modifierIds()
            );
        }
    }

    private static EntityType requireEntityType(String raw) {
        if (raw == null || !raw.matches("[a-z0-9][a-z0-9_]{0,63}")) {
            throw new IllegalStateException("Map encounter entity_type must be a lowercase Minecraft entity ID");
        }
        final EntityType type;
        try {
            type = EntityType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unknown Map encounter entity_type: " + raw, exception);
        }
        if (!type.isAlive() || !type.isSpawnable()) {
            throw new IllegalStateException("Map encounter entity_type is not a spawnable living entity: " + raw);
        }
        return type;
    }

    private record Key(String environmentId, String enemyFamilyId, String objectiveId) {
        private Key {
            environmentId = Objects.requireNonNull(environmentId, "environmentId");
            enemyFamilyId = Objects.requireNonNull(enemyFamilyId, "enemyFamilyId");
            objectiveId = Objects.requireNonNull(objectiveId, "objectiveId");
        }

        @Override
        public String toString() {
            return environmentId + "/" + enemyFamilyId + "/" + objectiveId;
        }
    }

    private record RawCatalog(
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("encounters") List<RawEncounter> encounters
    ) { }

    private record RawEncounter(
            @JsonProperty("encounter_id") String encounterId,
            @JsonProperty("environment_id") String environmentId,
            @JsonProperty("enemy_family_id") String enemyFamilyId,
            @JsonProperty("objective_id") String objectiveId,
            @JsonProperty("entity_type") String entityType,
            @JsonProperty("base_kills") int baseKills,
            @JsonProperty("difficulty_per_extra_kill") int difficultyPerExtraKill,
            @JsonProperty("max_kills") int maxKills,
            @JsonProperty("health_per_difficulty") double healthPerDifficulty,
            @JsonProperty("max_health_multiplier") double maxHealthMultiplier,
            @JsonProperty("damage_per_difficulty") double damagePerDifficulty,
            @JsonProperty("max_damage_multiplier") double maxDamageMultiplier,
            @JsonProperty("spawn_radius") double spawnRadius,
            @JsonProperty("reward_map_definition_id") String rewardMapDefinitionId,
            @JsonProperty("successor_difficulty_delta") int successorDifficultyDelta,
            @JsonProperty("max_successor_difficulty") int maxSuccessorDifficulty
    ) { }
}

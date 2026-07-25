package io.github.kevinrabbe.minecraftserver.paper;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyContentCatalog;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyException;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Strict Paper-only mapping from persistent bounty boss definitions to authored disposable spawn anchors. */
final class PaperBountyBossPlacementCatalog {
    private static final int SCHEMA_VERSION = 1;

    private final Map<String, PaperBountyBossPlacement> byBossDefinition;

    private PaperBountyBossPlacementCatalog(Map<String, PaperBountyBossPlacement> byBossDefinition) {
        this.byBossDefinition = Map.copyOf(byBossDefinition);
    }

    static PaperBountyBossPlacementCatalog loadResource(
            String resourcePath,
            BountyContentCatalog bountyContent
    ) {
        Objects.requireNonNull(bountyContent, "bountyContent");
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        InputStream input = PaperBountyBossPlacementCatalog.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new IllegalStateException("Bounty boss placement resource does not exist: " + resourcePath);
        }
        try (input) {
            ObjectMapper mapper = JsonMapper.builder()
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .build();
            RawCatalog raw = mapper.readValue(input, RawCatalog.class);
            if (raw == null || raw.placements() == null) {
                throw new IllegalStateException("Bounty boss placements must contain placements array");
            }
            if (raw.schemaVersion() != SCHEMA_VERSION) {
                throw new IllegalStateException(
                        "Unsupported bounty boss placement schema_version " + raw.schemaVersion()
                );
            }

            java.util.Set<String> configuredBosses = bountyContent.definitions().stream()
                    .map(definition -> definition.bossDefinitionId())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            LinkedHashMap<String, PaperBountyBossPlacement> result = new LinkedHashMap<>();
            for (int index = 0; index < raw.placements().size(); index++) {
                RawPlacement value = raw.placements().get(index);
                if (value == null) {
                    throw new IllegalStateException("placements[" + index + "] must not be null");
                }
                String bossDefinitionId = requireText(value.bossDefinitionId(), "boss_definition_id");
                if (!configuredBosses.contains(bossDefinitionId)) {
                    throw new IllegalStateException(
                            "Bounty boss placement references unknown boss_definition_id: " + bossDefinitionId
                    );
                }
                Class<? extends LivingEntity> entityClass = requireLivingEntityClass(value.entityType());
                PaperBountyBossPlacement placement = new PaperBountyBossPlacement(
                        bossDefinitionId,
                        value.zoneId(),
                        value.templateVersion(),
                        value.worldName(),
                        value.x(),
                        value.y(),
                        value.z(),
                        value.yaw(),
                        value.pitch(),
                        entityClass,
                        value.displayName()
                );
                if (result.putIfAbsent(bossDefinitionId, placement) != null) {
                    throw new IllegalStateException("Duplicate bounty boss placement: " + bossDefinitionId);
                }
            }

            if (!result.keySet().equals(configuredBosses)) {
                java.util.Set<String> missing = new java.util.TreeSet<>(configuredBosses);
                missing.removeAll(result.keySet());
                throw new IllegalStateException("Missing bounty boss placements: " + missing);
            }
            return new PaperBountyBossPlacementCatalog(result);
        } catch (IOException exception) {
            throw new IllegalStateException("Invalid bounty boss placement JSON: " + resourcePath, exception);
        }
    }

    PaperBountyBossPlacement require(String bossDefinitionId) {
        PaperBountyBossPlacement placement = byBossDefinition.get(bossDefinitionId);
        if (placement == null) {
            throw new BountyException("No Paper placement for bounty boss: " + bossDefinitionId);
        }
        return placement;
    }

    int size() {
        return byBossDefinition.size();
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends LivingEntity> requireLivingEntityClass(String rawEntityType) {
        String normalized = requireText(rawEntityType, "entity_type").toUpperCase(java.util.Locale.ROOT);
        final EntityType type;
        try {
            type = EntityType.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unknown bounty boss entity_type: " + normalized, exception);
        }
        Class<? extends Entity> entityClass = type.getEntityClass();
        if (entityClass == null || !LivingEntity.class.isAssignableFrom(entityClass)) {
            throw new IllegalStateException("Bounty boss entity_type must be a LivingEntity: " + normalized);
        }
        return (Class<? extends LivingEntity>) entityClass;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalStateException(field + " must not be blank");
        return value.trim();
    }

    private record RawCatalog(
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("placements") List<RawPlacement> placements
    ) { }

    private record RawPlacement(
            @JsonProperty("boss_definition_id") String bossDefinitionId,
            @JsonProperty("zone_id") String zoneId,
            @JsonProperty("template_version") String templateVersion,
            @JsonProperty("world_name") String worldName,
            @JsonProperty("x") double x,
            @JsonProperty("y") double y,
            @JsonProperty("z") double z,
            @JsonProperty("yaw") float yaw,
            @JsonProperty("pitch") float pitch,
            @JsonProperty("entity_type") String entityType,
            @JsonProperty("display_name") String displayName
    ) { }
}

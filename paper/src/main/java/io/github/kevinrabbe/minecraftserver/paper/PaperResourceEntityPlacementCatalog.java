package io.github.kevinrabbe.minecraftserver.paper;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceCatalog;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceDefinition;
import org.bukkit.entity.EntityType;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Version-controlled physical spawn points for entity-bound renewable source definitions. */
final class PaperResourceEntityPlacementCatalog {
    private static final int SCHEMA_VERSION = 1;

    private final List<PaperResourceEntityPlacement> placements;

    private PaperResourceEntityPlacementCatalog(List<PaperResourceEntityPlacement> placements) {
        LinkedHashMap<String, PaperResourceEntityPlacement> sourceKeys = new LinkedHashMap<>();
        for (PaperResourceEntityPlacement placement : placements) {
            String key = placement.zoneId() + ":" + placement.templateVersion() + ":" + placement.sourceKey();
            if (sourceKeys.putIfAbsent(key, placement) != null) {
                throw new IllegalArgumentException("duplicate entity source placement: " + key);
            }
        }
        this.placements = List.copyOf(placements);
    }

    static PaperResourceEntityPlacementCatalog loadResource(
            String resourcePath,
            ResourceSourceCatalog resourceCatalog
    ) {
        Objects.requireNonNull(resourceCatalog, "resourceCatalog");
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        InputStream input = PaperResourceEntityPlacementCatalog.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new IllegalArgumentException("Entity placement catalog does not exist: " + resourcePath);
        }

        ObjectMapper mapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
        final RawCatalog raw;
        try (input) {
            raw = mapper.readValue(input, RawCatalog.class);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid entity placement catalog: " + resourcePath, exception);
        }
        if (raw == null || raw.placements() == null) {
            throw new IllegalArgumentException("Entity placement catalog must contain placements array");
        }
        if (raw.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported entity placement schema_version " + raw.schemaVersion()
                            + "; expected " + SCHEMA_VERSION
            );
        }

        ArrayList<PaperResourceEntityPlacement> placements = new ArrayList<>(raw.placements().size());
        for (int index = 0; index < raw.placements().size(); index++) {
            RawPlacement value = raw.placements().get(index);
            if (value == null) {
                throw new IllegalArgumentException("placements[" + index + "] must not be null");
            }
            EntityType entityType;
            try {
                entityType = EntityType.valueOf(value.entityType());
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new IllegalArgumentException(
                        "Unknown entity_type at placements[" + index + "]: " + value.entityType(), exception
                );
            }
            PaperResourceEntityPlacement placement = new PaperResourceEntityPlacement(
                    value.sourceKey(),
                    value.definitionId(),
                    value.zoneId(),
                    value.templateVersion(),
                    value.worldName(),
                    value.x(),
                    value.y(),
                    value.z(),
                    entityType,
                    Duration.ofMillis(value.pendingLeaseMillis()),
                    Duration.ofMillis(value.activeLifetimeMillis())
            );
            ResourceSourceDefinition definition = resourceCatalog.require(placement.definitionId());
            if (!definition.zoneId().equals(placement.zoneId())
                    || !definition.templateVersion().equals(placement.templateVersion())) {
                throw new IllegalArgumentException(
                        "Entity placement zone/template does not match source definition: " + placement.sourceKey()
                );
            }
            placements.add(placement);
        }
        return new PaperResourceEntityPlacementCatalog(placements);
    }

    List<PaperResourceEntityPlacement> forZone(String zoneId, String templateVersion) {
        return placements.stream()
                .filter(placement -> placement.zoneId().equals(zoneId)
                        && placement.templateVersion().equals(templateVersion))
                .toList();
    }

    private record RawCatalog(
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("placements") List<RawPlacement> placements
    ) { }

    private record RawPlacement(
            @JsonProperty("source_key") String sourceKey,
            @JsonProperty("definition_id") String definitionId,
            @JsonProperty("zone_id") String zoneId,
            @JsonProperty("template_version") String templateVersion,
            @JsonProperty("world_name") String worldName,
            @JsonProperty("x") double x,
            @JsonProperty("y") double y,
            @JsonProperty("z") double z,
            @JsonProperty("entity_type") String entityType,
            @JsonProperty("pending_lease_millis") long pendingLeaseMillis,
            @JsonProperty("active_lifetime_millis") long activeLifetimeMillis
    ) { }
}

package io.github.kevinrabbe.minecraftserver.paper;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceCatalog;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceDefinition;
import org.bukkit.Material;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Version-controlled authored block placements. Coordinates are presentation; source keys carry logical identity. */
final class PaperResourceSourcePlacementCatalog {
    private static final int SCHEMA_VERSION = 1;

    private final Map<PaperResourceSourcePlacement.BlockKey, PaperResourceSourcePlacement> byBlock;
    private final List<PaperResourceSourcePlacement> placements;

    private PaperResourceSourcePlacementCatalog(List<PaperResourceSourcePlacement> placements) {
        LinkedHashMap<PaperResourceSourcePlacement.BlockKey, PaperResourceSourcePlacement> blockIndex = new LinkedHashMap<>();
        LinkedHashMap<String, PaperResourceSourcePlacement> sourceIndex = new LinkedHashMap<>();
        for (PaperResourceSourcePlacement placement : placements) {
            PaperResourceSourcePlacement previousBlock = blockIndex.putIfAbsent(placement.blockKey(), placement);
            if (previousBlock != null) {
                throw new IllegalArgumentException("duplicate resource placement block: " + placement.blockKey());
            }
            String logicalKey = placement.zoneId() + ":" + placement.templateVersion() + ":" + placement.sourceKey();
            PaperResourceSourcePlacement previousSource = sourceIndex.putIfAbsent(logicalKey, placement);
            if (previousSource != null) {
                throw new IllegalArgumentException("duplicate resource source_key in one zone/template: " + logicalKey);
            }
        }
        this.byBlock = Map.copyOf(blockIndex);
        this.placements = List.copyOf(placements);
    }

    static PaperResourceSourcePlacementCatalog loadResource(
            String resourcePath,
            ResourceSourceCatalog resourceCatalog
    ) {
        Objects.requireNonNull(resourceCatalog, "resourceCatalog");
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        InputStream input = PaperResourceSourcePlacementCatalog.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new IllegalArgumentException("Resource placement catalog does not exist: " + resourcePath);
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
            throw new IllegalArgumentException("Invalid resource placement catalog: " + resourcePath, exception);
        }
        if (raw == null || raw.placements() == null) {
            throw new IllegalArgumentException("Resource placement catalog must contain placements array");
        }
        if (raw.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported resource placement schema_version " + raw.schemaVersion()
                            + "; expected " + SCHEMA_VERSION
            );
        }

        ArrayList<PaperResourceSourcePlacement> placements = new ArrayList<>(raw.placements().size());
        for (int index = 0; index < raw.placements().size(); index++) {
            RawPlacement value = raw.placements().get(index);
            if (value == null) {
                throw new IllegalArgumentException("placements[" + index + "] must not be null");
            }
            Material material = Material.getMaterial(value.expectedBlock());
            if (material == null) {
                throw new IllegalArgumentException("Unknown block material at placements[" + index + "]: " + value.expectedBlock());
            }
            PaperResourceSourcePlacement placement = new PaperResourceSourcePlacement(
                    value.sourceKey(),
                    value.definitionId(),
                    value.zoneId(),
                    value.templateVersion(),
                    value.worldName(),
                    value.blockX(),
                    value.blockY(),
                    value.blockZ(),
                    material
            );
            ResourceSourceDefinition definition = resourceCatalog.require(placement.definitionId());
            if (!definition.zoneId().equals(placement.zoneId())
                    || !definition.templateVersion().equals(placement.templateVersion())) {
                throw new IllegalArgumentException(
                        "Placement zone/template does not match source definition: " + placement.sourceKey()
                );
            }
            placements.add(placement);
        }
        return new PaperResourceSourcePlacementCatalog(placements);
    }

    List<PaperResourceSourcePlacement> forZone(String zoneId, String templateVersion) {
        return placements.stream()
                .filter(placement -> placement.zoneId().equals(zoneId)
                        && placement.templateVersion().equals(templateVersion))
                .toList();
    }

    PaperResourceSourcePlacement find(String worldName, int x, int y, int z) {
        return byBlock.get(new PaperResourceSourcePlacement.BlockKey(worldName, x, y, z));
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
            @JsonProperty("block_x") int blockX,
            @JsonProperty("block_y") int blockY,
            @JsonProperty("block_z") int blockZ,
            @JsonProperty("expected_block") String expectedBlock
    ) { }
}

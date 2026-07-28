package io.github.kevinrabbe.minecraftserver.paper;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.kevinrabbe.minecraftserver.common.artifact.ArtifactDefinitionSnapshot;
import io.github.kevinrabbe.minecraftserver.common.artifact.ArtifactRepository;
import org.bukkit.Material;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Version-controlled Artifact bootstrap/visual catalog.
 *
 * <p>The JSON supplies stable identity and the initial location only. Once an artifact exists, PostgreSQL's current
 * location revision wins so a legitimate relocation is never silently reverted by stale content bytes.</p>
 */
final class PaperArtifactPlacementCatalog {
    private static final int SCHEMA_VERSION = 1;

    private final Map<BlockKey, PaperArtifactPlacement> byBlock;
    private final List<PaperArtifactPlacement> placements;

    private PaperArtifactPlacementCatalog(List<PaperArtifactPlacement> placements) {
        LinkedHashMap<BlockKey, PaperArtifactPlacement> blockIndex = new LinkedHashMap<>();
        HashSet<UUID> artifactIds = new HashSet<>();
        for (PaperArtifactPlacement placement : placements) {
            if (!artifactIds.add(placement.artifactId())) {
                throw new IllegalArgumentException("duplicate artifact_id in Paper placement catalog: " + placement.artifactId());
            }
            PaperArtifactPlacement previous = blockIndex.putIfAbsent(placement.blockKey(), placement);
            if (previous != null) {
                throw new IllegalArgumentException("multiple artifacts resolve to one physical block: " + placement.blockKey());
            }
        }
        this.byBlock = Map.copyOf(blockIndex);
        this.placements = List.copyOf(placements);
    }

    static PaperArtifactPlacementCatalog loadAndBootstrap(
            String resourcePath,
            ArtifactRepository artifacts
    ) throws SQLException {
        Objects.requireNonNull(artifacts, "artifacts");
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        InputStream input = PaperArtifactPlacementCatalog.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new IllegalArgumentException("Artifact placement catalog does not exist: " + resourcePath);
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
            throw new IllegalArgumentException("Invalid Artifact placement catalog: " + resourcePath, exception);
        }
        if (raw == null || raw.artifacts() == null) {
            throw new IllegalArgumentException("Artifact placement catalog must contain an artifacts array");
        }
        if (raw.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported Artifact placement schema_version " + raw.schemaVersion()
                            + "; expected " + SCHEMA_VERSION
            );
        }

        ArrayList<PaperArtifactPlacement> resolved = new ArrayList<>(raw.artifacts().size());
        HashSet<UUID> configuredIds = new HashSet<>();
        for (int index = 0; index < raw.artifacts().size(); index++) {
            RawArtifact value = raw.artifacts().get(index);
            if (value == null) {
                throw new IllegalArgumentException("artifacts[" + index + "] must not be null");
            }
            UUID artifactId = parseUuid(value.artifactId(), "artifact_id", index);
            UUID definitionOperationId = parseUuid(value.definitionOperationId(), "definition_operation_id", index);
            if (!configuredIds.add(artifactId)) {
                throw new IllegalArgumentException("duplicate artifact_id at artifacts[" + index + "]: " + artifactId);
            }
            Material expectedBlock = Material.getMaterial(value.expectedBlock());
            if (expectedBlock == null || !expectedBlock.isBlock() || expectedBlock == Material.AIR) {
                throw new IllegalArgumentException(
                        "Invalid Artifact expected_block at artifacts[" + index + "]: " + value.expectedBlock()
                );
            }

            ArtifactDefinitionSnapshot definition = artifacts.loadDefinition(artifactId).orElse(null);
            if (definition == null) {
                definition = artifacts.createArtifact(
                        definitionOperationId,
                        artifactId,
                        value.pointValue(),
                        value.pointPolicyVersion(),
                        value.enabled(),
                        requireNonBlank(value.initialWorldName(), "initial_world_name", index),
                        normalizeOptional(value.initialLogicalZoneId()),
                        value.initialBlockX(),
                        value.initialBlockY(),
                        value.initialBlockZ()
                );
            } else {
                if (definition.pointValue() != value.pointValue()
                        || definition.pointPolicyVersion() != value.pointPolicyVersion()) {
                    throw new IllegalStateException(
                            "Artifact point policy differs from version-controlled content for " + artifactId
                    );
                }
            }

            var location = definition.currentLocation();
            resolved.add(new PaperArtifactPlacement(
                    artifactId,
                    location.locationRevision(),
                    location.worldKey(),
                    location.logicalZoneId(),
                    location.blockX(),
                    location.blockY(),
                    location.blockZ(),
                    expectedBlock,
                    definition.enabled()
            ));
        }
        return new PaperArtifactPlacementCatalog(resolved);
    }

    List<PaperArtifactPlacement> all() {
        return placements;
    }

    PaperArtifactPlacement find(String worldName, int x, int y, int z) {
        return byBlock.get(new BlockKey(worldName, x, y, z));
    }

    private static UUID parseUuid(String value, String field, int index) {
        try {
            return UUID.fromString(requireNonBlank(value, field, index));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid " + field + " at artifacts[" + index + "]", exception);
        }
    }

    private static String requireNonBlank(String value, String field, int index) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank at artifacts[" + index + "]");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    record PaperArtifactPlacement(
            UUID artifactId,
            long locationRevision,
            String worldName,
            String logicalZoneId,
            int blockX,
            int blockY,
            int blockZ,
            Material expectedBlock,
            boolean enabled
    ) {
        PaperArtifactPlacement {
            artifactId = Objects.requireNonNull(artifactId, "artifactId");
            if (locationRevision < 1) {
                throw new IllegalArgumentException("locationRevision must be >= 1");
            }
            if (worldName == null || worldName.isBlank()) {
                throw new IllegalArgumentException("worldName must not be blank");
            }
            worldName = worldName.trim();
            expectedBlock = Objects.requireNonNull(expectedBlock, "expectedBlock");
        }

        BlockKey blockKey() {
            return new BlockKey(worldName, blockX, blockY, blockZ);
        }
    }

    record BlockKey(String worldName, int x, int y, int z) {
        BlockKey {
            if (worldName == null || worldName.isBlank()) {
                throw new IllegalArgumentException("worldName must not be blank");
            }
            worldName = worldName.trim();
        }
    }

    private record RawCatalog(
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("artifacts") List<RawArtifact> artifacts
    ) { }

    private record RawArtifact(
            @JsonProperty("artifact_id") String artifactId,
            @JsonProperty("definition_operation_id") String definitionOperationId,
            @JsonProperty("point_value") int pointValue,
            @JsonProperty("point_policy_version") int pointPolicyVersion,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("initial_world_name") String initialWorldName,
            @JsonProperty("initial_logical_zone_id") String initialLogicalZoneId,
            @JsonProperty("initial_block_x") int initialBlockX,
            @JsonProperty("initial_block_y") int initialBlockY,
            @JsonProperty("initial_block_z") int initialBlockZ,
            @JsonProperty("expected_block") String expectedBlock
    ) { }
}

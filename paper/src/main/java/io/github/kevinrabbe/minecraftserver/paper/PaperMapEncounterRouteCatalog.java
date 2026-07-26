package io.github.kevinrabbe.minecraftserver.paper;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityException;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict Paper-only mapping from persistent Map environment IDs to disposable encounter zone/template targets. */
final class PaperMapEncounterRouteCatalog {
    private static final int SCHEMA_VERSION = 1;

    private final Map<String, PaperMapEncounterRoute> byEnvironment;

    private PaperMapEncounterRouteCatalog(Map<String, PaperMapEncounterRoute> byEnvironment) {
        this.byEnvironment = Map.copyOf(byEnvironment);
    }

    static PaperMapEncounterRouteCatalog loadResource(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        InputStream input = PaperMapEncounterRouteCatalog.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new IllegalStateException("Map encounter route resource does not exist: " + resourcePath);
        }

        ObjectMapper mapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
        try (input) {
            RawCatalog raw = mapper.readValue(input, RawCatalog.class);
            if (raw == null || raw.routes() == null) {
                throw new IllegalStateException("Map encounter route catalog must contain routes array");
            }
            if (raw.schemaVersion() != SCHEMA_VERSION) {
                throw new IllegalStateException(
                        "Unsupported Map encounter route schema_version " + raw.schemaVersion()
                                + "; expected " + SCHEMA_VERSION
                );
            }

            LinkedHashMap<String, PaperMapEncounterRoute> routes = new LinkedHashMap<>();
            for (int index = 0; index < raw.routes().size(); index++) {
                RawRoute value = raw.routes().get(index);
                if (value == null) {
                    throw new IllegalStateException("routes[" + index + "] must not be null");
                }
                PaperMapEncounterRoute route = new PaperMapEncounterRoute(
                        value.environmentId(),
                        value.zoneId(),
                        value.templateVersion()
                );
                if (routes.putIfAbsent(route.environmentId(), route) != null) {
                    throw new IllegalStateException(
                            "Duplicate Map encounter environment route: " + route.environmentId()
                    );
                }
            }
            if (routes.isEmpty()) {
                throw new IllegalStateException("Map encounter route catalog must not be empty");
            }
            return new PaperMapEncounterRouteCatalog(routes);
        } catch (IOException exception) {
            throw new IllegalStateException("Invalid Map encounter route JSON: " + resourcePath, exception);
        }
    }

    PaperMapEncounterRoute require(String environmentId) {
        if (environmentId == null || environmentId.isBlank()) {
            throw new IllegalArgumentException("environmentId must not be blank");
        }
        PaperMapEncounterRoute route = byEnvironment.get(environmentId.trim());
        if (route == null) {
            throw new MapAuthorityException("No Paper encounter route for Map environment: " + environmentId.trim());
        }
        return route;
    }

    private record RawCatalog(
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("routes") List<RawRoute> routes
    ) { }

    private record RawRoute(
            @JsonProperty("environment_id") String environmentId,
            @JsonProperty("zone_id") String zoneId,
            @JsonProperty("template_version") String templateVersion
    ) { }
}

package io.github.kevinrabbe.minecraftserver.common.economy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Strict JSON loader for small Bazaar tuning values that must not be hardcoded into Paper. */
public final class BazaarPolicyLoader {
    public static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    public BazaarPolicy load(Path path) {
        Objects.requireNonNull(path, "path");
        try (InputStream input = Files.newInputStream(path)) {
            return load(input, path.toString());
        } catch (IOException exception) {
            throw new BazaarException("Could not read Bazaar policy: " + path, exception);
        }
    }

    public BazaarPolicy loadResource(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        InputStream input = BazaarPolicyLoader.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new BazaarException("Bazaar policy resource does not exist: " + resourcePath);
        }
        try (input) {
            return load(input, resourcePath);
        } catch (IOException exception) {
            throw new BazaarException("Could not close Bazaar policy resource: " + resourcePath, exception);
        }
    }

    BazaarPolicy load(InputStream input, String sourceDescription) {
        Objects.requireNonNull(input, "input");
        String source = sourceDescription == null || sourceDescription.isBlank()
                ? "<stream>"
                : sourceDescription.trim();
        final RawPolicy raw;
        try {
            raw = objectMapper.readValue(input, RawPolicy.class);
        } catch (IOException exception) {
            throw new BazaarException("Invalid Bazaar policy JSON in " + source, exception);
        }
        if (raw == null) {
            throw new BazaarException("Bazaar policy must not be null: " + source);
        }
        if (raw.schemaVersion() != SCHEMA_VERSION) {
            throw new BazaarException(
                    "Unsupported Bazaar policy schema_version " + raw.schemaVersion()
                            + " in " + source + "; expected " + SCHEMA_VERSION
            );
        }
        try {
            return new BazaarPolicy(raw.executionFeeBasisPoints(), raw.maxFillsPerMatch());
        } catch (IllegalArgumentException exception) {
            throw new BazaarException("Invalid Bazaar policy in " + source + ": " + exception.getMessage(), exception);
        }
    }

    private record RawPolicy(
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("execution_fee_basis_points") int executionFeeBasisPoints,
            @JsonProperty("max_fills_per_match") int maxFillsPerMatch
    ) { }
}

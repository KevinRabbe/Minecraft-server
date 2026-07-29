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

/** Strict JSON loader for ordinary-PvE pocket-Coin death-loss tuning. */
public final class PveDeathLossConfigLoader {
    public static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    public PveDeathLossConfig load(Path path) {
        Objects.requireNonNull(path, "path");
        try (InputStream input = Files.newInputStream(path)) {
            return load(input, path.toString());
        } catch (IOException exception) {
            throw new CoinWalletException("Could not read PvE death-loss config: " + path, exception);
        }
    }

    public PveDeathLossConfig loadResource(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        InputStream input = PveDeathLossConfigLoader.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new CoinWalletException("PvE death-loss config resource does not exist: " + resourcePath);
        }
        try (input) {
            return load(input, resourcePath);
        } catch (IOException exception) {
            throw new CoinWalletException("Could not close PvE death-loss config resource: " + resourcePath, exception);
        }
    }

    PveDeathLossConfig load(InputStream input, String sourceDescription) {
        Objects.requireNonNull(input, "input");
        String source = sourceDescription == null || sourceDescription.isBlank()
                ? "<stream>"
                : sourceDescription.trim();
        final RawConfig raw;
        try {
            raw = objectMapper.readValue(input, RawConfig.class);
        } catch (IOException exception) {
            throw new CoinWalletException("Invalid PvE death-loss config JSON in " + source, exception);
        }
        if (raw == null) {
            throw new CoinWalletException("PvE death-loss config must not be null: " + source);
        }
        if (raw.schemaVersion() != SCHEMA_VERSION) {
            throw new CoinWalletException(
                    "Unsupported PvE death-loss schema_version " + raw.schemaVersion()
                            + " in " + source + "; expected " + SCHEMA_VERSION
            );
        }
        try {
            return new PveDeathLossConfig(raw.enabled(), raw.policyVersion(), raw.lossBasisPoints());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new CoinWalletException(
                    "Invalid PvE death-loss config in " + source + ": " + exception.getMessage(),
                    exception
            );
        }
    }

    private record RawConfig(
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("policy_version") String policyVersion,
            @JsonProperty("loss_basis_points") int lossBasisPoints
    ) { }
}

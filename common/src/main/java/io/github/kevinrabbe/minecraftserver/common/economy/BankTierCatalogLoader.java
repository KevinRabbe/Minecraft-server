package io.github.kevinrabbe.minecraftserver.common.economy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Strict JSON loader for Bank Manager progression tuning. */
public final class BankTierCatalogLoader {
    public static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    public BankTierCatalog load(Path path) {
        Objects.requireNonNull(path, "path");
        try (InputStream input = Files.newInputStream(path)) {
            return load(input, path.toString());
        } catch (IOException exception) {
            throw new BankManagerException("Could not read Bank Manager tier catalog: " + path, exception);
        }
    }

    public BankTierCatalog loadResource(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        InputStream input = BankTierCatalogLoader.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new BankManagerException("Bank Manager tier resource does not exist: " + resourcePath);
        }
        try (input) {
            return load(input, resourcePath);
        } catch (IOException exception) {
            throw new BankManagerException("Could not close Bank Manager tier resource: " + resourcePath, exception);
        }
    }

    BankTierCatalog load(InputStream input, String sourceDescription) {
        Objects.requireNonNull(input, "input");
        String source = sourceDescription == null || sourceDescription.isBlank()
                ? "<stream>"
                : sourceDescription.trim();
        final RawCatalog raw;
        try {
            raw = objectMapper.readValue(input, RawCatalog.class);
        } catch (IOException exception) {
            throw new BankManagerException("Invalid Bank Manager tier JSON in " + source, exception);
        }
        if (raw == null || raw.tiers() == null) {
            throw new BankManagerException("Bank Manager catalog must contain a tiers array: " + source);
        }
        if (raw.schemaVersion() != SCHEMA_VERSION) {
            throw new BankManagerException(
                    "Unsupported Bank Manager schema_version " + raw.schemaVersion()
                            + " in " + source + "; expected " + SCHEMA_VERSION
            );
        }

        ArrayList<BankTierDefinition> tiers = new ArrayList<>(raw.tiers().size());
        for (int index = 0; index < raw.tiers().size(); index++) {
            RawTier value = raw.tiers().get(index);
            if (value == null) {
                throw new BankManagerException("tiers[" + index + "] must not be null in " + source);
            }
            try {
                tiers.add(new BankTierDefinition(
                        value.tier(),
                        value.capacityMinor(),
                        value.upgradeCostMinor(),
                        value.dailyInterestBasisPoints()
                ));
            } catch (IllegalArgumentException exception) {
                throw new BankManagerException(
                        "Invalid Bank Manager tier at tiers[" + index + "] in " + source + ": "
                                + exception.getMessage(),
                        exception
                );
            }
        }
        return new BankTierCatalog(tiers);
    }

    private record RawCatalog(
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("tiers") List<RawTier> tiers
    ) { }

    private record RawTier(
            @JsonProperty("tier") int tier,
            @JsonProperty("capacity_minor") long capacityMinor,
            @JsonProperty("upgrade_cost_minor") long upgradeCostMinor,
            @JsonProperty("daily_interest_basis_points") int dailyInterestBasisPoints
    ) { }
}

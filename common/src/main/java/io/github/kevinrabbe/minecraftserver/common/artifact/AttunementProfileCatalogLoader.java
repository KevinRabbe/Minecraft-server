package io.github.kevinrabbe.minecraftserver.common.artifact;

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

/** Strict versioned JSON loader for the deliberately small set of attunement profiles. */
public final class AttunementProfileCatalogLoader {
    public static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public AttunementProfileCatalog load(Path path) {
        Objects.requireNonNull(path, "path");
        try (InputStream input = Files.newInputStream(path)) {
            return load(input, path.toString());
        } catch (IOException exception) {
            throw new AttunementException("Could not read attunement profile catalog: " + path, exception);
        }
    }

    public AttunementProfileCatalog loadResource(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        InputStream input = AttunementProfileCatalogLoader.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new AttunementException("Attunement profile resource does not exist: " + resourcePath);
        }
        try (input) {
            return load(input, resourcePath);
        } catch (IOException exception) {
            throw new AttunementException("Could not close attunement profile resource: " + resourcePath, exception);
        }
    }

    AttunementProfileCatalog load(InputStream input, String sourceDescription) {
        Objects.requireNonNull(input, "input");
        String source = sourceDescription == null || sourceDescription.isBlank()
                ? "<stream>"
                : sourceDescription.trim();
        final RawCatalog raw;
        try {
            raw = objectMapper.readValue(input, RawCatalog.class);
        } catch (IOException exception) {
            throw new AttunementException("Invalid attunement profile JSON in " + source, exception);
        }
        if (raw == null || raw.profiles() == null) {
            throw new AttunementException("Attunement profile catalog must contain a profiles array: " + source);
        }
        if (raw.schemaVersion() != SCHEMA_VERSION) {
            throw new AttunementException(
                    "Unsupported attunement profile schema_version " + raw.schemaVersion()
                            + " in " + source + "; expected " + SCHEMA_VERSION
            );
        }

        ArrayList<AttunementProfileDefinition> definitions = new ArrayList<>(raw.profiles().size());
        for (int index = 0; index < raw.profiles().size(); index++) {
            RawProfile value = raw.profiles().get(index);
            if (value == null) {
                throw new AttunementException("profiles[" + index + "] must not be null in " + source);
            }
            try {
                definitions.add(new AttunementProfileDefinition(value.profileId(), value.statKey()));
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new AttunementException(
                        "Invalid attunement profile at profiles[" + index + "] in " + source + ": "
                                + exception.getMessage(),
                        exception
                );
            }
        }
        return new AttunementProfileCatalog(definitions);
    }

    private record RawCatalog(
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("profiles") List<RawProfile> profiles
    ) { }

    private record RawProfile(
            @JsonProperty("profile_id") String profileId,
            @JsonProperty("stat_key") String statKey
    ) { }
}

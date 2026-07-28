package io.github.kevinrabbe.minecraftserver.common.progression;

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

/** Strict versioned JSON loader for skill XP curves. */
public final class SkillProgressionCatalogLoader {
    public static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    public SkillProgressionCatalog load(Path path) {
        Objects.requireNonNull(path, "path");
        try (InputStream input = Files.newInputStream(path)) {
            return load(input, path.toString());
        } catch (IOException exception) {
            throw new SkillProgressionException("Could not read skill catalog: " + path, exception);
        }
    }

    public SkillProgressionCatalog loadResource(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        InputStream input = SkillProgressionCatalogLoader.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new SkillProgressionException("Skill catalog resource does not exist: " + resourcePath);
        }
        try (input) {
            return load(input, resourcePath);
        } catch (IOException exception) {
            throw new SkillProgressionException("Could not close skill catalog resource: " + resourcePath, exception);
        }
    }

    SkillProgressionCatalog load(InputStream input, String sourceDescription) {
        Objects.requireNonNull(input, "input");
        String source = sourceDescription == null || sourceDescription.isBlank()
                ? "<stream>"
                : sourceDescription.trim();
        final RawCatalog raw;
        try {
            raw = objectMapper.readValue(input, RawCatalog.class);
        } catch (IOException exception) {
            throw new SkillProgressionException("Invalid skill catalog JSON in " + source, exception);
        }
        if (raw == null || raw.skills() == null) {
            throw new SkillProgressionException("Skill catalog must contain a skills array: " + source);
        }
        if (raw.schemaVersion() != SCHEMA_VERSION) {
            throw new SkillProgressionException(
                    "Unsupported skill catalog schema_version " + raw.schemaVersion()
                            + " in " + source + "; expected " + SCHEMA_VERSION
            );
        }

        ArrayList<SkillProgressionDefinition> definitions = new ArrayList<>(raw.skills().size());
        for (int index = 0; index < raw.skills().size(); index++) {
            RawSkill skill = raw.skills().get(index);
            if (skill == null) {
                throw new SkillProgressionException("skills[" + index + "] must not be null in " + source);
            }
            try {
                definitions.add(new SkillProgressionDefinition(
                        new SkillId(skill.skillId()),
                        skill.cumulativeExperienceByLevel()
                ));
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new SkillProgressionException(
                        "Invalid skill definition at skills[" + index + "] in " + source + ": " + exception.getMessage(),
                        exception
                );
            }
        }
        return new SkillProgressionCatalog(definitions);
    }

    private record RawCatalog(
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("skills") List<RawSkill> skills
    ) { }

    private record RawSkill(
            @JsonProperty("skill_id") String skillId,
            @JsonProperty("cumulative_experience_by_level") List<Long> cumulativeExperienceByLevel
    ) { }
}

package io.github.kevinrabbe.minecraftserver.common.progression;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class SkillProgressionCatalog {
    private final Map<SkillId, SkillProgressionDefinition> definitions;

    public SkillProgressionCatalog(Collection<SkillProgressionDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        TreeMap<SkillId, SkillProgressionDefinition> ordered = new TreeMap<>((left, right) ->
                left.value().compareTo(right.value()));
        for (SkillProgressionDefinition definition : definitions) {
            Objects.requireNonNull(definition, "definition");
            if (ordered.put(definition.skillId(), definition) != null) {
                throw new IllegalArgumentException("duplicate skill progression: " + definition.skillId());
            }
        }
        if (ordered.isEmpty()) {
            throw new IllegalArgumentException("skill progression catalog must not be empty");
        }
        this.definitions = Map.copyOf(ordered);
    }

    public SkillProgressionDefinition require(SkillId skillId) {
        Objects.requireNonNull(skillId, "skillId");
        SkillProgressionDefinition definition = definitions.get(skillId);
        if (definition == null) {
            throw new SkillProgressionException("Unknown skill: " + skillId);
        }
        return definition;
    }
}

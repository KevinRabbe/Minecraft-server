package io.github.kevinrabbe.minecraftserver.common.artifact;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Startup-validated catalog of the small set of legal attunement profiles. */
public final class AttunementProfileCatalog {
    private final Map<String, AttunementProfileDefinition> definitions;

    public AttunementProfileCatalog(Collection<AttunementProfileDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        LinkedHashMap<String, AttunementProfileDefinition> indexed = new LinkedHashMap<>();
        for (AttunementProfileDefinition definition : definitions) {
            AttunementProfileDefinition prior = indexed.putIfAbsent(
                    Objects.requireNonNull(definition, "definition").profileId(), definition
            );
            if (prior != null) {
                throw new IllegalArgumentException("duplicate attunement profile: " + definition.profileId());
            }
        }
        if (indexed.isEmpty()) {
            throw new IllegalArgumentException("at least one attunement profile is required");
        }
        this.definitions = Collections.unmodifiableMap(indexed);
    }

    public AttunementProfileDefinition require(String profileId) {
        AttunementProfileDefinition definition = definitions.get(profileId);
        if (definition == null) {
            throw new AttunementException("Unknown attunement profile: " + profileId);
        }
        return definition;
    }

    public Collection<AttunementProfileDefinition> all() {
        return definitions.values();
    }
}

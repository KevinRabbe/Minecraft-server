package io.github.kevinrabbe.minecraftserver.common.world.resource;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable validated lookup for authorized renewable source definitions. */
public final class ResourceSourceCatalog {
    private final Map<String, ResourceSourceDefinition> definitions;

    public ResourceSourceCatalog(
            Collection<ResourceSourceDefinition> definitions,
            ItemCatalog itemCatalog,
            SkillProgressionCatalog skillCatalog
    ) {
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(itemCatalog, "itemCatalog");
        Objects.requireNonNull(skillCatalog, "skillCatalog");
        LinkedHashMap<String, ResourceSourceDefinition> indexed = new LinkedHashMap<>();
        for (ResourceSourceDefinition definition : definitions) {
            ResourceSourceDefinition nonNull = Objects.requireNonNull(definition, "definitions must not contain null");
            if (nonNull.hasCommodityReward()) {
                ItemDefinition commodity = itemCatalog.require(nonNull.commodityDefinitionId());
                if (commodity.identityKind() != ItemIdentityKind.COMMODITY) {
                    throw new IllegalArgumentException(
                            "resource source output must be COMMODITY: " + commodity.definitionId()
                    );
                }
            }
            if (nonNull.skillId() != null) {
                skillCatalog.require(nonNull.skillId());
            }
            if (indexed.putIfAbsent(nonNull.definitionId(), nonNull) != null) {
                throw new IllegalArgumentException("duplicate resource source definition: " + nonNull.definitionId());
            }
        }
        if (indexed.isEmpty()) {
            throw new IllegalArgumentException("resource source catalog must not be empty");
        }
        this.definitions = Map.copyOf(indexed);
    }

    public ResourceSourceDefinition require(String definitionId) {
        ResourceSourceDefinition definition = definitions.get(definitionId);
        if (definition == null) {
            throw new ResourceSourceException("Unknown resource source definition: " + definitionId);
        }
        return definition;
    }
}

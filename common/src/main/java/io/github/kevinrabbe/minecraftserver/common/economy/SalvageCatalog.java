package io.github.kevinrabbe.minecraftserver.common.economy;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable validated salvage rules for one balance/content release. */
public final class SalvageCatalog {
    private final Map<String, SalvageDefinition> definitions;

    public SalvageCatalog(Collection<SalvageDefinition> definitions, ItemCatalog itemCatalog) {
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(itemCatalog, "itemCatalog");
        LinkedHashMap<String, SalvageDefinition> indexed = new LinkedHashMap<>();
        for (SalvageDefinition definition : definitions) {
            SalvageDefinition nonNull = Objects.requireNonNull(definition, "definitions must not contain null");
            ItemDefinition source = itemCatalog.require(nonNull.itemDefinitionId());
            if (source.identityKind() != ItemIdentityKind.INDIVIDUAL) {
                throw new IllegalArgumentException("salvage source must be INDIVIDUAL: " + source.definitionId());
            }
            nonNull.commodityReturns().forEach((returnId, quantity) -> {
                ItemDefinition returned = itemCatalog.require(returnId);
                if (returned.identityKind() != ItemIdentityKind.COMMODITY) {
                    throw new IllegalArgumentException("salvage return must be COMMODITY: " + returned.definitionId());
                }
            });
            if (indexed.putIfAbsent(source.definitionId(), nonNull) != null) {
                throw new IllegalArgumentException("duplicate salvage definition: " + source.definitionId());
            }
        }
        this.definitions = Map.copyOf(indexed);
    }

    public SalvageDefinition require(String itemDefinitionId) {
        SalvageDefinition definition = definitions.get(itemDefinitionId);
        if (definition == null) {
            throw new SalvageException("Item definition is not salvageable: " + itemDefinitionId);
        }
        return definition;
    }
}

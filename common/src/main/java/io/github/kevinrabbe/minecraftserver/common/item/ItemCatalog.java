package io.github.kevinrabbe.minecraftserver.common.item;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable validated lookup of all item definitions in one content release. */
public final class ItemCatalog {
    private final Map<String, ItemDefinition> definitionsById;

    public ItemCatalog(Collection<ItemDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");

        LinkedHashMap<String, ItemDefinition> indexed = new LinkedHashMap<>();
        for (ItemDefinition definition : definitions) {
            ItemDefinition nonNullDefinition = Objects.requireNonNull(
                    definition,
                    "definitions must not contain null"
            );
            ItemDefinition previous = indexed.putIfAbsent(
                    nonNullDefinition.definitionId(),
                    nonNullDefinition
            );
            if (previous != null) {
                throw new ItemCatalogException(
                        "Duplicate item definition_id: " + nonNullDefinition.definitionId()
                );
            }
        }

        definitionsById = Map.copyOf(indexed);
    }

    public Optional<ItemDefinition> find(String definitionId) {
        if (definitionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(definitionsById.get(definitionId));
    }

    public ItemDefinition require(String definitionId) {
        return find(definitionId).orElseThrow(
                () -> new ItemCatalogException("Unknown item definition_id: " + definitionId)
        );
    }

    public List<ItemDefinition> definitions() {
        return List.copyOf(definitionsById.values());
    }

    public int size() {
        return definitionsById.size();
    }
}

package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Configured guaranteed low-value return for irreversibly salvaging one individualized item definition. */
public record SalvageDefinition(
        String itemDefinitionId,
        long coinReturnMinor,
        Map<String, Long> commodityReturns
) {
    public SalvageDefinition {
        if (itemDefinitionId == null || itemDefinitionId.isBlank()) {
            throw new IllegalArgumentException("itemDefinitionId must not be blank");
        }
        itemDefinitionId = itemDefinitionId.trim();
        if (coinReturnMinor < 0) {
            throw new IllegalArgumentException("coinReturnMinor must be >= 0");
        }
        Objects.requireNonNull(commodityReturns, "commodityReturns");
        if (commodityReturns.size() > 32) {
            throw new IllegalArgumentException("salvage may return at most 32 commodity definitions");
        }
        TreeMap<String, Long> normalized = new TreeMap<>();
        commodityReturns.forEach((definitionId, quantity) -> {
            if (definitionId == null || definitionId.isBlank()) {
                throw new IllegalArgumentException("commodity return definitionId must not be blank");
            }
            if (quantity == null || quantity <= 0) {
                throw new IllegalArgumentException("commodity return quantity must be > 0");
            }
            normalized.put(definitionId.trim(), quantity);
        });
        commodityReturns = Map.copyOf(normalized);
    }
}

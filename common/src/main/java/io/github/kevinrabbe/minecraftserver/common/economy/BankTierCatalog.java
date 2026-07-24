package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable validated Bank Manager progression configuration. */
public final class BankTierCatalog {
    private final Map<Integer, BankTierDefinition> tiers;

    public BankTierCatalog(Collection<BankTierDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        TreeMap<Integer, BankTierDefinition> ordered = new TreeMap<>();
        for (BankTierDefinition definition : definitions) {
            Objects.requireNonNull(definition, "definition");
            if (ordered.put(definition.tier(), definition) != null) {
                throw new IllegalArgumentException("duplicate bank tier: " + definition.tier());
            }
        }
        if (!ordered.containsKey(0)) {
            throw new IllegalArgumentException("bank tier catalog requires tier 0");
        }

        long previousCapacity = -1;
        int previousInterest = -1;
        int expectedTier = 0;
        for (BankTierDefinition definition : ordered.values()) {
            if (definition.tier() != expectedTier) {
                throw new IllegalArgumentException("bank tiers must be contiguous from 0; missing tier " + expectedTier);
            }
            if (definition.capacityMinor() <= previousCapacity) {
                throw new IllegalArgumentException("bank capacity must strictly increase by tier");
            }
            if (definition.dailyInterestBasisPoints() < previousInterest) {
                throw new IllegalArgumentException("bank interest must not decrease by tier");
            }
            previousCapacity = definition.capacityMinor();
            previousInterest = definition.dailyInterestBasisPoints();
            expectedTier++;
        }
        tiers = Map.copyOf(ordered);
    }

    public BankTierDefinition require(int tier) {
        BankTierDefinition definition = tiers.get(tier);
        if (definition == null) {
            throw new BankManagerException("Unknown Bank Manager tier: " + tier);
        }
        return definition;
    }

    public BankTierDefinition next(int currentTier) {
        return require(Math.addExact(currentTier, 1));
    }

    public int maxTier() {
        return tiers.keySet().stream().mapToInt(Integer::intValue).max().orElseThrow();
    }
}

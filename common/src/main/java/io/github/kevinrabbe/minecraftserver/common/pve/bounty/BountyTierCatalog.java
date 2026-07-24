package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable configured bounty families/tiers. */
public final class BountyTierCatalog {
    private final Map<Key, BountyTierDefinition> tiers;

    public BountyTierCatalog(Collection<BountyTierDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        Map<Key, BountyTierDefinition> values = new HashMap<>();
        for (BountyTierDefinition definition : definitions) {
            Objects.requireNonNull(definition, "definition");
            Key key = new Key(definition.familyId(), definition.tier());
            if (values.put(key, definition) != null) {
                throw new IllegalArgumentException("duplicate bounty tier: " + key);
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("bounty tier catalog must not be empty");
        }
        this.tiers = Map.copyOf(values);
    }

    public BountyTierDefinition require(BountyFamilyId familyId, int tier) {
        BountyTierDefinition definition = tiers.get(new Key(Objects.requireNonNull(familyId, "familyId"), tier));
        if (definition == null) {
            throw new BountyException("Unknown bounty tier " + familyId + "/" + tier);
        }
        return definition;
    }

    private record Key(BountyFamilyId familyId, int tier) {
    }
}

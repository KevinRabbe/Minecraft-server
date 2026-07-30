package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable configured bounty families/tiers with exact historical content versions. */
public final class BountyTierCatalog {
    private final Map<VersionedKey, BountyTierDefinition> versions;
    private final Map<Key, BountyTierDefinition> current;

    public BountyTierCatalog(Collection<BountyTierDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        Map<VersionedKey, BountyTierDefinition> versionValues = new HashMap<>();
        Map<Key, BountyTierDefinition> currentValues = new HashMap<>();
        for (BountyTierDefinition definition : definitions) {
            Objects.requireNonNull(definition, "definition");
            VersionedKey versionedKey = new VersionedKey(
                    definition.familyId(),
                    definition.tier(),
                    definition.contentVersion()
            );
            if (versionValues.put(versionedKey, definition) != null) {
                throw new IllegalArgumentException("duplicate bounty tier content version: " + versionedKey);
            }
            Key key = new Key(definition.familyId(), definition.tier());
            currentValues.merge(
                    key,
                    definition,
                    (left, right) -> left.contentVersion() > right.contentVersion() ? left : right
            );
        }
        if (versionValues.isEmpty()) {
            throw new IllegalArgumentException("bounty tier catalog must not be empty");
        }
        this.versions = Map.copyOf(versionValues);
        this.current = Map.copyOf(currentValues);
    }

    /** Highest configured content version for new contracts. */
    public BountyTierDefinition require(BountyFamilyId familyId, int tier) {
        BountyTierDefinition definition = current.get(new Key(Objects.requireNonNull(familyId, "familyId"), tier));
        if (definition == null) {
            throw new BountyException("Unknown bounty tier " + familyId + "/" + tier);
        }
        return definition;
    }

    /** Exact immutable content version for an already-started contract. */
    public BountyTierDefinition require(BountyFamilyId familyId, int tier, int contentVersion) {
        BountyTierDefinition definition = versions.get(new VersionedKey(
                Objects.requireNonNull(familyId, "familyId"),
                tier,
                contentVersion
        ));
        if (definition == null) {
            throw new BountyException(
                    "Unknown bounty tier content " + familyId + "/" + tier + "@" + contentVersion
            );
        }
        return definition;
    }

    private record Key(BountyFamilyId familyId, int tier) { }

    private record VersionedKey(BountyFamilyId familyId, int tier, int contentVersion) { }
}

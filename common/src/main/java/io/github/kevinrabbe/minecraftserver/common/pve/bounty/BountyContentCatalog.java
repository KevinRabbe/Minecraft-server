package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable launch content for bounty tiers, fixed rewards, and ordinary-PvE eligibility mapping. */
public final class BountyContentCatalog implements BountyRewardResolver {
    private final List<BountyTierDefinition> definitions;
    private final BountyTierCatalog tiers;
    private final Map<Key, Map<String, Long>> rewardsByTier;
    private final Map<String, BountyFamilyId> familyByEligibleSource;

    public BountyContentCatalog(Collection<ConfiguredTier> configuredTiers) {
        Objects.requireNonNull(configuredTiers, "configuredTiers");
        if (configuredTiers.isEmpty()) {
            throw new BountyException("bounty content catalog must not be empty");
        }

        ArrayList<BountyTierDefinition> tierDefinitions = new ArrayList<>();
        LinkedHashMap<Key, Map<String, Long>> rewards = new LinkedHashMap<>();
        LinkedHashMap<String, BountyFamilyId> sourceFamilies = new LinkedHashMap<>();
        for (ConfiguredTier configured : configuredTiers) {
            Objects.requireNonNull(configured, "configured tier");
            BountyTierDefinition definition = configured.definition();
            Key key = new Key(definition.familyId(), definition.tier());
            if (rewards.putIfAbsent(key, configured.fixedRewards()) != null) {
                throw new BountyException(
                        "duplicate bounty tier content: " + definition.familyId().value() + "/" + definition.tier()
                );
            }
            tierDefinitions.add(definition);
            for (String sourceId : configured.eligibleSourceIds()) {
                BountyFamilyId previous = sourceFamilies.putIfAbsent(sourceId, definition.familyId());
                if (previous != null && !previous.equals(definition.familyId())) {
                    throw new BountyException(
                            "ordinary PvE source " + sourceId + " is assigned to multiple bounty families"
                    );
                }
            }
        }

        this.definitions = List.copyOf(tierDefinitions);
        this.tiers = new BountyTierCatalog(this.definitions);
        this.rewardsByTier = Map.copyOf(rewards);
        this.familyByEligibleSource = Map.copyOf(sourceFamilies);
    }

    public BountyTierCatalog tiers() {
        return tiers;
    }

    public List<BountyTierDefinition> definitions() {
        return definitions;
    }

    public Set<String> eligibleSourceDefinitionIds() {
        return familyByEligibleSource.keySet();
    }

    public Optional<BountyFamilyId> eligibleFamilyForSource(String sourceDefinitionId) {
        if (sourceDefinitionId == null) return Optional.empty();
        return Optional.ofNullable(familyByEligibleSource.get(sourceDefinitionId.trim()));
    }

    @Override
    public Map<String, Long> resolve(java.util.UUID contractId, BountyTierDefinition tierDefinition) {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(tierDefinition, "tierDefinition");
        Map<String, Long> rewards = rewardsByTier.get(new Key(tierDefinition.familyId(), tierDefinition.tier()));
        if (rewards == null) {
            throw new BountyException(
                    "No configured rewards for bounty tier "
                            + tierDefinition.familyId().value() + "/" + tierDefinition.tier()
            );
        }
        return rewards;
    }

    public record ConfiguredTier(
            BountyTierDefinition definition,
            List<String> eligibleSourceIds,
            Map<String, Long> fixedRewards
    ) {
        public ConfiguredTier {
            definition = Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(eligibleSourceIds, "eligibleSourceIds");
            if (eligibleSourceIds.isEmpty()) {
                throw new IllegalArgumentException("eligibleSourceIds must not be empty");
            }
            eligibleSourceIds = eligibleSourceIds.stream()
                    .map(value -> requireId(value, "eligibleSourceId"))
                    .distinct()
                    .sorted()
                    .toList();

            Objects.requireNonNull(fixedRewards, "fixedRewards");
            if (fixedRewards.isEmpty()) {
                throw new IllegalArgumentException("fixedRewards must not be empty");
            }
            LinkedHashMap<String, Long> normalizedRewards = new LinkedHashMap<>();
            fixedRewards.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        String definitionId = requireId(entry.getKey(), "rewardDefinitionId");
                        Long quantity = entry.getValue();
                        if (quantity == null || quantity <= 0) {
                            throw new IllegalArgumentException("fixed reward quantity must be > 0: " + definitionId);
                        }
                        normalizedRewards.put(definitionId, quantity);
                    });
            fixedRewards = Map.copyOf(normalizedRewards);

            if (!fixedRewards.keySet().stream().allMatch(definition.materialDefinitionIds()::contains)
                    || definition.materialDefinitionIds().size() != fixedRewards.size()) {
                throw new IllegalArgumentException(
                        "tier materialDefinitionIds must exactly match configured fixed reward definitions"
                );
            }
        }

        private static String requireId(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            String normalized = value.trim();
            if (!normalized.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
                throw new IllegalArgumentException(field + " has invalid format: " + normalized);
            }
            return normalized;
        }
    }

    private record Key(BountyFamilyId familyId, int tier) { }
}

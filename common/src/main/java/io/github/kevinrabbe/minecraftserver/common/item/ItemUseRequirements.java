package io.github.kevinrabbe.minecraftserver.common.item;

import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Type-level use/equip requirements. These never change ownership, storage, trade, salvage, or economic acquisition.
 */
public record ItemUseRequirements(List<ItemSkillRequirement> skillRequirements) {
    public static final ItemUseRequirements NONE = new ItemUseRequirements(List.of());

    public ItemUseRequirements {
        skillRequirements = List.copyOf(Objects.requireNonNull(skillRequirements, "skillRequirements"));
        Set<SkillId> seen = new HashSet<>();
        for (ItemSkillRequirement requirement : skillRequirements) {
            ItemSkillRequirement nonNull = Objects.requireNonNull(
                    requirement,
                    "skillRequirements must not contain null"
            );
            if (!seen.add(nonNull.skillId())) {
                throw new IllegalArgumentException("duplicate item use skill requirement: " + nonNull.skillId());
            }
        }
        skillRequirements = skillRequirements.stream().sorted().toList();
    }

    public ItemUseRequirements(Collection<ItemSkillRequirement> skillRequirements) {
        this(List.copyOf(Objects.requireNonNull(skillRequirements, "skillRequirements")));
    }

    public boolean unrestricted() {
        return skillRequirements.isEmpty();
    }

    /** Returns every unmet requirement in stable skill-id order; missing player skill rows count as level 0. */
    public List<ItemSkillRequirement> unmet(Map<SkillId, Integer> playerLevels) {
        Objects.requireNonNull(playerLevels, "playerLevels");
        ArrayList<ItemSkillRequirement> unmet = new ArrayList<>();
        for (ItemSkillRequirement requirement : skillRequirements) {
            Integer rawLevel = playerLevels.get(requirement.skillId());
            int level = rawLevel == null ? 0 : rawLevel;
            if (level < 0) {
                throw new IllegalArgumentException("player skill level must be >= 0 for " + requirement.skillId());
            }
            if (level < requirement.minimumLevel()) {
                unmet.add(requirement);
            }
        }
        return List.copyOf(unmet);
    }

    public boolean allows(Map<SkillId, Integer> playerLevels) {
        return unmet(playerLevels).isEmpty();
    }
}

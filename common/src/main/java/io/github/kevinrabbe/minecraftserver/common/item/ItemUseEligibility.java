package io.github.kevinrabbe.minecraftserver.common.item;

import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Read-only use/equip decision derived from type-level requirements and current authoritative skill levels. */
public record ItemUseEligibility(
        String definitionId,
        Map<SkillId, Integer> currentSkillLevels,
        List<ItemSkillRequirement> unmetRequirements
) {
    public ItemUseEligibility {
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        definitionId = definitionId.trim();
        currentSkillLevels = Map.copyOf(Objects.requireNonNull(currentSkillLevels, "currentSkillLevels"));
        unmetRequirements = List.copyOf(Objects.requireNonNull(unmetRequirements, "unmetRequirements"));
    }

    public boolean allowed() {
        return unmetRequirements.isEmpty();
    }
}

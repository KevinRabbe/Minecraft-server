package io.github.kevinrabbe.minecraftserver.common.item;

import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionQueryRepository;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Read-only common service for skill-based item use/equip eligibility. */
public final class ItemUseEligibilityService {
    private final ItemCatalog itemCatalog;
    private final SkillProgressionQueryRepository skills;

    public ItemUseEligibilityService(
            ItemCatalog itemCatalog,
            SkillProgressionQueryRepository skills
    ) {
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.skills = Objects.requireNonNull(skills, "skills");
    }

    public ItemUseEligibility evaluate(UUID playerId, String definitionId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        ItemDefinition definition = itemCatalog.require(definitionId);
        ItemUseRequirements requirements = definition.useRequirements();
        if (requirements.unrestricted()) {
            return new ItemUseEligibility(definition.definitionId(), Map.of(), List.of());
        }

        List<SkillId> requiredSkills = requirements.skillRequirements().stream()
                .map(ItemSkillRequirement::skillId)
                .toList();
        Map<SkillId, Integer> levels = skills.loadLevels(playerId, requiredSkills);
        List<ItemSkillRequirement> unmet = requirements.unmet(levels);

        LinkedHashMap<SkillId, Integer> relevantLevels = new LinkedHashMap<>();
        for (ItemSkillRequirement requirement : requirements.skillRequirements()) {
            relevantLevels.put(requirement.skillId(), levels.getOrDefault(requirement.skillId(), 0));
        }
        return new ItemUseEligibility(
                definition.definitionId(),
                relevantLevels,
                unmet
        );
    }
}

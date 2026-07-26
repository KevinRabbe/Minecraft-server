package io.github.kevinrabbe.minecraftserver.legacy;

import org.bukkit.Material;

import java.util.Objects;

/** One identity-free frozen Clan-War snapshot row resolved to a concrete Minecraft-1.8 representation. */
final class LegacyClanWarRepresentedItem {
    private final int participantIndex;
    private final int loadoutItemIndex;
    private final String definitionId;
    private final Material material;

    LegacyClanWarRepresentedItem(
            int participantIndex,
            int loadoutItemIndex,
            String definitionId,
            Material material
    ) {
        if (participantIndex < 0 || loadoutItemIndex < 0) {
            throw new IllegalArgumentException("represented Clan-War indexes must be >= 0");
        }
        if (definitionId == null || definitionId.trim().isEmpty()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        this.participantIndex = participantIndex;
        this.loadoutItemIndex = loadoutItemIndex;
        this.definitionId = definitionId.trim();
        this.material = Objects.requireNonNull(material, "material");
    }

    int getParticipantIndex() {
        return participantIndex;
    }

    int getLoadoutItemIndex() {
        return loadoutItemIndex;
    }

    String getDefinitionId() {
        return definitionId;
    }

    Material getMaterial() {
        return material;
    }
}

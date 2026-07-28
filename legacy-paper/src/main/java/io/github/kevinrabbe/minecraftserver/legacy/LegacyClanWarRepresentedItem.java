package io.github.kevinrabbe.minecraftserver.legacy;

/** One identity-free frozen Clan-War snapshot row resolved to a concrete Minecraft-1.8 material id. */
final class LegacyClanWarRepresentedItem {
    private final int participantIndex;
    private final int loadoutItemIndex;
    private final String definitionId;
    private final String materialId;

    LegacyClanWarRepresentedItem(
            int participantIndex,
            int loadoutItemIndex,
            String definitionId,
            String materialId
    ) {
        if (participantIndex < 0 || loadoutItemIndex < 0) {
            throw new IllegalArgumentException("represented Clan-War indexes must be >= 0");
        }
        if (definitionId == null || definitionId.trim().isEmpty()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        if (materialId == null || materialId.trim().isEmpty()) {
            throw new IllegalArgumentException("materialId must not be blank");
        }
        this.participantIndex = participantIndex;
        this.loadoutItemIndex = loadoutItemIndex;
        this.definitionId = definitionId.trim();
        this.materialId = materialId.trim();
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

    String getMaterialId() {
        return materialId;
    }
}

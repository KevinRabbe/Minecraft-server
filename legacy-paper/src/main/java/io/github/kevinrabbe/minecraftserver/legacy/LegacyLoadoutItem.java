package io.github.kevinrabbe.minecraftserver.legacy;

/**
 * One identity-free item from the immutable execution-scoped Clan-War loadout snapshot.
 * Persistent MMO item identity never enters the legacy runtime model.
 */
final class LegacyLoadoutItem {
    private final int participantIndex;
    private final int loadoutItemIndex;
    private final String definitionId;
    private final String rollStateJson;
    private final int upgradeLevel;

    LegacyLoadoutItem(
            int participantIndex,
            int loadoutItemIndex,
            String definitionId,
            String rollStateJson,
            int upgradeLevel
    ) {
        if (participantIndex < 0) {
            throw new IllegalArgumentException("participantIndex must be >= 0");
        }
        if (loadoutItemIndex < 0) {
            throw new IllegalArgumentException("loadoutItemIndex must be >= 0");
        }
        if (upgradeLevel < 0) {
            throw new IllegalArgumentException("upgradeLevel must be >= 0");
        }
        this.participantIndex = participantIndex;
        this.loadoutItemIndex = loadoutItemIndex;
        this.definitionId = requireText(definitionId, "definitionId");
        this.rollStateJson = requireText(rollStateJson, "rollStateJson");
        this.upgradeLevel = upgradeLevel;
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

    String getRollStateJson() {
        return rollStateJson;
    }

    int getUpgradeLevel() {
        return upgradeLevel;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}

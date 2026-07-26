package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.Objects;

/**
 * Immutable preparation state for one disposable Clan-War execution.
 * Persistent item identity is absent; mutable objective progress is owned by the main-thread objective controller.
 */
final class LegacyClanWarRuntimeState {
    private final LegacyClanWarExecution war;
    private final LegacyClanWarMaterializationPlan materializationPlan;
    private final LegacyClanWarObjectiveSettings objectiveSettings;

    private LegacyClanWarRuntimeState(
            LegacyClanWarExecution war,
            LegacyClanWarMaterializationPlan materializationPlan,
            LegacyClanWarObjectiveSettings objectiveSettings
    ) {
        this.war = war;
        this.materializationPlan = materializationPlan;
        this.objectiveSettings = objectiveSettings;
    }

    static LegacyClanWarRuntimeState prepare(
            LegacyClanWarExecution war,
            LegacyClanWarLoadout loadout,
            LegacyClanWarRepresentationCatalog representationCatalog,
            LegacyClanWarArenaSettings arenaSettings,
            LegacyClanWarObjectiveSettings objectiveSettings
    ) {
        Objects.requireNonNull(war, "war");
        Objects.requireNonNull(loadout, "loadout");
        Objects.requireNonNull(representationCatalog, "representationCatalog");
        Objects.requireNonNull(arenaSettings, "arenaSettings");
        Objects.requireNonNull(objectiveSettings, "objectiveSettings");

        LegacyClanWarMaterializationPlan materializationPlan = LegacyClanWarMaterializationPlan.build(
                war,
                loadout,
                representationCatalog,
                arenaSettings
        );
        return new LegacyClanWarRuntimeState(
                war,
                materializationPlan,
                objectiveSettings
        );
    }

    LegacyClanWarExecution getWar() {
        return war;
    }

    LegacyClanWarMaterializationPlan getMaterializationPlan() {
        return materializationPlan;
    }

    LegacyClanWarRepresentationPlan getRepresentationPlan() {
        return materializationPlan.getRepresentationPlan();
    }

    LegacyClanWarObjectiveSettings getObjectiveSettings() {
        return objectiveSettings;
    }
}

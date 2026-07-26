package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.Objects;

/**
 * Immutable-preparation shell plus mutable objective state for one disposable Clan-War execution.
 * Persistent item identity is absent; combat cannot start merely because this object exists.
 */
final class LegacyClanWarRuntimeState {
    private final LegacyClanWarExecution war;
    private final LegacyClanWarMaterializationPlan materializationPlan;
    private final LegacyClanWarObjective objective;
    private final LegacyClanWarObjectiveSettings objectiveSettings;

    private LegacyClanWarRuntimeState(
            LegacyClanWarExecution war,
            LegacyClanWarMaterializationPlan materializationPlan,
            LegacyClanWarObjective objective,
            LegacyClanWarObjectiveSettings objectiveSettings
    ) {
        this.war = war;
        this.materializationPlan = materializationPlan;
        this.objective = objective;
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
                new LegacyClanWarObjective(war, objectiveSettings),
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

    LegacyClanWarObjective getObjective() {
        return objective;
    }

    LegacyClanWarObjectiveSettings getObjectiveSettings() {
        return objectiveSettings;
    }
}

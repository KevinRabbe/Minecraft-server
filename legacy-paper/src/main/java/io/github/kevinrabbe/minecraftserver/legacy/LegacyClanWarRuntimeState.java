package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.Objects;

/**
 * Immutable-preparation shell plus mutable objective state for one disposable Clan-War execution.
 * Persistent item identity is absent; combat cannot start merely because this object exists.
 */
final class LegacyClanWarRuntimeState {
    private final LegacyClanWarExecution war;
    private final LegacyClanWarRepresentationPlan representationPlan;
    private final LegacyClanWarObjective objective;
    private final LegacyClanWarObjectiveSettings objectiveSettings;

    private LegacyClanWarRuntimeState(
            LegacyClanWarExecution war,
            LegacyClanWarRepresentationPlan representationPlan,
            LegacyClanWarObjective objective,
            LegacyClanWarObjectiveSettings objectiveSettings
    ) {
        this.war = war;
        this.representationPlan = representationPlan;
        this.objective = objective;
        this.objectiveSettings = objectiveSettings;
    }

    static LegacyClanWarRuntimeState prepare(
            LegacyClanWarExecution war,
            LegacyClanWarLoadout loadout,
            LegacyClanWarRepresentationCatalog representationCatalog,
            LegacyClanWarObjectiveSettings objectiveSettings
    ) {
        Objects.requireNonNull(war, "war");
        Objects.requireNonNull(loadout, "loadout");
        Objects.requireNonNull(representationCatalog, "representationCatalog");
        Objects.requireNonNull(objectiveSettings, "objectiveSettings");

        LegacyClanWarRepresentationPlan plan = LegacyClanWarRepresentationPlan.build(
                war,
                loadout,
                representationCatalog
        );
        return new LegacyClanWarRuntimeState(
                war,
                plan,
                new LegacyClanWarObjective(war, objectiveSettings),
                objectiveSettings
        );
    }

    LegacyClanWarExecution getWar() {
        return war;
    }

    LegacyClanWarRepresentationPlan getRepresentationPlan() {
        return representationPlan;
    }

    LegacyClanWarObjective getObjective() {
        return objective;
    }

    LegacyClanWarObjectiveSettings getObjectiveSettings() {
        return objectiveSettings;
    }
}

package io.github.kevinrabbe.minecraftserver.legacy;

import org.bukkit.configuration.file.FileConfiguration;

/** Loads disposable-world control-point placement from local legacy runtime configuration. */
final class LegacyClanWarControlPointGeometryLoader {
    private LegacyClanWarControlPointGeometryLoader() { }

    static LegacyClanWarControlPointGeometry load(
            FileConfiguration config,
            LegacyClanWarObjectiveSettings objectiveSettings
    ) {
        if (config == null) throw new NullPointerException("config");
        if (objectiveSettings == null) throw new NullPointerException("objectiveSettings");
        return new LegacyClanWarControlPointGeometry(
                config.getDouble("clan-war.objective.center-x"),
                config.getDouble("clan-war.objective.center-y"),
                config.getDouble("clan-war.objective.center-z"),
                objectiveSettings.getRadiusBlocks()
        );
    }
}

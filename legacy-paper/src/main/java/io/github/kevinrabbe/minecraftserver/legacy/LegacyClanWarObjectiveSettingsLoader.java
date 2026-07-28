package io.github.kevinrabbe.minecraftserver.legacy;

import org.bukkit.configuration.file.FileConfiguration;

/** Loads cheap control-point tuning from the disposable legacy runtime's local configuration. */
final class LegacyClanWarObjectiveSettingsLoader {
    private LegacyClanWarObjectiveSettingsLoader() { }

    static LegacyClanWarObjectiveSettings load(FileConfiguration config) {
        if (config == null) throw new NullPointerException("config");
        return new LegacyClanWarObjectiveSettings(
                config.getDouble("clan-war.objective.radius-blocks"),
                config.getInt("clan-war.objective.evaluation-period-ticks"),
                config.getInt("clan-war.objective.uncontested-evaluations-to-win"),
                config.getInt("clan-war.objective.match-timeout-seconds")
        );
    }
}

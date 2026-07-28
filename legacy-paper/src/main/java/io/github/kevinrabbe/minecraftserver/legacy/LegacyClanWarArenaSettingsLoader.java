package io.github.kevinrabbe.minecraftserver.legacy;

import org.bukkit.configuration.file.FileConfiguration;

/** Loads disposable Clan-War arena geometry from local legacy runtime configuration. */
final class LegacyClanWarArenaSettingsLoader {
    private LegacyClanWarArenaSettingsLoader() { }

    static LegacyClanWarArenaSettings load(FileConfiguration config) {
        if (config == null) throw new NullPointerException("config");
        return new LegacyClanWarArenaSettings(
                config.getInt("clan-war.arena.origin-x"),
                config.getInt("clan-war.arena.floor-y"),
                config.getInt("clan-war.arena.origin-z"),
                config.getInt("clan-war.arena.half-size"),
                config.getInt("clan-war.arena.wall-height"),
                config.getInt("clan-war.arena.spawn-offset"),
                config.getDouble("clan-war.arena.spawn-spacing"),
                config.getString("clan-war.arena.floor-material"),
                config.getString("clan-war.arena.border-material"),
                config.getString("clan-war.arena.wall-material")
        );
    }
}

package io.github.kevinrabbe.minecraftserver.legacy;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Loads cheap Ranked arena/loadout tuning from the isolated runtime's local config.yml. */
final class LegacyRankedArenaSettingsLoader {
    private LegacyRankedArenaSettingsLoader() { }

    static LegacyRankedArenaSettings load(FileConfiguration config) {
        if (config == null) throw new NullPointerException("config");

        List<Map<?, ?>> rawLoadout = config.getMapList("ranked.loadout");
        ArrayList<LegacyRankedArenaSettings.LoadoutEntry> loadout = new ArrayList<LegacyRankedArenaSettings.LoadoutEntry>();
        for (Map<?, ?> raw : rawLoadout) {
            Object slot = raw.get("slot");
            Object material = raw.get("material");
            Object amount = raw.get("amount");
            if (slot == null || material == null || !(amount instanceof Number)) {
                throw new IllegalArgumentException("each ranked.loadout entry requires slot, material, and numeric amount");
            }
            loadout.add(new LegacyRankedArenaSettings.LoadoutEntry(
                    String.valueOf(slot),
                    String.valueOf(material),
                    ((Number) amount).intValue()
            ));
        }

        return new LegacyRankedArenaSettings(
                config.getInt("ranked.arena.origin-x"),
                config.getInt("ranked.arena.floor-y"),
                config.getInt("ranked.arena.origin-z"),
                config.getInt("ranked.arena.half-size"),
                config.getInt("ranked.arena.wall-height"),
                config.getInt("ranked.arena.spawn-offset"),
                config.getString("ranked.arena.floor-material"),
                config.getString("ranked.arena.border-material"),
                config.getString("ranked.arena.wall-material"),
                loadout
        );
    }
}

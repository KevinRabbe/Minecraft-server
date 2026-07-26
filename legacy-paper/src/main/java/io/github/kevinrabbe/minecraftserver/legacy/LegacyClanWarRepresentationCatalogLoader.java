package io.github.kevinrabbe.minecraftserver.legacy;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

/** Loads the explicit legacy representation allowlist from local disposable-runtime configuration. */
final class LegacyClanWarRepresentationCatalogLoader {
    private LegacyClanWarRepresentationCatalogLoader() { }

    static LegacyClanWarRepresentationCatalog load(FileConfiguration config) {
        if (config == null) throw new NullPointerException("config");
        ConfigurationSection section = config.getConfigurationSection("clan-war.representations");
        LinkedHashMap<String, String> configured = new LinkedHashMap<String, String>();
        if (section != null) {
            for (String definitionId : section.getKeys(false)) {
                String material = section.getString(definitionId);
                if (material == null) {
                    throw new IllegalArgumentException(
                            "Clan-War representation must be a material string: " + definitionId
                    );
                }
                configured.put(definitionId, material);
            }
        }
        return new LegacyClanWarRepresentationCatalog(configured);
    }
}

package io.github.kevinrabbe.minecraftserver.legacy;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the explicit legacy representation allowlist from local disposable-runtime configuration. */
final class LegacyClanWarRepresentationCatalogLoader {
    private LegacyClanWarRepresentationCatalogLoader() { }

    static LegacyClanWarRepresentationCatalog load(FileConfiguration config) {
        if (config == null) throw new NullPointerException("config");
        List<Map<?, ?>> entries = config.getMapList("clan-war.representations");
        LinkedHashMap<String, String> configured = new LinkedHashMap<String, String>();
        for (Map<?, ?> entry : entries) {
            Object definitionId = entry.get("definition-id");
            Object material = entry.get("material");
            if (definitionId == null || material == null) {
                throw new IllegalArgumentException(
                        "each clan-war.representations entry requires definition-id and material"
                );
            }
            String normalizedDefinition = String.valueOf(definitionId);
            if (configured.put(normalizedDefinition, String.valueOf(material)) != null) {
                throw new IllegalArgumentException(
                        "duplicate Clan-War representation definition " + normalizedDefinition
                );
            }
        }
        return new LegacyClanWarRepresentationCatalog(configured);
    }
}

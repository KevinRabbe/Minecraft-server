package io.github.kevinrabbe.minecraftserver.legacy;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loads and validates the explicit legacy representation allowlist from local disposable-runtime configuration. */
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
            String requestedMaterial = String.valueOf(material).trim().toUpperCase(Locale.ROOT);
            Material resolvedMaterial = Material.matchMaterial(requestedMaterial);
            if (resolvedMaterial == null || resolvedMaterial == Material.AIR) {
                throw new IllegalArgumentException(
                        "Clan-War representation uses unknown/invalid Minecraft-1.8 material "
                                + requestedMaterial + " for " + normalizedDefinition
                );
            }
            if (configured.put(normalizedDefinition, resolvedMaterial.name()) != null) {
                throw new IllegalArgumentException(
                        "duplicate Clan-War representation definition " + normalizedDefinition
                );
            }
        }
        return new LegacyClanWarRepresentationCatalog(configured);
    }
}

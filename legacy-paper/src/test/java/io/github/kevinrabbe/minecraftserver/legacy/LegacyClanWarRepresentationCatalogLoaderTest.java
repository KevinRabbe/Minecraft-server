package io.github.kevinrabbe.minecraftserver.legacy;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyClanWarRepresentationCatalogLoaderTest {
    @Test
    void dottedDefinitionIdsRemainLiteralListValues() {
        YamlConfiguration config = new YamlConfiguration();
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("definition-id", "equipment.starter_sword");
        entry.put("material", "IRON_SWORD");
        config.set("clan-war.representations", Collections.singletonList(entry));

        LegacyClanWarRepresentationCatalog catalog = LegacyClanWarRepresentationCatalogLoader.load(config);
        assertEquals(
                Material.IRON_SWORD,
                catalog.requireBaselineMaterial(
                        new LegacyLoadoutItem(0, 0, "equipment.starter_sword", "{}", 0)
                )
        );
    }

    @Test
    void malformedEntriesFailClosed() {
        YamlConfiguration config = new YamlConfiguration();
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("definition-id", "equipment.starter_sword");
        config.set("clan-war.representations", Collections.singletonList(entry));

        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyClanWarRepresentationCatalogLoader.load(config)
        );
    }
}

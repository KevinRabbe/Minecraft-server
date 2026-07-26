package io.github.kevinrabbe.minecraftserver.legacy;

import org.bukkit.Material;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Explicit definition-id -> Minecraft-1.8 representation allowlist for Clan War.
 *
 * <p>This first slice intentionally supports only baseline items whose frozen intrinsic roll state is empty and whose
 * upgrade level is zero. Silently flattening a rolled/upgraded MMO item into a vanilla legacy item would change the
 * player's selected combat value, so unsupported combat semantics fail closed instead.</p>
 */
final class LegacyClanWarRepresentationCatalog {
    private static final Pattern DEFINITION_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private final Map<String, Material> materialsByDefinition;

    LegacyClanWarRepresentationCatalog(Map<String, String> configuredMaterials) {
        Objects.requireNonNull(configuredMaterials, "configuredMaterials");
        LinkedHashMap<String, Material> resolved = new LinkedHashMap<String, Material>();
        for (Map.Entry<String, String> entry : configuredMaterials.entrySet()) {
            String definitionId = requireDefinitionId(entry.getKey());
            String materialName = requireText(entry.getValue(), "legacy material").toUpperCase(java.util.Locale.ROOT);
            Material material = Material.matchMaterial(materialName);
            if (material == null || material == Material.AIR) {
                throw new IllegalArgumentException(
                        "Clan-War legacy representation uses unknown/invalid 1.8 material " + materialName
                                + " for " + definitionId
                );
            }
            if (resolved.put(definitionId, material) != null) {
                throw new IllegalArgumentException("duplicate Clan-War representation definition " + definitionId);
            }
        }
        this.materialsByDefinition = Collections.unmodifiableMap(resolved);
    }

    Material requireBaselineMaterial(LegacyLoadoutItem item) {
        Objects.requireNonNull(item, "item");
        if (!isEmptyRollState(item.getRollStateJson()) || item.getUpgradeLevel() != 0) {
            throw new IllegalArgumentException(
                    "Clan-War legacy combat translation is not defined for rolled/upgraded item "
                            + item.getDefinitionId()
            );
        }
        Material material = materialsByDefinition.get(item.getDefinitionId());
        if (material == null) {
            throw new IllegalArgumentException(
                    "Clan-War legacy representation is not configured for " + item.getDefinitionId()
            );
        }
        return material;
    }

    private static boolean isEmptyRollState(String json) {
        StringBuilder compact = new StringBuilder(json.length());
        for (int index = 0; index < json.length(); index++) {
            char value = json.charAt(index);
            if (!Character.isWhitespace(value)) compact.append(value);
        }
        return "{}".contentEquals(compact);
    }

    private static String requireDefinitionId(String value) {
        String normalized = requireText(value, "definition id");
        if (!DEFINITION_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("invalid Clan-War definition id " + normalized);
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}

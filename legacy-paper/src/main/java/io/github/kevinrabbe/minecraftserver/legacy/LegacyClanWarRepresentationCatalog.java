package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Version-bound definition-id -> Minecraft-1.8 representation allowlist for Clan War.
 *
 * <p>This first slice intentionally supports only baseline items whose frozen intrinsic roll state is empty and whose
 * upgrade level is zero. Silently flattening a rolled/upgraded MMO item into a vanilla legacy item would change the
 * player's selected combat value, so unsupported combat semantics fail closed instead.</p>
 *
 * <p>The production mapping is part of {@code war.legacy_1_8_9@1} semantics. It is code-bound rather than operator
 * configuration so a deployment cannot silently change combat value while executions still claim the same frozen
 * ruleset version.</p>
 */
final class LegacyClanWarRepresentationCatalog {
    private static final Pattern DEFINITION_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern MATERIAL_ID = Pattern.compile("[A-Z0-9_]{1,128}");

    private final Map<String, String> materialsByDefinition;

    LegacyClanWarRepresentationCatalog(Map<String, String> configuredMaterials) {
        Objects.requireNonNull(configuredMaterials, "configuredMaterials");
        LinkedHashMap<String, String> resolved = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : configuredMaterials.entrySet()) {
            String definitionId = requireDefinitionId(entry.getKey());
            String materialName = requireText(entry.getValue(), "legacy material").toUpperCase(Locale.ROOT);
            if (!MATERIAL_ID.matcher(materialName).matches() || "AIR".equals(materialName)) {
                throw new IllegalArgumentException(
                        "Clan-War legacy representation uses invalid material id " + materialName
                                + " for " + definitionId
                );
            }
            if (resolved.put(definitionId, materialName) != null) {
                throw new IllegalArgumentException("duplicate Clan-War representation definition " + definitionId);
            }
        }
        this.materialsByDefinition = Collections.unmodifiableMap(resolved);
    }

    static LegacyClanWarRepresentationCatalog legacy189V1() {
        LinkedHashMap<String, String> mappings = new LinkedHashMap<String, String>();
        mappings.put("equipment.starter_sword", "IRON_SWORD");
        return new LegacyClanWarRepresentationCatalog(mappings);
    }

    String requireBaselineMaterial(LegacyLoadoutItem item) {
        Objects.requireNonNull(item, "item");
        if (!isEmptyRollState(item.getRollStateJson()) || item.getUpgradeLevel() != 0) {
            throw new IllegalArgumentException(
                    "Clan-War legacy combat translation is not defined for rolled/upgraded item "
                            + item.getDefinitionId()
            );
        }
        String material = materialsByDefinition.get(item.getDefinitionId());
        if (material == null) {
            throw new IllegalArgumentException(
                    "Clan-War legacy representation is not defined by the frozen ruleset for " + item.getDefinitionId()
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

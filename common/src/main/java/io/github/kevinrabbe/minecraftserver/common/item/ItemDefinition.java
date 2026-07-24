package io.github.kevinrabbe.minecraftserver.common.item;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable type-level content definition. Display/render data is not identity. */
public record ItemDefinition(
        String definitionId,
        String minecraftMaterial,
        String displayName,
        int maxStackSize,
        ItemCategory category,
        ItemIdentityKind identityKind
) {
    private static final Pattern DEFINITION_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern MATERIAL_ID = Pattern.compile("[A-Z0-9_]{1,128}");
    private static final int MAX_SUPPORTED_STACK_SIZE = 99;

    public ItemDefinition {
        definitionId = requireNormalizedDefinitionId(definitionId);
        minecraftMaterial = requireMinecraftMaterial(minecraftMaterial);
        displayName = requireDisplayName(displayName);
        category = Objects.requireNonNull(category, "category");
        identityKind = Objects.requireNonNull(identityKind, "identityKind");

        if (maxStackSize < 1 || maxStackSize > MAX_SUPPORTED_STACK_SIZE) {
            throw new IllegalArgumentException(
                    "maxStackSize must be between 1 and " + MAX_SUPPORTED_STACK_SIZE + ": " + maxStackSize
            );
        }
        if (identityKind == ItemIdentityKind.COMMODITY && maxStackSize == 1) {
            throw new IllegalArgumentException("COMMODITY definitions must be stackable: " + definitionId);
        }
        if (identityKind == ItemIdentityKind.INDIVIDUAL && maxStackSize != 1) {
            throw new IllegalArgumentException("INDIVIDUAL definitions must have maxStackSize=1: " + definitionId);
        }
    }

    public boolean stackable() {
        return maxStackSize > 1;
    }

    private static String requireNormalizedDefinitionId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        String normalized = value.trim();
        if (!DEFINITION_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "definitionId must match [a-z0-9][a-z0-9._-]{0,63}: " + normalized
            );
        }
        return normalized;
    }

    private static String requireMinecraftMaterial(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("minecraftMaterial must not be blank");
        }
        String normalized = value.trim();
        if (!MATERIAL_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("minecraftMaterial must be an uppercase Minecraft material id: " + normalized);
        }
        return normalized;
    }

    private static String requireDisplayName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("displayName must be <= 128 characters");
        }
        return normalized;
    }
}

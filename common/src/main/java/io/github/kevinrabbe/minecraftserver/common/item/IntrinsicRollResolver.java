package io.github.kevinrabbe.minecraftserver.common.item;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Resolves persistent normalized roll quality into current relative stat multipliers.
 *
 * <p>The returned values are basis-point multipliers of each property's current base value. Upgrade state, skills,
 * enchantments and temporary effects are intentionally separate later pipeline stages.</p>
 */
public final class IntrinsicRollResolver {
    private IntrinsicRollResolver() { }

    public static Map<String, Integer> resolveMultipliers(
            ItemRollProfile profile,
            Map<String, Integer> normalizedRollState
    ) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(normalizedRollState, "normalizedRollState");

        TreeSet<String> expected = new TreeSet<>(profile.properties().keySet());
        TreeSet<String> actual = new TreeSet<>(normalizedRollState.keySet());
        if (!expected.equals(actual)) {
            TreeSet<String> missing = new TreeSet<>(expected);
            missing.removeAll(actual);
            TreeSet<String> unknown = new TreeSet<>(actual);
            unknown.removeAll(expected);
            throw new IllegalArgumentException(
                    "roll state does not match current profile; missing=" + missing + ", unknown=" + unknown
            );
        }

        LinkedHashMap<String, Integer> resolved = new LinkedHashMap<>();
        for (String propertyId : expected) {
            Integer rawQuality = normalizedRollState.get(propertyId);
            if (rawQuality == null) {
                throw new IllegalArgumentException("roll quality must not be null for " + propertyId);
            }
            RollQuality quality = new RollQuality(rawQuality);
            RollRange range = profile.properties().get(propertyId);
            resolved.put(propertyId, range.interpolate(quality));
        }
        return Map.copyOf(resolved);
    }
}

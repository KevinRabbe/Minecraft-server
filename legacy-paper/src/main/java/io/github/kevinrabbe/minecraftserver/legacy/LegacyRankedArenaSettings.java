package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Validated cheap-tuning/configuration for the first single-arena Ranked runtime. */
final class LegacyRankedArenaSettings {
    private final int originX;
    private final int floorY;
    private final int originZ;
    private final int halfSize;
    private final int wallHeight;
    private final int spawnOffset;
    private final String floorMaterial;
    private final String borderMaterial;
    private final String wallMaterial;
    private final List<LoadoutEntry> loadout;

    LegacyRankedArenaSettings(
            int originX,
            int floorY,
            int originZ,
            int halfSize,
            int wallHeight,
            int spawnOffset,
            String floorMaterial,
            String borderMaterial,
            String wallMaterial,
            List<LoadoutEntry> loadout
    ) {
        if (floorY < 1 || floorY > 250) {
            throw new IllegalArgumentException("ranked arena floorY must be between 1 and 250");
        }
        if (halfSize < 4 || halfSize > 64) {
            throw new IllegalArgumentException("ranked arena halfSize must be between 4 and 64");
        }
        if (wallHeight < 2 || wallHeight > 16) {
            throw new IllegalArgumentException("ranked arena wallHeight must be between 2 and 16");
        }
        if (spawnOffset < 1 || spawnOffset >= halfSize) {
            throw new IllegalArgumentException("ranked arena spawnOffset must be inside the arena");
        }
        this.originX = originX;
        this.floorY = floorY;
        this.originZ = originZ;
        this.halfSize = halfSize;
        this.wallHeight = wallHeight;
        this.spawnOffset = spawnOffset;
        this.floorMaterial = requireText(floorMaterial, "floorMaterial");
        this.borderMaterial = requireText(borderMaterial, "borderMaterial");
        this.wallMaterial = requireText(wallMaterial, "wallMaterial");

        Objects.requireNonNull(loadout, "loadout");
        if (loadout.isEmpty()) {
            throw new IllegalArgumentException("ranked temporary loadout must contain at least one entry");
        }
        ArrayList<LoadoutEntry> copy = new ArrayList<LoadoutEntry>(loadout.size());
        Set<String> slots = new HashSet<String>();
        for (LoadoutEntry entry : loadout) {
            Objects.requireNonNull(entry, "loadout entry");
            if (!slots.add(entry.getSlot())) {
                throw new IllegalArgumentException("ranked temporary loadout contains duplicate slot " + entry.getSlot());
            }
            copy.add(entry);
        }
        this.loadout = Collections.unmodifiableList(copy);
    }

    int getOriginX() {
        return originX;
    }

    int getFloorY() {
        return floorY;
    }

    int getOriginZ() {
        return originZ;
    }

    int getHalfSize() {
        return halfSize;
    }

    int getWallHeight() {
        return wallHeight;
    }

    int getSpawnOffset() {
        return spawnOffset;
    }

    String getFloorMaterial() {
        return floorMaterial;
    }

    String getBorderMaterial() {
        return borderMaterial;
    }

    String getWallMaterial() {
        return wallMaterial;
    }

    List<LoadoutEntry> getLoadout() {
        return loadout;
    }

    static final class LoadoutEntry {
        private final String slot;
        private final String material;
        private final int amount;

        LoadoutEntry(String slot, String material, int amount) {
            this.slot = normalizeSlot(slot);
            this.material = requireText(material, "material").toUpperCase(Locale.ROOT);
            if (amount < 1 || amount > 64) {
                throw new IllegalArgumentException("ranked loadout amount must be between 1 and 64");
            }
            this.amount = amount;
        }

        String getSlot() {
            return slot;
        }

        String getMaterial() {
            return material;
        }

        int getAmount() {
            return amount;
        }

        private static String normalizeSlot(String raw) {
            String slot = requireText(raw, "slot").toLowerCase(Locale.ROOT);
            if (slot.equals("helmet") || slot.equals("chestplate") || slot.equals("leggings") || slot.equals("boots")) {
                return slot;
            }
            final int numeric;
            try {
                numeric = Integer.parseInt(slot);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("ranked loadout slot must be armor or inventory index 0-35", exception);
            }
            if (numeric < 0 || numeric > 35) {
                throw new IllegalArgumentException("ranked loadout inventory slot must be between 0 and 35");
            }
            return Integer.toString(numeric);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}

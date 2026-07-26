package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Exact 1.8 player-inventory projection for a frozen Clan-War representation plan.
 *
 * <p>Each frozen loadout row remains one separate inventory slot, using its contiguous loadout item index directly.
 * The legacy runtime does not merge equal materials, reinterpret rows as armor, truncate overflow, or create hidden
 * storage. A participant with more than the 36 normal 1.8 inventory slots is therefore not faithfully representable
 * and fails closed before combat can open.</p>
 */
final class LegacyClanWarInventoryProjection {
    static final int INVENTORY_SLOT_COUNT = 36;

    private final Map<UUID, List<SlotItem>> itemsByMinecraftUuid;

    private LegacyClanWarInventoryProjection(Map<UUID, List<SlotItem>> itemsByMinecraftUuid) {
        LinkedHashMap<UUID, List<SlotItem>> copy = new LinkedHashMap<UUID, List<SlotItem>>();
        for (Map.Entry<UUID, List<SlotItem>> entry : itemsByMinecraftUuid.entrySet()) {
            copy.put(
                    entry.getKey(),
                    Collections.unmodifiableList(new ArrayList<SlotItem>(entry.getValue()))
            );
        }
        this.itemsByMinecraftUuid = Collections.unmodifiableMap(copy);
    }

    static LegacyClanWarInventoryProjection build(LegacyClanWarRepresentationPlan plan) {
        Objects.requireNonNull(plan, "plan");
        LegacyExecution execution = plan.getWar().getExecution();

        LegacyParticipant[] participantsByIndex = new LegacyParticipant[execution.getParticipants().size()];
        LinkedHashMap<UUID, List<SlotItem>> projected = new LinkedHashMap<UUID, List<SlotItem>>();
        for (LegacyParticipant participant : execution.getParticipants()) {
            participantsByIndex[participant.getParticipantIndex()] = participant;
            projected.put(participant.getMinecraftUuid(), new ArrayList<SlotItem>());
        }

        for (LegacyClanWarRepresentedItem item : plan.getItems()) {
            int participantIndex = item.getParticipantIndex();
            if (participantIndex < 0 || participantIndex >= participantsByIndex.length
                    || participantsByIndex[participantIndex] == null) {
                throw new IllegalArgumentException(
                        "Clan-War representation references unknown participant index " + participantIndex
                );
            }
            int slot = item.getLoadoutItemIndex();
            if (slot < 0 || slot >= INVENTORY_SLOT_COUNT) {
                throw new IllegalArgumentException(
                        "Clan-War participant " + participantIndex
                                + " has more frozen items than the 36-slot Minecraft-1.8 inventory can represent"
                );
            }
            LegacyParticipant participant = participantsByIndex[participantIndex];
            projected.get(participant.getMinecraftUuid()).add(
                    new SlotItem(slot, item.getDefinitionId(), item.getMaterialId())
            );
        }

        return new LegacyClanWarInventoryProjection(projected);
    }

    Map<UUID, List<SlotItem>> getItemsByMinecraftUuid() {
        return itemsByMinecraftUuid;
    }

    static final class SlotItem {
        private final int inventorySlot;
        private final String definitionId;
        private final String materialId;

        SlotItem(int inventorySlot, String definitionId, String materialId) {
            if (inventorySlot < 0 || inventorySlot >= INVENTORY_SLOT_COUNT) {
                throw new IllegalArgumentException("inventorySlot must be between 0 and 35");
            }
            if (definitionId == null || definitionId.trim().isEmpty()) {
                throw new IllegalArgumentException("definitionId must not be blank");
            }
            if (materialId == null || materialId.trim().isEmpty()) {
                throw new IllegalArgumentException("materialId must not be blank");
            }
            this.inventorySlot = inventorySlot;
            this.definitionId = definitionId.trim();
            this.materialId = materialId.trim();
        }

        int getInventorySlot() {
            return inventorySlot;
        }

        String getDefinitionId() {
            return definitionId;
        }

        String getMaterialId() {
            return materialId;
        }
    }
}

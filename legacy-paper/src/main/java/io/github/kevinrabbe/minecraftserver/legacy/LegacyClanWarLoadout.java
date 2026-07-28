package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable, structurally validated view of one Clan-War execution's identity-free equipment snapshot. */
final class LegacyClanWarLoadout {
    private final LegacyClanWarExecution war;
    private final List<LegacyLoadoutItem> items;

    private LegacyClanWarLoadout(LegacyClanWarExecution war, List<LegacyLoadoutItem> items) {
        this.war = war;
        this.items = Collections.unmodifiableList(new ArrayList<LegacyLoadoutItem>(items));
    }

    static LegacyClanWarLoadout requireValid(LegacyClanWarExecution war, List<LegacyLoadoutItem> items) {
        Objects.requireNonNull(war, "war");
        Objects.requireNonNull(items, "items");

        int participantCount = war.getExecution().getParticipants().size();
        int[] nextItemIndex = new int[participantCount];
        int previousParticipant = -1;
        int previousItem = -1;

        for (LegacyLoadoutItem item : items) {
            Objects.requireNonNull(item, "loadout item");
            int participantIndex = item.getParticipantIndex();
            int itemIndex = item.getLoadoutItemIndex();
            if (participantIndex >= participantCount) {
                throw new IllegalArgumentException(
                        "loadout references unknown participant index " + participantIndex
                );
            }
            if (participantIndex < previousParticipant
                    || (participantIndex == previousParticipant && itemIndex <= previousItem)) {
                throw new IllegalArgumentException("loadout rows must be strictly ordered by participant/item index");
            }
            if (itemIndex != nextItemIndex[participantIndex]) {
                throw new IllegalArgumentException(
                        "loadout item indexes must be contiguous per participant: participant="
                                + participantIndex + " expected=" + nextItemIndex[participantIndex] + " actual=" + itemIndex
                );
            }

            nextItemIndex[participantIndex]++;
            previousParticipant = participantIndex;
            previousItem = itemIndex;
        }

        return new LegacyClanWarLoadout(war, items);
    }

    LegacyClanWarExecution getWar() {
        return war;
    }

    List<LegacyLoadoutItem> getItems() {
        return items;
    }
}

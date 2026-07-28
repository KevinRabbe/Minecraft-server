package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable representation plan derived only from the sanitized frozen execution snapshot. */
final class LegacyClanWarRepresentationPlan {
    private final LegacyClanWarExecution war;
    private final List<LegacyClanWarRepresentedItem> items;

    private LegacyClanWarRepresentationPlan(
            LegacyClanWarExecution war,
            List<LegacyClanWarRepresentedItem> items
    ) {
        this.war = war;
        this.items = Collections.unmodifiableList(new ArrayList<LegacyClanWarRepresentedItem>(items));
    }

    static LegacyClanWarRepresentationPlan build(
            LegacyClanWarExecution war,
            LegacyClanWarLoadout loadout,
            LegacyClanWarRepresentationCatalog catalog
    ) {
        Objects.requireNonNull(war, "war");
        Objects.requireNonNull(loadout, "loadout");
        Objects.requireNonNull(catalog, "catalog");
        if (!war.getExecution().getExecutionId().equals(loadout.getWar().getExecution().getExecutionId())) {
            throw new IllegalArgumentException("Clan-War loadout belongs to another execution");
        }

        ArrayList<LegacyClanWarRepresentedItem> represented = new ArrayList<LegacyClanWarRepresentedItem>();
        for (LegacyLoadoutItem item : loadout.getItems()) {
            represented.add(new LegacyClanWarRepresentedItem(
                    item.getParticipantIndex(),
                    item.getLoadoutItemIndex(),
                    item.getDefinitionId(),
                    catalog.requireBaselineMaterial(item)
            ));
        }
        return new LegacyClanWarRepresentationPlan(war, represented);
    }

    LegacyClanWarExecution getWar() {
        return war;
    }

    List<LegacyClanWarRepresentedItem> getItems() {
        return items;
    }
}

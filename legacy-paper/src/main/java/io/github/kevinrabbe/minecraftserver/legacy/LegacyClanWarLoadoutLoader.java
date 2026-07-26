package io.github.kevinrabbe.minecraftserver.legacy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Reads the immutable V71 loadout through bounded pages without imposing a gameplay kit-size cap. */
final class LegacyClanWarLoadoutLoader {
    private static final int PAGE_SIZE = 128;

    private LegacyClanWarLoadoutLoader() { }

    static LegacyClanWarLoadout load(LegacyClanWarExecution war, LoadoutPageSource source) throws SQLException {
        Objects.requireNonNull(war, "war");
        Objects.requireNonNull(source, "source");

        UUID executionId = war.getExecution().getExecutionId();
        ArrayList<LegacyLoadoutItem> items = new ArrayList<LegacyLoadoutItem>();
        Integer afterParticipantIndex = null;
        Integer afterLoadoutItemIndex = null;

        while (true) {
            List<LegacyLoadoutItem> page = source.page(
                    executionId,
                    afterParticipantIndex,
                    afterLoadoutItemIndex,
                    PAGE_SIZE
            );
            if (page == null) {
                throw new SQLException("competitive runtime loadout page source returned null");
            }
            if (page.isEmpty()) break;
            if (page.size() > PAGE_SIZE) {
                throw new SQLException("competitive runtime loadout page exceeded requested bound");
            }

            LegacyLoadoutItem last = page.get(page.size() - 1);
            if (last == null) {
                throw new SQLException("competitive runtime loadout page ended with null item");
            }
            if (afterParticipantIndex != null && !strictlyAfter(
                    last,
                    afterParticipantIndex.intValue(),
                    afterLoadoutItemIndex.intValue()
            )) {
                throw new SQLException("competitive runtime loadout pagination did not advance");
            }

            items.addAll(page);
            afterParticipantIndex = last.getParticipantIndex();
            afterLoadoutItemIndex = last.getLoadoutItemIndex();
            if (page.size() < PAGE_SIZE) break;
        }

        return LegacyClanWarLoadout.requireValid(war, items);
    }

    private static boolean strictlyAfter(LegacyLoadoutItem item, int participantIndex, int loadoutItemIndex) {
        return item.getParticipantIndex() > participantIndex
                || (item.getParticipantIndex() == participantIndex
                && item.getLoadoutItemIndex() > loadoutItemIndex);
    }

    @FunctionalInterface
    interface LoadoutPageSource {
        List<LegacyLoadoutItem> page(
                UUID executionId,
                Integer afterParticipantIndex,
                Integer afterLoadoutItemIndex,
                int limit
        ) throws SQLException;
    }
}

package io.github.kevinrabbe.minecraftserver.legacy;

import java.sql.SQLException;
import java.util.Objects;

/**
 * Fail-closed admission preparation independent of Bukkit event plumbing.
 * Ranked requires only its frozen manifest; Clan War additionally requires the complete sealed V71/V74 loadout.
 */
final class LegacyExecutionAdmission {
    private LegacyExecutionAdmission() { }

    static LegacyClanWarLoadout prepare(
            LegacyExecution execution,
            LegacyClanWarLoadoutLoader.LoadoutPageSource loadoutSource
    ) throws SQLException {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(loadoutSource, "loadoutSource");

        if (LegacyRankedExecution.ACTIVITY_KIND.equals(execution.getActivityKind())) {
            LegacyRankedExecution.requireSupported(execution);
            return null;
        }
        if (LegacyClanWarExecution.ACTIVITY_KIND.equals(execution.getActivityKind())) {
            LegacyClanWarExecution war = LegacyClanWarExecution.requireSupported(execution);
            return LegacyClanWarLoadoutLoader.load(war, loadoutSource);
        }
        throw new IllegalArgumentException("Unsupported competitive activity kind: " + execution.getActivityKind());
    }

    /**
     * Clan-War admission additionally proves that every frozen item has a faithful currently-supported 1.8
     * representation. The representation plan is deliberately discarded here; the eventual combat materializer will
     * rebuild it from the same immutable loadout when it becomes ready to open combat.
     */
    static LegacyClanWarLoadout prepare(
            LegacyExecution execution,
            LegacyClanWarLoadoutLoader.LoadoutPageSource loadoutSource,
            LegacyClanWarRepresentationCatalog representationCatalog
    ) throws SQLException {
        Objects.requireNonNull(representationCatalog, "representationCatalog");
        LegacyClanWarLoadout loadout = prepare(execution, loadoutSource);
        if (loadout != null) {
            LegacyClanWarExecution war = LegacyClanWarExecution.requireSupported(execution);
            LegacyClanWarRepresentationPlan.build(war, loadout, representationCatalog);
        }
        return loadout;
    }
}

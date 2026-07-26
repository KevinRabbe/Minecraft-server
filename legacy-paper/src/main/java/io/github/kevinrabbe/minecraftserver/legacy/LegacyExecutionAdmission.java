package io.github.kevinrabbe.minecraftserver.legacy;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
}

package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded aggregate verifier across economy/custody, item state, persistent PvE, clans, and competitive evidence. */
public final class PersistentIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;

    private final EconomyIntegrityVerifier economy;
    private final ItemUpgradeIntegrityVerifier itemUpgrades;
    private final PersistentPveIntegrityVerifier pve;
    private final ClanIntegrityVerifier clans;
    private final CompetitiveIntegrityVerifier competitive;
    private final CompetitiveExecutionLoadoutIntegrityVerifier competitiveLoadouts;

    /** Compatibility constructor for environments without a loaded item catalog. */
    public PersistentIntegrityVerifier(DataSource dataSource) {
        this(dataSource, null);
    }

    /** Strong aggregate verifier with item-definition-aware upgrade reconciliation. */
    public PersistentIntegrityVerifier(DataSource dataSource, ItemCatalog itemCatalog) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.economy = new EconomyIntegrityVerifier(dataSource);
        this.itemUpgrades = itemCatalog == null
                ? new ItemUpgradeIntegrityVerifier(dataSource)
                : new ItemUpgradeIntegrityVerifier(dataSource, itemCatalog);
        this.pve = new PersistentPveIntegrityVerifier(dataSource);
        this.clans = new ClanIntegrityVerifier(dataSource);
        this.competitive = new CompetitiveIntegrityVerifier(dataSource);
        this.competitiveLoadouts = new CompetitiveExecutionLoadoutIntegrityVerifier(dataSource);
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }
        ArrayList<IntegrityIssue> issues = new ArrayList<>(economy.verify(maxIssues));
        int remaining = maxIssues - issues.size();
        if (remaining > 0) {
            issues.addAll(itemUpgrades.verify(remaining));
        }
        remaining = maxIssues - issues.size();
        if (remaining > 0) {
            issues.addAll(pve.verify(remaining));
        }
        remaining = maxIssues - issues.size();
        if (remaining > 0) {
            issues.addAll(clans.verify(remaining));
        }
        remaining = maxIssues - issues.size();
        if (remaining > 0) {
            issues.addAll(competitive.verify(remaining));
        }
        remaining = maxIssues - issues.size();
        if (remaining > 0) {
            issues.addAll(competitiveLoadouts.verify(remaining));
        }
        return List.copyOf(issues);
    }
}

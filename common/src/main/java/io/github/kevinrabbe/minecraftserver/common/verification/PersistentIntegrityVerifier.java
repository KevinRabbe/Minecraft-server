package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded aggregate verifier across session/state, economy/custody, progression/world state, PvE, clan, and competitive evidence. */
public final class PersistentIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;

    private final PlayerSessionIntegrityVerifier sessions;
    private final EconomyIntegrityVerifier economy;
    private final ItemUpgradeIntegrityVerifier itemUpgrades;
    private final SkillProgressionIntegrityVerifier skills;
    private final WorldProgressionIntegrityVerifier worldProgression;
    private final PersistentPveIntegrityVerifier pve;
    private final ClanIntegrityVerifier clans;
    private final CompetitiveIntegrityVerifier competitive;
    private final CompetitiveExecutionLoadoutIntegrityVerifier competitiveLoadouts;

    /** Compatibility constructor for environments without loaded item/skill catalogs. */
    public PersistentIntegrityVerifier(DataSource dataSource) {
        this(dataSource, null, null);
    }

    /** Compatibility constructor with item-definition-aware upgrade reconciliation only. */
    public PersistentIntegrityVerifier(DataSource dataSource, ItemCatalog itemCatalog) {
        this(dataSource, itemCatalog, null);
    }

    /** Strong aggregate verifier with both live item and skill catalogs. */
    public PersistentIntegrityVerifier(
            DataSource dataSource,
            ItemCatalog itemCatalog,
            SkillProgressionCatalog skillCatalog
    ) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.sessions = new PlayerSessionIntegrityVerifier(dataSource);
        this.economy = new EconomyIntegrityVerifier(dataSource);
        this.itemUpgrades = itemCatalog == null
                ? new ItemUpgradeIntegrityVerifier(dataSource)
                : new ItemUpgradeIntegrityVerifier(dataSource, itemCatalog);
        this.skills = skillCatalog == null
                ? new SkillProgressionIntegrityVerifier(dataSource)
                : new SkillProgressionIntegrityVerifier(dataSource, skillCatalog);
        this.worldProgression = new WorldProgressionIntegrityVerifier(dataSource);
        this.pve = new PersistentPveIntegrityVerifier(dataSource);
        this.clans = new ClanIntegrityVerifier(dataSource);
        this.competitive = new CompetitiveIntegrityVerifier(dataSource);
        this.competitiveLoadouts = new CompetitiveExecutionLoadoutIntegrityVerifier(dataSource);
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }
        ArrayList<IntegrityIssue> issues = new ArrayList<>(sessions.verify(maxIssues));
        int remaining = maxIssues - issues.size();
        if (remaining > 0) {
            issues.addAll(economy.verify(remaining));
        }
        remaining = maxIssues - issues.size();
        if (remaining > 0) {
            issues.addAll(itemUpgrades.verify(remaining));
        }
        remaining = maxIssues - issues.size();
        if (remaining > 0) {
            issues.addAll(skills.verify(remaining));
        }
        remaining = maxIssues - issues.size();
        if (remaining > 0) {
            issues.addAll(worldProgression.verify(remaining));
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

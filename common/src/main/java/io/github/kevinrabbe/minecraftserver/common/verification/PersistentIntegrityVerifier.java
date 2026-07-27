package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.artifact.AttunementProfileCatalog;
import io.github.kevinrabbe.minecraftserver.common.economy.BankTierCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded aggregate verifier across session/state, economy/custody, Bank Manager/Bazaar,
 * progression/crafting/world state, Artifacts/Attunement, PvE, clan, and competitive evidence.
 */
public final class PersistentIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;

    private final PlayerSessionIntegrityVerifier sessions;
    private final EconomyIntegrityVerifier economy;
    private final BankIntegrityVerifier bank;
    private final BazaarIntegrityVerifier bazaar;
    private final ItemUpgradeIntegrityVerifier itemUpgrades;
    private final SkillProgressionIntegrityVerifier skills;
    private final CraftingIntegrityVerifier crafting;
    private final WorldProgressionIntegrityVerifier worldProgression;
    private final ArtifactIntegrityVerifier artifacts;
    private final PersistentPveIntegrityVerifier pve;
    private final ClanIntegrityVerifier clans;
    private final CompetitiveIntegrityVerifier competitive;
    private final CompetitiveExecutionLoadoutIntegrityVerifier competitiveLoadouts;

    /** Compatibility constructor for environments without loaded content catalogs. */
    public PersistentIntegrityVerifier(DataSource dataSource) {
        this(dataSource, null, null, null, null);
    }

    /** Compatibility constructor with item-definition-aware upgrade reconciliation only. */
    public PersistentIntegrityVerifier(DataSource dataSource, ItemCatalog itemCatalog) {
        this(dataSource, itemCatalog, null, null, null);
    }

    /** Compatibility constructor with live item and skill catalogs but no Attunement or Bank tier catalog. */
    public PersistentIntegrityVerifier(
            DataSource dataSource,
            ItemCatalog itemCatalog,
            SkillProgressionCatalog skillCatalog
    ) {
        this(dataSource, itemCatalog, skillCatalog, null, null);
    }

    /** Compatibility constructor with live item, skill and Attunement catalogs but no Bank tier catalog. */
    public PersistentIntegrityVerifier(
            DataSource dataSource,
            ItemCatalog itemCatalog,
            SkillProgressionCatalog skillCatalog,
            AttunementProfileCatalog attunementProfiles
    ) {
        this(dataSource, itemCatalog, skillCatalog, attunementProfiles, null);
    }

    /** Strong aggregate verifier with live item, skill, Attunement-profile, and Bank-tier catalogs. */
    public PersistentIntegrityVerifier(
            DataSource dataSource,
            ItemCatalog itemCatalog,
            SkillProgressionCatalog skillCatalog,
            AttunementProfileCatalog attunementProfiles,
            BankTierCatalog bankTiers
    ) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.sessions = new PlayerSessionIntegrityVerifier(dataSource);
        this.economy = new EconomyIntegrityVerifier(dataSource);
        this.bank = bankTiers == null
                ? new BankIntegrityVerifier(dataSource)
                : new BankIntegrityVerifier(dataSource, bankTiers);
        this.bazaar = new BazaarIntegrityVerifier(dataSource);
        this.itemUpgrades = itemCatalog == null
                ? new ItemUpgradeIntegrityVerifier(dataSource)
                : new ItemUpgradeIntegrityVerifier(dataSource, itemCatalog);
        this.skills = skillCatalog == null
                ? new SkillProgressionIntegrityVerifier(dataSource)
                : new SkillProgressionIntegrityVerifier(dataSource, skillCatalog);
        this.crafting = new CraftingIntegrityVerifier(dataSource);
        this.worldProgression = new WorldProgressionIntegrityVerifier(dataSource);
        this.artifacts = attunementProfiles == null
                ? new ArtifactIntegrityVerifier(dataSource)
                : new ArtifactIntegrityVerifier(dataSource, attunementProfiles);
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
            issues.addAll(bank.verify(remaining));
        }
        remaining = maxIssues - issues.size();
        if (remaining > 0) {
            issues.addAll(bazaar.verify(remaining));
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
            issues.addAll(crafting.verify(remaining));
        }
        remaining = maxIssues - issues.size();
        if (remaining > 0) {
            issues.addAll(worldProgression.verify(remaining));
        }
        remaining = maxIssues - issues.size();
        if (remaining > 0) {
            issues.addAll(artifacts.verify(remaining));
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

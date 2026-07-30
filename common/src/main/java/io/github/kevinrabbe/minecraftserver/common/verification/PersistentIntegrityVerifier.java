package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.artifact.AttunementProfileCatalog;
import io.github.kevinrabbe.minecraftserver.common.economy.BankTierCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceCatalog;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded aggregate verifier across session/state, economy/custody, PvE death-loss evidence, shared commodity and
 * unique-delivery claims, Auction/Bank/Bazaar/Secure Trade/salvage/clan assets, progression/crafting/resources/world
 * state, Artifacts/Attunement, Map encounter/reward and PvE/Bounty lifecycle evidence, clan, and competitive evidence.
 */
public final class PersistentIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;

    private final PlayerSessionIntegrityVerifier sessions;
    private final EconomyIntegrityVerifier economy;
    private final PveDeathLossIntegrityVerifier pveDeathLoss;
    private final CommodityDeliveryIntegrityVerifier commodityDeliveries;
    private final PendingUniqueDeliveryClaimIntegrityVerifier uniqueDeliveryClaims;
    private final AuctionIntegrityVerifier auction;
    private final BankIntegrityVerifier bank;
    private final BazaarIntegrityVerifier bazaar;
    private final SecureTradeIntegrityVerifier secureTrades;
    private final SalvageIntegrityVerifier salvage;
    private final ClanTreasuryIntegrityVerifier clanTreasuries;
    private final ClanStorageIntegrityVerifier clanStorage;
    private final ItemUpgradeIntegrityVerifier itemUpgrades;
    private final SkillProgressionIntegrityVerifier skills;
    private final ResourceSourceIntegrityVerifier resources;
    private final ResourceSourceLiveCatalogIntegrityVerifier resourceContent;
    private final CraftingIntegrityVerifier crafting;
    private final WorldProgressionIntegrityVerifier worldProgression;
    private final ArtifactIntegrityVerifier artifacts;
    private final BountyLifecycleIntegrityVerifier bountyLifecycle;
    private final MapEncounterIntegrityVerifier mapEncounters;
    private final PersistentPveIntegrityVerifier pve;
    private final MapRewardIntegrityVerifier mapRewards;
    private final ClanIntegrityVerifier clans;
    private final CompetitiveIntegrityVerifier competitive;
    private final CompetitiveExecutionLoadoutIntegrityVerifier competitiveLoadouts;

    /** Compatibility constructor for environments without loaded content catalogs. */
    public PersistentIntegrityVerifier(DataSource dataSource) {
        this(dataSource, null, null, null, null, null);
    }

    /** Compatibility constructor with item-definition-aware upgrade reconciliation only. */
    public PersistentIntegrityVerifier(DataSource dataSource, ItemCatalog itemCatalog) {
        this(dataSource, itemCatalog, null, null, null, null);
    }

    /** Compatibility constructor with live item and skill catalogs but no Attunement, Bank, or resource catalog. */
    public PersistentIntegrityVerifier(
            DataSource dataSource,
            ItemCatalog itemCatalog,
            SkillProgressionCatalog skillCatalog
    ) {
        this(dataSource, itemCatalog, skillCatalog, null, null, null);
    }

    /** Compatibility constructor with live item, skill and Attunement catalogs but no Bank or resource catalog. */
    public PersistentIntegrityVerifier(
            DataSource dataSource,
            ItemCatalog itemCatalog,
            SkillProgressionCatalog skillCatalog,
            AttunementProfileCatalog attunementProfiles
    ) {
        this(dataSource, itemCatalog, skillCatalog, attunementProfiles, null, null);
    }

    /** Compatibility constructor with live item, skill, Attunement-profile, and Bank-tier catalogs. */
    public PersistentIntegrityVerifier(
            DataSource dataSource,
            ItemCatalog itemCatalog,
            SkillProgressionCatalog skillCatalog,
            AttunementProfileCatalog attunementProfiles,
            BankTierCatalog bankTiers
    ) {
        this(dataSource, itemCatalog, skillCatalog, attunementProfiles, bankTiers, null);
    }

    /** Strong aggregate verifier with all currently loaded content catalogs used by bounded reconciliation. */
    public PersistentIntegrityVerifier(
            DataSource dataSource,
            ItemCatalog itemCatalog,
            SkillProgressionCatalog skillCatalog,
            AttunementProfileCatalog attunementProfiles,
            BankTierCatalog bankTiers,
            ResourceSourceCatalog resourceSourceCatalog
    ) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.sessions = new PlayerSessionIntegrityVerifier(dataSource);
        this.economy = new EconomyIntegrityVerifier(dataSource);
        this.pveDeathLoss = new PveDeathLossIntegrityVerifier(dataSource);
        this.commodityDeliveries = new CommodityDeliveryIntegrityVerifier(dataSource);
        this.uniqueDeliveryClaims = new PendingUniqueDeliveryClaimIntegrityVerifier(dataSource);
        this.auction = new AuctionIntegrityVerifier(dataSource);
        this.bank = bankTiers == null
                ? new BankIntegrityVerifier(dataSource)
                : new BankIntegrityVerifier(dataSource, bankTiers);
        this.bazaar = new BazaarIntegrityVerifier(dataSource);
        this.secureTrades = new SecureTradeIntegrityVerifier(dataSource);
        this.salvage = new SalvageIntegrityVerifier(dataSource);
        this.clanTreasuries = new ClanTreasuryIntegrityVerifier(dataSource);
        this.clanStorage = new ClanStorageIntegrityVerifier(dataSource);
        this.itemUpgrades = itemCatalog == null
                ? new ItemUpgradeIntegrityVerifier(dataSource)
                : new ItemUpgradeIntegrityVerifier(dataSource, itemCatalog);
        this.skills = skillCatalog == null
                ? new SkillProgressionIntegrityVerifier(dataSource)
                : new SkillProgressionIntegrityVerifier(dataSource, skillCatalog);
        this.resources = new ResourceSourceIntegrityVerifier(dataSource);
        this.resourceContent = resourceSourceCatalog == null
                ? null
                : new ResourceSourceLiveCatalogIntegrityVerifier(dataSource, resourceSourceCatalog);
        this.crafting = new CraftingIntegrityVerifier(dataSource);
        this.worldProgression = new WorldProgressionIntegrityVerifier(dataSource);
        this.artifacts = attunementProfiles == null
                ? new ArtifactIntegrityVerifier(dataSource)
                : new ArtifactIntegrityVerifier(dataSource, attunementProfiles);
        this.bountyLifecycle = new BountyLifecycleIntegrityVerifier(dataSource);
        this.mapEncounters = new MapEncounterIntegrityVerifier(dataSource);
        this.pve = new PersistentPveIntegrityVerifier(dataSource);
        this.mapRewards = new MapRewardIntegrityVerifier(dataSource);
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
        if (remaining > 0) issues.addAll(economy.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(pveDeathLoss.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(commodityDeliveries.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(uniqueDeliveryClaims.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(auction.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(bank.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(bazaar.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(secureTrades.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(salvage.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(clanTreasuries.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(clanStorage.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(itemUpgrades.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(skills.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(resources.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0 && resourceContent != null) issues.addAll(resourceContent.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(crafting.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(worldProgression.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(artifacts.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(bountyLifecycle.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(mapEncounters.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(pve.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(mapRewards.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(clans.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(competitive.verify(remaining));
        remaining = maxIssues - issues.size();
        if (remaining > 0) issues.addAll(competitiveLoadouts.verify(remaining));
        return List.copyOf(issues);
    }
}

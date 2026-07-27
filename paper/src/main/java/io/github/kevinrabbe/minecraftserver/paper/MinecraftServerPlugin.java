package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.artifact.ArtifactRepository;
import io.github.kevinrabbe.minecraftserver.common.artifact.AttunementProfileCatalog;
import io.github.kevinrabbe.minecraftserver.common.artifact.AttunementProfileCatalogLoader;
import io.github.kevinrabbe.minecraftserver.common.artifact.AttunementRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanMembershipRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanQueryRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanRoleRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanStorageRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanTreasuryRepository;
import io.github.kevinrabbe.minecraftserver.common.control.BackendRegistry;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftingCommissionCompletionRepository;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftingContentCatalog;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftingContentCatalogLoader;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftingExperienceFulfillmentRepository;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftingRepository;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftingStateExecutionService;
import io.github.kevinrabbe.minecraftserver.common.economy.AuctionHouseQueryRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.AuctionHouseRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.BankManagerRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.BankTierCatalog;
import io.github.kevinrabbe.minecraftserver.common.economy.BankTierCatalogLoader;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarPolicy;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarPolicyLoader;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.CraftingCommissionQueryRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.CraftingCommissionRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.SalvageCatalog;
import io.github.kevinrabbe.minecraftserver.common.economy.SalvageCatalogLoader;
import io.github.kevinrabbe.minecraftserver.common.economy.SalvageRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeAssetRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeConfirmationRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeQueryRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeResolutionRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeWithdrawalRepository;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalogLoader;
import io.github.kevinrabbe.minecraftserver.common.item.ItemUseRequirementCatalogValidator;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillLeaderboardRepository;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalogLoader;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionQueryRepository;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyBossMaterializationRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyContentCatalog;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyContentCatalogLoader;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyKillProgressRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyPouchRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountySummonRecoveryRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarLifecycleRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarLoadoutReadinessRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarLoadoutRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarQueryRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarResolutionRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarRuleset;
import io.github.kevinrabbe.minecraftserver.common.pvp.RankedArenaRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.RankedArenaRuleset;
import io.github.kevinrabbe.minecraftserver.common.pvp.RankedLeaderboardRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.RankedMatchmakingRepository;
import io.github.kevinrabbe.minecraftserver.common.transfer.TransferPluginMessage;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceEntitySpawnRepository;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceGatheringService;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceHarvestFulfillmentRepository;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceCatalog;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceCatalogLoader;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceRepository;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public final class MinecraftServerPlugin extends JavaPlugin implements Listener {
    private static final long HEARTBEAT_PERIOD_TICKS = 100L;
    private static final long CHECKPOINT_PERIOD_TICKS = 100L;
    private static final long DELIVERY_PUMP_PERIOD_TICKS = 100L;
    private static final Duration BOUNTY_SUMMON_LEASE = Duration.ofSeconds(30);
    private static final String ITEM_CATALOG_RESOURCE = "/content/items.json";
    private static final String SKILL_CATALOG_RESOURCE = "/content/skills.json";
    private static final String BANK_TIER_CATALOG_RESOURCE = "/content/bank-tiers.json";
    private static final String BAZAAR_POLICY_RESOURCE = "/content/bazaar-policy.json";
    private static final String SALVAGE_CATALOG_RESOURCE = "/content/salvage.json";
    private static final String CRAFTING_CATALOG_RESOURCE = "/content/crafting.json";
    private static final String RESOURCE_SOURCE_CATALOG_RESOURCE = "/content/resource-sources.json";
    private static final String BOUNTY_CONTENT_RESOURCE = "/content/bounties.json";
    private static final String BOUNTY_BOSS_PLACEMENT_CATALOG_RESOURCE = "/content/bounty-boss-placements.json";
    private static final String RESOURCE_PLACEMENT_CATALOG_RESOURCE = "/content/resource-source-placements.json";
    private static final String RESOURCE_ENTITY_PLACEMENT_CATALOG_RESOURCE = "/content/resource-entity-placements.json";
    private static final String ATTUNEMENT_PROFILE_CATALOG_RESOURCE = "/content/attunement-profiles.json";
    private static final String ARTIFACT_PLACEMENT_CATALOG_RESOURCE = "/content/artifacts.json";

    private final AtomicInteger onlinePlayers = new AtomicInteger();

    private String backendId;
    private ItemCatalog itemCatalog;
    private SkillProgressionCatalog skillCatalog;
    private ResourceSourceCatalog resourceSourceCatalog;
    private Database database;
    private BackendRegistry backendRegistry;
    private PaperSessionController sessionController;
    private PaperPlayerItemRepresentationValidator itemRepresentationValidator;
    private PaperCommodityDeliveryController commodityDeliveryController;
    private BootstrapZoneInstance bootstrapZoneInstance;
    private PaperResourceGatheringListener resourceGatheringListener;
    private PaperResourceEntityController resourceEntityController;
    private PaperBountyProgressService bountyProgressService;
    private PaperBountyBossController bountyBossController;
    private PaperArtifactDiscoveryListener artifactDiscoveryListener;
    private PaperMapRuntime mapRuntime;
    private BukkitTask heartbeatTask;
    private BukkitTask checkpointTask;
    private BukkitTask deliveryPumpTask;

    @Override
    public void onEnable() {
        backendId = requireBackendId();

        PaperAttunementCommand attunementCommand;
        PaperSkillsCommand skillsCommand;
        PaperSkillLeaderboardCommand leaderboardCommand;
        PaperBankCommand bankCommand;
        PaperBazaarCommand bazaarCommand;
        PaperAuctionHouseCommand auctionHouseCommand;
        PaperTradeCommand tradeCommand;
        PaperClanRouterCommand clanCommand;
        PaperRankedCommand rankedCommand;
        PaperCraftingCommissionCommand commissionCommand;
        PaperBountyCommand bountyCommand;
        PaperSalvageCommand salvageCommand;
        PaperUniqueDeliveryController uniqueDeliveryController;
        PaperItemUseEligibilityController itemUseEligibilityController;
        PaperCraftingController craftingController;
        BazaarPolicy bazaarPolicy;
        BazaarRepository bazaarRepository;
        try {
            itemCatalog = new ItemCatalogLoader().loadResource(ITEM_CATALOG_RESOURCE);
            PaperItemCatalogValidator.validate(itemCatalog);
            skillCatalog = new SkillProgressionCatalogLoader().loadResource(SKILL_CATALOG_RESOURCE);
            ItemUseRequirementCatalogValidator.validate(itemCatalog, skillCatalog);
            BankTierCatalog bankTiers = new BankTierCatalogLoader().loadResource(BANK_TIER_CATALOG_RESOURCE);
            bazaarPolicy = new BazaarPolicyLoader().loadResource(BAZAAR_POLICY_RESOURCE);
            SalvageCatalog salvageCatalog = new SalvageCatalogLoader().loadResource(
                    SALVAGE_CATALOG_RESOURCE,
                    itemCatalog
            );
            CraftingContentCatalog craftingContent = new CraftingContentCatalogLoader().loadResource(
                    CRAFTING_CATALOG_RESOURCE,
                    itemCatalog,
                    skillCatalog
            );
            resourceSourceCatalog = new ResourceSourceCatalogLoader().loadResource(
                    RESOURCE_SOURCE_CATALOG_RESOURCE,
                    itemCatalog,
                    skillCatalog
            );
            BountyContentCatalog bountyContent = new BountyContentCatalogLoader().loadResource(
                    BOUNTY_CONTENT_RESOURCE,
                    itemCatalog,
                    resourceSourceCatalog
            );
            PaperBountyBossPlacementCatalog bountyBossPlacements = PaperBountyBossPlacementCatalog.loadResource(
                    BOUNTY_BOSS_PLACEMENT_CATALOG_RESOURCE,
                    bountyContent
            );
            AttunementProfileCatalog attunementProfiles = new AttunementProfileCatalogLoader().loadResource(
                    ATTUNEMENT_PROFILE_CATALOG_RESOURCE
            );

            database = Database.open(DatabaseConfig.fromEnvironment());
            database.migrate();

            backendRegistry = new BackendRegistry(database.dataSource());
            onlinePlayers.set(getServer().getOnlinePlayers().size());
            backendRegistry.registerOnline(backendId, onlinePlayers.get());

            Optional<BootstrapZoneInstance> configuredZone = BootstrapZoneInstance.fromEnvironment(
                    backendId,
                    database.dataSource()
            );
            if (configuredZone.isPresent()) {
                bootstrapZoneInstance = configuredZone.orElseThrow();
                bootstrapZoneInstance.start();
            }

            String currentZoneId = bootstrapZoneInstance == null ? null : bootstrapZoneInstance.zoneId();
            sessionController = new PaperSessionController(
                    this,
                    backendId,
                    currentZoneId,
                    database.dataSource()
            );
            PaperPlayerIdentityResolver playerIdentities = new PaperPlayerIdentityResolver(database.dataSource());
            itemUseEligibilityController = new PaperItemUseEligibilityController(
                    this,
                    playerIdentities,
                    new PaperItemUseEligibilityCache(
                            itemCatalog,
                            new SkillProgressionQueryRepository(database.dataSource(), skillCatalog),
                            Math.max(1, getServer().getMaxPlayers())
                    )
            );
            RankedArenaRuleset rankedRuleset = RankedArenaRuleset.legacy189V1();
            RankedArenaRepository rankedArena = new RankedArenaRepository(database.dataSource(), rankedRuleset);
            rankedCommand = new PaperRankedCommand(
                    this,
                    playerIdentities,
                    new RankedMatchmakingRepository(database.dataSource(), rankedRuleset),
                    rankedArena,
                    new RankedLeaderboardRepository(database.dataSource(), rankedRuleset)
            );
            skillsCommand = new PaperSkillsCommand(
                    this,
                    playerIdentities,
                    new SkillProgressionRepository(database.dataSource(), skillCatalog),
                    skillCatalog
            );
            leaderboardCommand = new PaperSkillLeaderboardCommand(
                    this,
                    new SkillLeaderboardRepository(database.dataSource(), skillCatalog),
                    skillCatalog
            );
            bankCommand = new PaperBankCommand(
                    this,
                    playerIdentities,
                    new BankManagerRepository(database.dataSource(), bankTiers),
                    new CoinWalletRepository(database.dataSource()),
                    bankTiers
            );
            itemRepresentationValidator = new PaperPlayerItemRepresentationValidator(
                    this,
                    database.dataSource(),
                    itemCatalog
            );
            PaperCommodityStateMutator commodityMutator = new PaperCommodityStateMutator(this, itemCatalog);
            commodityDeliveryController = new PaperCommodityDeliveryController(
                    this,
                    database.dataSource(),
                    sessionController,
                    commodityMutator
            );
            uniqueDeliveryController = new PaperUniqueDeliveryController(
                    this,
                    database.dataSource(),
                    sessionController,
                    playerIdentities,
                    itemCatalog
            );
            bazaarRepository = new BazaarRepository(
                    database.dataSource(),
                    itemCatalog,
                    commodityMutator,
                    bazaarPolicy.executionFeeBasisPoints()
            );
            bazaarCommand = new PaperBazaarCommand(
                    this,
                    sessionController,
                    playerIdentities,
                    commodityMutator,
                    commodityDeliveryController,
                    bazaarRepository,
                    bazaarPolicy,
                    itemCatalog
            );
            auctionHouseCommand = new PaperAuctionHouseCommand(
                    this,
                    sessionController,
                    playerIdentities,
                    uniqueDeliveryController,
                    new AuctionHouseRepository(database.dataSource(), itemCatalog),
                    new AuctionHouseQueryRepository(database.dataSource()),
                    itemCatalog
            );
            PaperUniqueItemStateRemovalMutator uniqueItemRemoval = new PaperUniqueItemStateRemovalMutator(this);
            mapRuntime = PaperMapRuntime.start(
                    this,
                    bootstrapZoneInstance,
                    database.dataSource(),
                    sessionController,
                    playerIdentities,
                    itemCatalog,
                    uniqueItemRemoval
            );
            SecureTradeRepository secureTrades = new SecureTradeRepository(database.dataSource());
            tradeCommand = new PaperTradeCommand(
                    this,
                    sessionController,
                    playerIdentities,
                    commodityDeliveryController,
                    uniqueDeliveryController,
                    secureTrades,
                    new SecureTradeAssetRepository(
                            database.dataSource(),
                            itemCatalog,
                            commodityMutator,
                            uniqueItemRemoval
                    ),
                    new SecureTradeWithdrawalRepository(database.dataSource()),
                    new SecureTradeConfirmationRepository(database.dataSource()),
                    new SecureTradeResolutionRepository(database.dataSource()),
                    new SecureTradeQueryRepository(database.dataSource()),
                    itemCatalog,
                    commodityMutator,
                    uniqueItemRemoval
            );
            ClanMembershipRepository clanMemberships = new ClanMembershipRepository(database.dataSource());
            ClanQueryRepository clanQueries = new ClanQueryRepository(database.dataSource());
            ClanWarLifecycleRepository clanWars = new ClanWarLifecycleRepository(
                    database.dataSource(),
                    ClanWarRuleset.legacy189V1()
            );
            PaperClanCommand baseClanCommand = new PaperClanCommand(
                    this,
                    sessionController,
                    playerIdentities,
                    commodityDeliveryController,
                    uniqueDeliveryController,
                    clanMemberships,
                    new ClanRoleRepository(database.dataSource()),
                    new ClanTreasuryRepository(database.dataSource()),
                    new ClanStorageRepository(
                            database.dataSource(),
                            itemCatalog,
                            commodityMutator,
                            uniqueItemRemoval
                    ),
                    itemCatalog,
                    commodityMutator,
                    uniqueItemRemoval
            );
            PaperClanWarCommand clanWarCommand = new PaperClanWarCommand(
                    this,
                    sessionController,
                    playerIdentities,
                    clanMemberships,
                    clanQueries,
                    clanWars,
                    new ClanWarQueryRepository(database.dataSource()),
                    new ClanWarResolutionRepository(database.dataSource()),
                    new ClanWarLoadoutRepository(database.dataSource(), itemCatalog, uniqueItemRemoval),
                    new ClanWarLoadoutReadinessRepository(database.dataSource()),
                    itemCatalog,
                    uniqueItemRemoval
            );
            clanCommand = new PaperClanRouterCommand(
                    this,
                    baseClanCommand,
                    clanWarCommand,
                    playerIdentities,
                    clanMemberships,
                    clanQueries
            );
            BountyRepository bountyRepository = new BountyRepository(
                    database.dataSource(),
                    bountyContent.tiers(),
                    bountyContent,
                    BOUNTY_SUMMON_LEASE
            );
            bountyCommand = new PaperBountyCommand(
                    this,
                    playerIdentities,
                    commodityDeliveryController,
                    bountyContent,
                    bountyRepository,
                    new BountyPouchRepository(database.dataSource(), itemCatalog)
            );
            salvageCommand = new PaperSalvageCommand(
                    this,
                    sessionController,
                    commodityDeliveryController,
                    new SalvageRepository(database.dataSource(), salvageCatalog, uniqueItemRemoval),
                    salvageCatalog,
                    itemCatalog
            );

            PaperCommodityBatchStateMutator craftingIngredients = new PaperCommodityBatchStateMutator(commodityMutator);
            CraftingExperienceFulfillmentRepository craftingExperience = new CraftingExperienceFulfillmentRepository(
                    database.dataSource(),
                    craftingContent.experience(),
                    skillCatalog
            );
            CraftingRepository craftingRepository = new CraftingRepository(
                    database.dataSource(),
                    itemCatalog,
                    craftingContent.recipes(),
                    skillCatalog,
                    craftingIngredients
            );
            craftingController = new PaperCraftingController(
                    this,
                    sessionController,
                    new CraftingStateExecutionService(craftingRepository),
                    craftingExperience,
                    craftingContent.recipes(),
                    craftingIngredients,
                    commodityDeliveryController,
                    uniqueDeliveryController
            );
            commissionCommand = new PaperCraftingCommissionCommand(
                    this,
                    sessionController,
                    playerIdentities,
                    commodityDeliveryController,
                    uniqueDeliveryController,
                    new CraftingCommissionRepository(
                            database.dataSource(),
                            itemCatalog,
                            craftingContent.recipes(),
                            skillCatalog,
                            craftingIngredients
                    ),
                    new CraftingCommissionCompletionRepository(
                            database.dataSource(),
                            itemCatalog,
                            craftingContent.recipes(),
                            skillCatalog
                    ),
                    new CraftingCommissionQueryRepository(database.dataSource()),
                    craftingExperience,
                    craftingContent.recipes(),
                    itemCatalog,
                    craftingIngredients
            );

            ArtifactRepository artifactRepository = new ArtifactRepository(database.dataSource());
            PaperArtifactPlacementCatalog artifactPlacements = PaperArtifactPlacementCatalog.loadAndBootstrap(
                    ARTIFACT_PLACEMENT_CATALOG_RESOURCE,
                    artifactRepository
            );
            artifactDiscoveryListener = new PaperArtifactDiscoveryListener(
                    this,
                    playerIdentities,
                    artifactRepository,
                    artifactPlacements
            );
            AttunementRepository attunementRepository = new AttunementRepository(
                    database.dataSource(),
                    attunementProfiles
            );
            attunementCommand = new PaperAttunementCommand(
                    this,
                    playerIdentities,
                    attunementRepository,
                    attunementProfiles
            );

            if (bootstrapZoneInstance != null) {
                ResourceSourceRepository sourceRepository = new ResourceSourceRepository(
                        database.dataSource(),
                        resourceSourceCatalog
                );
                ResourceHarvestFulfillmentRepository fulfillmentRepository = new ResourceHarvestFulfillmentRepository(
                        database.dataSource(),
                        skillCatalog
                );
                ResourceGatheringService gatheringService = new ResourceGatheringService(
                        sourceRepository,
                        fulfillmentRepository
                );
                PaperResourceSessionResolver resourceSessions = new PaperResourceSessionResolver(
                        database.dataSource(),
                        backendId
                );

                PaperResourceSourcePlacementCatalog blockPlacements = PaperResourceSourcePlacementCatalog.loadResource(
                        RESOURCE_PLACEMENT_CATALOG_RESOURCE,
                        resourceSourceCatalog
                );
                resourceGatheringListener = new PaperResourceGatheringListener(
                        this,
                        backendId,
                        sessionController,
                        bootstrapZoneInstance,
                        blockPlacements,
                        sourceRepository,
                        gatheringService,
                        resourceSessions,
                        commodityDeliveryController
                );

                PaperResourceEntityPlacementCatalog entityPlacements = PaperResourceEntityPlacementCatalog.loadResource(
                        RESOURCE_ENTITY_PLACEMENT_CATALOG_RESOURCE,
                        resourceSourceCatalog
                );
                resourceEntityController = new PaperResourceEntityController(
                        this,
                        backendId,
                        bootstrapZoneInstance,
                        entityPlacements,
                        sourceRepository,
                        new ResourceEntitySpawnRepository(database.dataSource(), resourceSourceCatalog),
                        gatheringService,
                        resourceSessions,
                        commodityDeliveryController
                );
                bountyProgressService = new PaperBountyProgressService(
                        this,
                        playerIdentities,
                        bountyContent,
                        new BountyKillProgressRepository(database.dataSource())
                );
                bountyBossController = new PaperBountyBossController(
                        this,
                        backendId,
                        bootstrapZoneInstance,
                        playerIdentities,
                        bountyContent,
                        bountyBossPlacements,
                        bountyRepository,
                        new BountyBossMaterializationRepository(database.dataSource(), bountyContent.tiers()),
                        new BountySummonRecoveryRepository(database.dataSource())
                );
            }
        } catch (RuntimeException | SQLException exception) {
            stopMapRuntime();
            stopBountyBossController();
            stopBountyProgressService();
            stopResourceEntityController();
            stopBootstrapZoneQuietly();
            markBackendOfflineQuietly();
            closeDatabase();
            artifactDiscoveryListener = null;
            resourceGatheringListener = null;
            commodityDeliveryController = null;
            itemRepresentationValidator = null;
            resourceSourceCatalog = null;
            skillCatalog = null;
            itemCatalog = null;
            throw new IllegalStateException("Failed to initialize persistent network foundation/content", exception);
        }

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(sessionController, this);
        getServer().getPluginManager().registerEvents(itemUseEligibilityController, this);
        getServer().getPluginManager().registerEvents(commodityDeliveryController, this);
        getServer().getPluginManager().registerEvents(uniqueDeliveryController, this);
        getServer().getPluginManager().registerEvents(rankedCommand, this);
        getServer().getPluginManager().registerEvents(artifactDiscoveryListener, this);
        getServer().getPluginManager().registerEvents(
                new PaperItemRepresentationGate(this, itemRepresentationValidator),
                this
        );
        getServer().getPluginManager().registerEvents(new FrozenPlayerMutationGuard(sessionController), this);
        if (resourceGatheringListener != null) {
            getServer().getPluginManager().registerEvents(resourceGatheringListener, this);
        }
        if (resourceEntityController != null && resourceEntityController.managedSourceCount() > 0) {
            getServer().getPluginManager().registerEvents(resourceEntityController, this);
            resourceEntityController.start();
        }
        if (bountyProgressService != null) {
            bountyProgressService.start();
        }
        if (bountyBossController != null) {
            getServer().getPluginManager().registerEvents(bountyBossController, this);
            bountyBossController.start();
        }
        getServer().getMessenger().registerOutgoingPluginChannel(this, TransferPluginMessage.CHANNEL);

        PaperIntegrityCommand.install(this, database.dataSource(), itemCatalog);
        PluginCommand devZone = Objects.requireNonNull(getCommand("devzone"), "devzone command missing from plugin.yml");
        devZone.setExecutor(new DevZoneCommand(sessionController));
        PluginCommand attune = Objects.requireNonNull(getCommand("attune"), "attune command missing from plugin.yml");
        attune.setExecutor(attunementCommand);
        attune.setTabCompleter(attunementCommand);
        PluginCommand skills = Objects.requireNonNull(getCommand("skills"), "skills command missing from plugin.yml");
        skills.setExecutor(skillsCommand);
        PluginCommand leaderboard = Objects.requireNonNull(
                getCommand("leaderboard"),
                "leaderboard command missing from plugin.yml"
        );
        leaderboard.setExecutor(leaderboardCommand);
        leaderboard.setTabCompleter(leaderboardCommand);
        PluginCommand bank = Objects.requireNonNull(getCommand("bank"), "bank command missing from plugin.yml");
        bank.setExecutor(bankCommand);
        bank.setTabCompleter(bankCommand);
        PluginCommand bazaar = Objects.requireNonNull(getCommand("bazaar"), "bazaar command missing from plugin.yml");
        bazaar.setExecutor(bazaarCommand);
        bazaar.setTabCompleter(bazaarCommand);
        PluginCommand auctionHouse = Objects.requireNonNull(getCommand("ah"), "ah command missing from plugin.yml");
        auctionHouse.setExecutor(auctionHouseCommand);
        auctionHouse.setTabCompleter(auctionHouseCommand);
        PluginCommand trade = Objects.requireNonNull(getCommand("trade"), "trade command missing from plugin.yml");
        trade.setExecutor(tradeCommand);
        trade.setTabCompleter(tradeCommand);
        PluginCommand clan = Objects.requireNonNull(getCommand("clan"), "clan command missing from plugin.yml");
        clan.setExecutor(clanCommand);
        clan.setTabCompleter(clanCommand);
        PluginCommand ranked = Objects.requireNonNull(getCommand("ranked"), "ranked command missing from plugin.yml");
        ranked.setExecutor(rankedCommand);
        ranked.setTabCompleter(rankedCommand);
        PluginCommand commission = Objects.requireNonNull(
                getCommand("commission"),
                "commission command missing from plugin.yml"
        );
        commission.setExecutor(commissionCommand);
        commission.setTabCompleter(commissionCommand);
        PluginCommand bounty = Objects.requireNonNull(getCommand("bounty"), "bounty command missing from plugin.yml");
        bounty.setExecutor(bountyCommand);
        bounty.setTabCompleter(bountyCommand);
        PluginCommand salvage = Objects.requireNonNull(getCommand("salvage"), "salvage command missing from plugin.yml");
        salvage.setExecutor(salvageCommand);
        salvage.setTabCompleter(salvageCommand);
        PluginCommand craft = Objects.requireNonNull(getCommand("craft"), "craft command missing from plugin.yml");
        craft.setExecutor(craftingController);
        craft.setTabCompleter(craftingController);
        craftingController.recoverPendingExperience();
        PaperBazaarRecovery.schedule(
                this,
                bazaarRepository,
                bazaarPolicy,
                itemCatalog,
                commodityDeliveryController
        );

        heartbeatTask = getServer().getScheduler().runTaskTimerAsynchronously(
                this,
                this::sendHeartbeat,
                HEARTBEAT_PERIOD_TICKS,
                HEARTBEAT_PERIOD_TICKS
        );
        checkpointTask = getServer().getScheduler().runTaskTimer(
                this,
                sessionController::checkpointOnlinePlayers,
                CHECKPOINT_PERIOD_TICKS,
                CHECKPOINT_PERIOD_TICKS
        );
        deliveryPumpTask = getServer().getScheduler().runTaskTimer(
                this,
                () -> getServer().getOnlinePlayers().forEach(player -> {
                    commodityDeliveryController.requestDrain(player.getUniqueId());
                    uniqueDeliveryController.requestDrain(player.getUniqueId());
                }),
                DELIVERY_PUMP_PERIOD_TICKS,
                DELIVERY_PUMP_PERIOD_TICKS
        );

        String zoneDescription = bootstrapZoneInstance == null
                ? "no bootstrap zone"
                : "bootstrap zone " + bootstrapZoneInstance.zoneId();
        int itemDefinitionCount = itemCatalog.size();
        int resourceCount = resourceGatheringListener == null ? 0 : resourceGatheringListener.registeredSourceCount();
        int entitySourceCount = resourceEntityController == null ? 0 : resourceEntityController.managedSourceCount();
        int artifactCount = artifactDiscoveryListener.registeredArtifactCount();
        getLogger().info(() -> "Started backend " + backendId + " with " + zoneDescription
                + ", " + itemDefinitionCount + " validated item definitions, "
                + resourceCount + " authored renewable block sources, "
                + entitySourceCount + " authorized ordinary-PvE entity sources and "
                + artifactCount + " hidden Artifacts");
    }

    @Override
    public void onDisable() {
        if (deliveryPumpTask != null) {
            deliveryPumpTask.cancel();
            deliveryPumpTask = null;
        }
        if (checkpointTask != null) {
            checkpointTask.cancel();
            checkpointTask = null;
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }

        stopMapRuntime();
        stopBountyBossController();
        stopBountyProgressService();
        stopResourceEntityController();

        if (sessionController != null) {
            sessionController.shutdown();
            sessionController = null;
        }

        artifactDiscoveryListener = null;
        resourceGatheringListener = null;
        commodityDeliveryController = null;
        stopBootstrapZoneQuietly();
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, TransferPluginMessage.CHANNEL);
        markBackendOfflineQuietly();
        closeDatabase();
        itemRepresentationValidator = null;
        resourceSourceCatalog = null;
        skillCatalog = null;
        itemCatalog = null;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        onlinePlayers.incrementAndGet();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        onlinePlayers.updateAndGet(current -> Math.max(0, current - 1));
    }

    public String backendId() {
        return backendId;
    }

    /** Compatibility alias for the original bootstrap API. */
    public String serverId() {
        return backendId;
    }

    ItemCatalog itemCatalog() {
        if (itemCatalog == null) {
            throw new IllegalStateException("Item catalog is not initialized");
        }
        return itemCatalog;
    }

    Optional<PaperBountyBossController> bountyBossController() {
        return Optional.ofNullable(bountyBossController);
    }

    private void sendHeartbeat() {
        int playerCount = onlinePlayers.get();
        try {
            backendRegistry.heartbeat(backendId, playerCount);
            BootstrapZoneInstance zone = bootstrapZoneInstance;
            if (zone != null) {
                zone.heartbeat(playerCount);
            }
        } catch (SQLException exception) {
            getLogger().log(Level.WARNING, "Backend/zone heartbeat failed", exception);
        }

        PaperSessionController controller = sessionController;
        if (controller != null) {
            controller.heartbeat();
        }
    }

    private void stopMapRuntime() {
        if (mapRuntime != null) {
            mapRuntime.shutdown();
            mapRuntime = null;
        }
    }

    private void stopBountyBossController() {
        if (bountyBossController != null) {
            bountyBossController.stop();
            bountyBossController = null;
        }
    }

    private void stopBountyProgressService() {
        if (bountyProgressService != null) {
            bountyProgressService.stop();
            bountyProgressService = null;
        }
    }

    private void stopResourceEntityController() {
        if (resourceEntityController != null) {
            resourceEntityController.stop();
            resourceEntityController = null;
        }
    }

    private void stopBootstrapZoneQuietly() {
        if (bootstrapZoneInstance == null) {
            return;
        }
        try {
            bootstrapZoneInstance.stop();
        } catch (SQLException exception) {
            getLogger().log(Level.WARNING, "Could not mark bootstrap zone instance stopped", exception);
        } finally {
            bootstrapZoneInstance = null;
        }
    }

    private void markBackendOfflineQuietly() {
        if (backendRegistry == null || backendId == null) {
            return;
        }
        try {
            backendRegistry.markOffline(backendId);
        } catch (SQLException exception) {
            getLogger().log(Level.WARNING, "Could not mark backend offline", exception);
        }
    }

    private void closeDatabase() {
        if (database != null) {
            database.close();
            database = null;
        }
    }

    private static String requireBackendId() {
        String value = System.getenv("BACKEND_ID");
        if (value == null || value.isBlank()) {
            value = System.getenv("SERVER_ID");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("BACKEND_ID (or legacy SERVER_ID) must be set");
        }
        return value.trim();
    }
}

package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.clan.ClanAssetException;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanCommodityStorageDepositResult;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanCommodityStorageSnapshot;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanCommodityStorageWithdrawResult;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanMemberSnapshot;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanMembershipException;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanMembershipRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanRole;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanRoleRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanSnapshot;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanStorageRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanTreasuryRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanTreasurySnapshot;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanTreasuryTransferResult;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanUniqueStorageDepositResult;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanUniqueStorageItemSnapshot;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanUniqueStorageWithdrawResult;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarException;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinCurrency;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationClaim;
import io.github.kevinrabbe.minecraftserver.common.session.SessionConflictException;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/** Command-first Paper bridge for persistent MMO clan social state, treasury, and shared storage. */
final class PaperClanCommand implements CommandExecutor, TabCompleter {
    private static final Duration INVITE_LIFETIME = Duration.ofDays(7);
    private static final String TREASURY_DEPOSIT_REASON = "clan.player_treasury_deposit";
    private static final String TREASURY_WITHDRAW_REASON = "clan.player_treasury_withdraw";
    private static final String STORAGE_COMMODITY_DEPOSIT_REASON = "clan.player_storage_commodity_deposit";
    private static final String STORAGE_COMMODITY_WITHDRAW_REASON = "clan.player_storage_commodity_withdraw";
    private static final String STORAGE_UNIQUE_DEPOSIT_REASON = "clan.player_storage_unique_deposit";
    private static final String STORAGE_UNIQUE_WITHDRAW_REASON = "clan.player_storage_unique_withdraw";

    private final MinecraftServerPlugin plugin;
    private final PaperSessionController sessions;
    private final PaperPlayerIdentityResolver playerIdentities;
    private final PaperCommodityDeliveryController commodityDeliveries;
    private final PaperUniqueDeliveryController uniqueDeliveries;
    private final ClanMembershipRepository memberships;
    private final ClanRoleRepository roles;
    private final ClanTreasuryRepository treasury;
    private final ClanStorageRepository storage;
    private final ItemCatalog itemCatalog;
    private final PaperCommodityStateMutator commodityMutator;
    private final PaperUniqueItemStateRemovalMutator uniqueItemRemoval;
    private final PaperItemIdentityCodec identityCodec;

    PaperClanCommand(
            MinecraftServerPlugin plugin,
            PaperSessionController sessions,
            PaperPlayerIdentityResolver playerIdentities,
            PaperCommodityDeliveryController commodityDeliveries,
            PaperUniqueDeliveryController uniqueDeliveries,
            ClanMembershipRepository memberships,
            ClanRoleRepository roles,
            ClanTreasuryRepository treasury,
            ClanStorageRepository storage,
            ItemCatalog itemCatalog,
            PaperCommodityStateMutator commodityMutator,
            PaperUniqueItemStateRemovalMutator uniqueItemRemoval
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.playerIdentities = Objects.requireNonNull(playerIdentities, "playerIdentities");
        this.commodityDeliveries = Objects.requireNonNull(commodityDeliveries, "commodityDeliveries");
        this.uniqueDeliveries = Objects.requireNonNull(uniqueDeliveries, "uniqueDeliveries");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.treasury = Objects.requireNonNull(treasury, "treasury");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.commodityMutator = Objects.requireNonNull(commodityMutator, "commodityMutator");
        this.uniqueItemRemoval = Objects.requireNonNull(uniqueItemRemoval, "uniqueItemRemoval");
        this.identityCodec = new PaperItemIdentityCodec(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can use clan commands."));
            return true;
        }
        if (args.length == 0) {
            scheduleStatus(player.getUniqueId());
            return true;
        }

        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "status" -> {
                    if (args.length != 1) usage(player); else scheduleStatus(player.getUniqueId());
                }
                case "create" -> {
                    if (args.length < 3) {
                        usage(player);
                    } else {
                        String tag = args[1];
                        String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                        scheduleCreate(player.getUniqueId(), name, tag);
                    }
                }
                case "invite" -> {
                    if (args.length != 2) usage(player); else invite(player, args[1]);
                }
                case "accept" -> {
                    if (args.length != 2) usage(player); else scheduleAccept(player.getUniqueId(), parseUuid(args[1], "invite ID"));
                }
                case "leave" -> {
                    if (args.length != 1) usage(player); else scheduleLeave(player.getUniqueId());
                }
                case "kick" -> {
                    if (args.length != 2) usage(player); else withOnlineTarget(player, args[1], this::scheduleKick);
                }
                case "role" -> {
                    if (args.length != 3) {
                        usage(player);
                    } else {
                        ClanRole role = parseManagedRole(args[2]);
                        withOnlineTarget(player, args[1], (actor, target) -> scheduleRole(actor, target, role));
                    }
                }
                case "transfer" -> {
                    if (args.length < 2 || args.length > 3) {
                        usage(player);
                    } else {
                        ClanRole formerLeaderRole = args.length == 3 ? parseManagedRole(args[2]) : ClanRole.OFFICER;
                        withOnlineTarget(
                                player,
                                args[1],
                                (actor, target) -> scheduleTransfer(actor, target, formerLeaderRole)
                        );
                    }
                }
                case "treasury" -> handleTreasuryCommand(player, args);
                case "storage" -> handleStorageCommand(player, args);
                default -> usage(player);
            }
        } catch (IllegalArgumentException exception) {
            player.sendMessage(Component.text(playerMessage(exception, "Invalid clan command arguments.")));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of(
                            "status", "create", "invite", "accept", "leave", "kick", "role", "transfer",
                            "treasury", "storage"
                    ).stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && List.of("invite", "kick", "role", "transfer").contains(args[0].toLowerCase(Locale.ROOT))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("treasury")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of("deposit", "withdraw").stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("storage")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of("commodity", "items", "deposit", "withdraw", "deposit-item", "withdraw-item")
                    .stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("role") || args[0].equalsIgnoreCase("transfer"))) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return List.of("member", "officer").stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("storage")
                && List.of("commodity", "deposit", "withdraw").contains(args[1].toLowerCase(Locale.ROOT))) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return itemCatalog.definitions().stream()
                    .filter(definition -> definition.identityKind() == ItemIdentityKind.COMMODITY)
                    .map(ItemDefinition::definitionId)
                    .filter(id -> id.startsWith(prefix))
                    .sorted()
                    .toList();
        }
        return List.of();
    }

    private void handleTreasuryCommand(Player player, String[] args) {
        if (args.length == 1) {
            scheduleTreasuryStatus(player.getUniqueId());
            return;
        }
        if (args.length != 3) {
            usage(player);
            return;
        }
        long amountMinor = parsePositiveCoin(args[2]);
        if (args[1].equalsIgnoreCase("deposit")) {
            scheduleTreasuryTransfer(player.getUniqueId(), amountMinor, true);
        } else if (args[1].equalsIgnoreCase("withdraw")) {
            scheduleTreasuryTransfer(player.getUniqueId(), amountMinor, false);
        } else {
            usage(player);
        }
    }

    private void handleStorageCommand(Player player, String[] args) {
        if (args.length < 2) {
            storageUsage(player);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "commodity" -> {
                if (args.length != 3) {
                    storageUsage(player);
                } else {
                    String definitionId = requireCommodityDefinition(args[2]).definitionId();
                    scheduleStorageCommodityStatus(player.getUniqueId(), definitionId);
                }
            }
            case "items" -> {
                if (args.length > 3) {
                    storageUsage(player);
                } else {
                    int limit = args.length == 3 ? parseBoundedLimit(args[2], 100) : 20;
                    scheduleStorageItems(player.getUniqueId(), limit);
                }
            }
            case "deposit" -> {
                if (args.length != 4) {
                    storageUsage(player);
                } else {
                    String definitionId = requireCommodityDefinition(args[2]).definitionId();
                    depositStorageCommodity(player, definitionId, parsePositiveLong(args[3], "quantity"));
                }
            }
            case "withdraw" -> {
                if (args.length != 4) {
                    storageUsage(player);
                } else {
                    String definitionId = requireCommodityDefinition(args[2]).definitionId();
                    scheduleStorageCommodityWithdraw(
                            player.getUniqueId(), definitionId, parsePositiveLong(args[3], "quantity")
                    );
                }
            }
            case "deposit-item" -> {
                if (args.length != 2) storageUsage(player); else depositStorageUnique(player);
            }
            case "withdraw-item" -> {
                if (args.length != 4) {
                    storageUsage(player);
                } else {
                    scheduleStorageUniqueWithdraw(
                            player.getUniqueId(),
                            parseUuid(args[2], "item instance ID"),
                            parseNonNegativeLong(args[3], "item state version")
                    );
                }
            }
            default -> storageUsage(player);
        }
    }

    private void scheduleStatus(UUID minecraftUuid) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                ClanMemberSnapshot member = memberships.loadMember(playerId);
                ClanSnapshot clan = memberships.loadClan(member.clanId());
                ClanTreasurySnapshot wallet = treasury.load(member.clanId());
                sendIfOnline(
                        minecraftUuid,
                        "Clan [" + clan.tag() + "] " + clan.name() + " — role " + member.role()
                                + " — treasury " + formatCoin(wallet.balanceMinor())
                                + " — id " + clan.clanId()
                );
            } catch (ClanMembershipException exception) {
                sendIfOnline(minecraftUuid, "You are not currently in a clan.");
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(minecraftUuid, "Could not load clan status.", exception);
            }
        });
    }

    private void scheduleCreate(UUID minecraftUuid, String name, String tag) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), playerId, name, tag);
                sendIfOnline(
                        minecraftUuid,
                        "Created clan [" + clan.tag() + "] " + clan.name() + " with id " + clan.clanId() + "."
                );
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(minecraftUuid, "Could not create clan.", exception);
            }
        });
    }

    private void invite(Player actor, String targetName) {
        Player target = plugin.getServer().getPlayerExact(targetName);
        if (target == null || !target.isOnline()) {
            actor.sendMessage(Component.text("That player must be online to receive a clan invitation."));
            return;
        }
        if (target.getUniqueId().equals(actor.getUniqueId())) {
            actor.sendMessage(Component.text("You cannot invite yourself."));
            return;
        }
        UUID actorMinecraftUuid = actor.getUniqueId();
        UUID targetMinecraftUuid = target.getUniqueId();
        String actorName = actor.getName();
        String liveTargetName = target.getName();
        runAsync(() -> {
            try {
                UUID actorPlayerId = requirePlayerId(actorMinecraftUuid);
                UUID targetPlayerId = requirePlayerId(targetMinecraftUuid);
                ClanMemberSnapshot actorMember = memberships.loadMember(actorPlayerId);
                var invitation = memberships.invite(
                        UUID.randomUUID(),
                        actorMember.clanId(),
                        actorPlayerId,
                        targetPlayerId,
                        Instant.now().plus(INVITE_LIFETIME)
                );
                ClanSnapshot clan = memberships.loadClan(actorMember.clanId());
                sendIfOnline(actorMinecraftUuid, "Invited " + liveTargetName + " to [" + clan.tag() + "] " + clan.name() + ".");
                sendIfOnline(
                        targetMinecraftUuid,
                        actorName + " invited you to [" + clan.tag() + "] " + clan.name()
                                + ". Accept with /clan accept " + invitation.inviteId()
                );
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(actorMinecraftUuid, "Could not create clan invitation.", exception);
            }
        });
    }

    private void scheduleAccept(UUID minecraftUuid, UUID inviteId) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                ClanMemberSnapshot member = memberships.acceptInvite(UUID.randomUUID(), inviteId, playerId);
                ClanSnapshot clan = memberships.loadClan(member.clanId());
                sendIfOnline(minecraftUuid, "Joined [" + clan.tag() + "] " + clan.name() + " as " + member.role() + ".");
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(minecraftUuid, "Could not accept clan invitation.", exception);
            }
        });
    }

    private void scheduleLeave(UUID minecraftUuid) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                ClanMemberSnapshot member = memberships.loadMember(playerId);
                memberships.leaveClan(UUID.randomUUID(), member.clanId(), playerId);
                sendIfOnline(minecraftUuid, "You left the clan.");
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(minecraftUuid, "Could not leave clan.", exception);
            }
        });
    }

    private void scheduleKick(UUID actorMinecraftUuid, UUID targetMinecraftUuid) {
        runAsync(() -> {
            try {
                UUID actorPlayerId = requirePlayerId(actorMinecraftUuid);
                UUID targetPlayerId = requirePlayerId(targetMinecraftUuid);
                ClanMemberSnapshot actor = memberships.loadMember(actorPlayerId);
                memberships.removeMember(UUID.randomUUID(), actor.clanId(), actorPlayerId, targetPlayerId);
                sendIfOnline(actorMinecraftUuid, "Removed that player from the clan.");
                sendIfOnline(targetMinecraftUuid, "You were removed from your clan.");
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(actorMinecraftUuid, "Could not remove clan member.", exception);
            }
        });
    }

    private void scheduleRole(UUID actorMinecraftUuid, UUID targetMinecraftUuid, ClanRole role) {
        runAsync(() -> {
            try {
                UUID actorPlayerId = requirePlayerId(actorMinecraftUuid);
                UUID targetPlayerId = requirePlayerId(targetMinecraftUuid);
                ClanMemberSnapshot actor = memberships.loadMember(actorPlayerId);
                ClanMemberSnapshot changed = roles.setMemberRole(
                        UUID.randomUUID(), actor.clanId(), actorPlayerId, targetPlayerId, role
                );
                sendIfOnline(actorMinecraftUuid, "Clan role updated to " + changed.role() + ".");
                sendIfOnline(targetMinecraftUuid, "Your clan role is now " + changed.role() + ".");
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(actorMinecraftUuid, "Could not change clan role.", exception);
            }
        });
    }

    private void scheduleTransfer(UUID actorMinecraftUuid, UUID targetMinecraftUuid, ClanRole formerLeaderRole) {
        runAsync(() -> {
            try {
                UUID actorPlayerId = requirePlayerId(actorMinecraftUuid);
                UUID targetPlayerId = requirePlayerId(targetMinecraftUuid);
                ClanMemberSnapshot actor = memberships.loadMember(actorPlayerId);
                memberships.transferLeadership(
                        UUID.randomUUID(), actor.clanId(), actorPlayerId, targetPlayerId, formerLeaderRole
                );
                sendIfOnline(actorMinecraftUuid, "Leadership transferred. Your role is now " + formerLeaderRole + ".");
                sendIfOnline(targetMinecraftUuid, "You are now the clan LEADER.");
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(actorMinecraftUuid, "Could not transfer clan leadership.", exception);
            }
        });
    }

    private void scheduleTreasuryStatus(UUID minecraftUuid) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                ClanMemberSnapshot member = memberships.loadMember(playerId);
                ClanTreasurySnapshot snapshot = treasury.load(member.clanId());
                sendIfOnline(minecraftUuid, "Clan treasury: " + formatCoin(snapshot.balanceMinor()) + ".");
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(minecraftUuid, "Could not load clan treasury.", exception);
            }
        });
    }

    private void scheduleTreasuryTransfer(UUID minecraftUuid, long amountMinor, boolean deposit) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                ClanMemberSnapshot member = memberships.loadMember(playerId);
                ClanTreasuryTransferResult result = deposit
                        ? treasury.deposit(UUID.randomUUID(), member.clanId(), playerId, amountMinor, TREASURY_DEPOSIT_REASON)
                        : treasury.withdraw(UUID.randomUUID(), member.clanId(), playerId, amountMinor, TREASURY_WITHDRAW_REASON);
                sendIfOnline(
                        minecraftUuid,
                        (deposit ? "Deposited " : "Withdrew ") + formatCoin(amountMinor)
                                + (deposit ? " into" : " from") + " the clan treasury. Treasury: "
                                + formatCoin(result.treasury().balanceMinor()) + "; wallet: " + formatCoin(result.walletBalanceMinor()) + "."
                );
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(minecraftUuid, "Could not update clan treasury.", exception);
            }
        });
    }

    private void scheduleStorageCommodityStatus(UUID minecraftUuid, String definitionId) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                ClanMemberSnapshot member = memberships.loadMember(playerId);
                Optional<ClanCommodityStorageSnapshot> snapshot = storage.loadCommodity(member.clanId(), definitionId);
                long quantity = snapshot.map(ClanCommodityStorageSnapshot::quantity).orElse(0L);
                sendIfOnline(minecraftUuid, "Clan storage: " + quantity + " " + displayName(definitionId) + ".");
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(minecraftUuid, "Could not load clan commodity storage.", exception);
            }
        });
    }

    private void scheduleStorageItems(UUID minecraftUuid, int limit) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                ClanMemberSnapshot member = memberships.loadMember(playerId);
                List<ClanUniqueStorageItemSnapshot> items = storage.listUniqueItems(member.clanId(), limit);
                ArrayList<String> messages = new ArrayList<>();
                messages.add("Clan unique storage: " + items.size() + " item(s) shown.");
                for (ClanUniqueStorageItemSnapshot item : items) {
                    messages.add(
                            "- " + displayName(item.definitionId()) + " {" + item.itemInstanceId()
                                    + " @v" + item.itemStateVersion() + "}"
                    );
                }
                sendMessagesIfOnline(minecraftUuid, messages);
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(minecraftUuid, "Could not list clan unique storage.", exception);
            }
        });
    }

    private void depositStorageCommodity(Player player, String definitionId, long quantity) {
        UUID minecraftUuid = player.getUniqueId();
        if (sessions.isMutationFrozen(minecraftUuid)) {
            player.sendMessage(Component.text("Your persistent state is busy. Try again shortly."));
            return;
        }
        AtomicReference<ClanCommodityStorageDepositResult> committed = new AtomicReference<>();
        sessions.mutateAuthoritativeState(player, context -> {
            ClanMemberSnapshot member = memberships.loadMember(context.playerId());
            byte[] nextPayload = commodityMutator.remove(
                    context.playerId(), definitionId, quantity, context.currentStatePayload()
            );
            ClanCommodityStorageDepositResult result = storage.depositCommodity(
                    UUID.randomUUID(),
                    member.clanId(),
                    context.sessionId(),
                    context.backendId(),
                    context.stateVersion(),
                    definitionId,
                    quantity,
                    context.logicalZoneId(),
                    context.entryPoint(),
                    nextPayload,
                    STORAGE_COMMODITY_DEPOSIT_REASON
            );
            committed.set(result);
            return new PaperAuthoritativeStateMutation.Result(result.playerStateVersion(), nextPayload);
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                handleMutationFailure(minecraftUuid, "Could not deposit commodity into clan storage.", failure);
                return;
            }
            ClanCommodityStorageDepositResult result = committed.get();
            if (result == null) {
                plugin.getLogger().severe("Clan commodity deposit committed without captured result");
                return;
            }
            sendIfOnline(
                    minecraftUuid,
                    "Deposited " + result.depositedQuantity() + " " + displayName(definitionId)
                            + " into clan storage. Stored: " + result.storage().quantity() + "."
            );
        });
    }

    private void scheduleStorageCommodityWithdraw(UUID minecraftUuid, String definitionId, long quantity) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                ClanMemberSnapshot member = memberships.loadMember(playerId);
                ClanCommodityStorageWithdrawResult result = storage.withdrawCommodity(
                        UUID.randomUUID(),
                        member.clanId(),
                        playerId,
                        definitionId,
                        quantity,
                        STORAGE_COMMODITY_WITHDRAW_REASON
                );
                commodityDeliveries.requestDrain(minecraftUuid);
                sendIfOnline(
                        minecraftUuid,
                        "Withdrew " + result.withdrawnQuantity() + " " + displayName(definitionId)
                                + " from clan storage. Delivery is secured; stored: " + result.storage().quantity() + "."
                );
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(minecraftUuid, "Could not withdraw clan commodity.", exception);
            }
        });
    }

    private void depositStorageUnique(Player player) {
        UUID minecraftUuid = player.getUniqueId();
        if (sessions.isMutationFrozen(minecraftUuid)) {
            player.sendMessage(Component.text("Your persistent state is busy. Try again shortly."));
            return;
        }
        final UniqueClaim claim;
        try {
            claim = requireUniqueClaim(player.getInventory().getItemInMainHand());
        } catch (PaperItemRepresentationException | IllegalArgumentException exception) {
            player.sendMessage(Component.text(playerMessage(exception, "Hold one managed unique item in your main hand.")));
            return;
        }
        AtomicReference<ClanUniqueStorageDepositResult> committed = new AtomicReference<>();
        sessions.mutateAuthoritativeState(player, context -> {
            ClanMemberSnapshot member = memberships.loadMember(context.playerId());
            byte[] nextPayload = uniqueItemRemoval.remove(
                    context.playerId(), claim.itemInstanceId(), claim.authorityVersion(), context.currentStatePayload()
            );
            ClanUniqueStorageDepositResult result = storage.depositUniqueItem(
                    UUID.randomUUID(),
                    member.clanId(),
                    context.sessionId(),
                    context.backendId(),
                    context.stateVersion(),
                    claim.itemInstanceId(),
                    claim.authorityVersion(),
                    context.logicalZoneId(),
                    context.entryPoint(),
                    nextPayload,
                    STORAGE_UNIQUE_DEPOSIT_REASON
            );
            committed.set(result);
            return new PaperAuthoritativeStateMutation.Result(result.playerStateVersion(), nextPayload);
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                handleMutationFailure(minecraftUuid, "Could not deposit unique item into clan storage.", failure);
                return;
            }
            ClanUniqueStorageDepositResult result = committed.get();
            if (result == null) {
                plugin.getLogger().severe("Clan unique deposit committed without captured result");
                return;
            }
            sendIfOnline(
                    minecraftUuid,
                    "Deposited " + displayName(claim.definitionId()) + " into clan storage as "
                            + result.itemInstanceId() + " @v" + result.itemStateVersion() + "."
            );
        });
    }

    private void scheduleStorageUniqueWithdraw(UUID minecraftUuid, UUID itemInstanceId, long itemStateVersion) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                ClanMemberSnapshot member = memberships.loadMember(playerId);
                ClanUniqueStorageWithdrawResult result = storage.withdrawUniqueItem(
                        UUID.randomUUID(),
                        member.clanId(),
                        playerId,
                        itemInstanceId,
                        itemStateVersion,
                        STORAGE_UNIQUE_WITHDRAW_REASON
                );
                uniqueDeliveries.requestDrain(minecraftUuid);
                sendIfOnline(
                        minecraftUuid,
                        "Withdrew unique item " + result.itemInstanceId()
                                + " from clan storage. Delivery is secured at item version " + result.itemStateVersion() + "."
                );
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(minecraftUuid, "Could not withdraw clan unique item.", exception);
            }
        });
    }

    private UniqueClaim requireUniqueClaim(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("Hold one unique item in your main hand to deposit it.");
        }
        Optional<ItemRepresentationClaim> optional = identityCodec.readClaim(stack, "main_hand");
        if (optional.isEmpty()) {
            throw new IllegalArgumentException("The main-hand item is not a managed server item.");
        }
        ItemRepresentationClaim claim = optional.orElseThrow();
        if (!claim.individualClaim() || claim.itemInstanceId() == null || claim.authorityVersion() == null
                || claim.amount() != 1) {
            throw new IllegalArgumentException("Only individualized one-of-one items can enter clan unique storage.");
        }
        ItemDefinition definition = itemCatalog.find(claim.definitionId()).orElseThrow(
                () -> new PaperItemRepresentationException("The main-hand item has an unknown definition.")
        );
        if (definition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
            throw new IllegalArgumentException("Only individualized items can enter clan unique storage.");
        }
        if (!definition.minecraftMaterial().equals(claim.minecraftMaterial())) {
            throw new PaperItemRepresentationException("The main-hand item material does not match its definition.");
        }
        return new UniqueClaim(claim.itemInstanceId(), claim.authorityVersion(), claim.definitionId());
    }

    private ItemDefinition requireCommodityDefinition(String definitionId) {
        ItemDefinition definition = itemCatalog.find(definitionId).orElseThrow(
                () -> new IllegalArgumentException("Unknown commodity: " + definitionId)
        );
        if (definition.identityKind() != ItemIdentityKind.COMMODITY) {
            throw new IllegalArgumentException("Definition is not a commodity: " + definitionId);
        }
        return definition;
    }

    private void withOnlineTarget(Player actor, String targetName, PlayerPairAction action) {
        Player target = plugin.getServer().getPlayerExact(targetName);
        if (target == null || !target.isOnline()) {
            actor.sendMessage(Component.text("That player must be online for this clan command."));
            return;
        }
        if (target.getUniqueId().equals(actor.getUniqueId())) {
            actor.sendMessage(Component.text("Choose another clan member."));
            return;
        }
        action.run(actor.getUniqueId(), target.getUniqueId());
    }

    private UUID requirePlayerId(UUID minecraftUuid) throws SQLException {
        return playerIdentities.resolve(minecraftUuid).orElseThrow(
                () -> new ClanMembershipException("Persistent player identity is not available.")
        );
    }

    private void handleMutationFailure(UUID minecraftUuid, String fallback, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof SessionConflictException) {
            sendIfOnline(minecraftUuid, "Your persistent state changed. Review clan storage and try again.");
            return;
        }
        if (cause instanceof ClanMembershipException
                || cause instanceof ClanAssetException
                || cause instanceof BazaarException
                || cause instanceof PaperItemRepresentationException
                || cause instanceof IllegalArgumentException) {
            sendIfOnline(minecraftUuid, playerMessage(cause, fallback));
            return;
        }
        plugin.getLogger().log(Level.WARNING, fallback, cause);
        sendIfOnline(minecraftUuid, fallback);
    }

    private void handleAsyncFailure(UUID minecraftUuid, String fallback, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof ClanMembershipException || cause instanceof ClanAssetException || cause instanceof IllegalArgumentException) {
            sendIfOnline(minecraftUuid, playerMessage(cause, fallback));
            return;
        }
        plugin.getLogger().log(Level.WARNING, fallback, cause);
        sendIfOnline(minecraftUuid, fallback);
    }

    private void runAsync(Runnable task) {
        if (!plugin.isEnabled()) return;
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule clan work", exception);
        }
    }

    private void sendIfOnline(UUID minecraftUuid, String message) {
        if (!plugin.isEnabled()) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(minecraftUuid);
            if (player != null && player.isOnline()) player.sendMessage(Component.text(message));
        });
    }

    private void sendMessagesIfOnline(UUID minecraftUuid, List<String> messages) {
        if (!plugin.isEnabled()) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(minecraftUuid);
            if (player == null || !player.isOnline()) return;
            messages.forEach(message -> player.sendMessage(Component.text(message)));
        });
    }

    private void usage(Player player) {
        player.sendMessage(Component.text("Clan: /clan [status] | create <tag> <name> | invite <player> | accept <invite-id> | leave"));
        player.sendMessage(Component.text("      /clan kick <player> | role <player> <member|officer> | transfer <player> [member|officer]"));
        player.sendMessage(Component.text("      /clan treasury [deposit <coins>|withdraw <coins>] | storage ..."));
        storageUsage(player);
    }

    private void storageUsage(Player player) {
        player.sendMessage(Component.text("Storage: /clan storage commodity <id> | items [limit] | deposit <id> <qty> | withdraw <id> <qty>"));
        player.sendMessage(Component.text("         /clan storage deposit-item | withdraw-item <item-id> <version>"));
    }

    private String displayName(String definitionId) {
        return itemCatalog.require(definitionId).displayName();
    }

    private static ClanRole parseManagedRole(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "member" -> ClanRole.MEMBER;
            case "officer" -> ClanRole.OFFICER;
            default -> throw new IllegalArgumentException("Role must be member or officer.");
        };
    }

    private static UUID parseUuid(String raw, String label) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(label + " must be a valid UUID", exception);
        }
    }

    private static long parsePositiveCoin(String raw) {
        try {
            BigDecimal coins = new BigDecimal(raw.trim());
            if (coins.signum() <= 0) throw new IllegalArgumentException("Coin amount must be > 0");
            return coins.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("Coin amount must be positive with at most two decimals", exception);
        }
    }

    private static long parsePositiveLong(String raw, String label) {
        long value = parseNonNegativeLong(raw, label);
        if (value == 0) throw new IllegalArgumentException(label + " must be > 0");
        return value;
    }

    private static long parseNonNegativeLong(String raw, String label) {
        try {
            long value = Long.parseLong(raw.trim());
            if (value < 0) throw new IllegalArgumentException(label + " must be >= 0");
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a whole number", exception);
        }
    }

    private static int parseBoundedLimit(String raw, int maximum) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1 || value > maximum) throw new IllegalArgumentException("limit must be between 1 and " + maximum);
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("limit must be a whole number", exception);
        }
    }

    private static String formatCoin(long amountMinor) {
        long whole = amountMinor / CoinCurrency.MINOR_UNITS_PER_COIN;
        long fraction = amountMinor % CoinCurrency.MINOR_UNITS_PER_COIN;
        return String.format(Locale.ROOT, "%d.%02d Coin", whole, fraction);
    }

    private static String playerMessage(Throwable exception, String fallback) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
        return current;
    }

    private record UniqueClaim(UUID itemInstanceId, long authorityVersion, String definitionId) { }

    @FunctionalInterface
    private interface PlayerPairAction {
        void run(UUID actorMinecraftUuid, UUID targetMinecraftUuid);
    }
}

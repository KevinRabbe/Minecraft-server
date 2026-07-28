package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.economy.BazaarException;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinCurrency;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeAssetRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeCommodityOffer;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeCommodityOfferResult;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeConfirmationRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeException;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeOfferView;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeQueryRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeResolutionRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeResolutionResult;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeSnapshot;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeStatus;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeUniqueItemOfferResult;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeUniqueOffer;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeWithdrawalRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeWithdrawalResult;
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
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.stream.Collectors;

/** Command-first Paper bridge for exact-revision secure direct trades. */
final class PaperTradeCommand implements CommandExecutor, TabCompleter {
    private static final String COIN_REASON = "trade.player_coin";
    private static final String COMMODITY_ADD_REASON = "trade.player_commodity_add";
    private static final String UNIQUE_ADD_REASON = "trade.player_unique_add";
    private static final String COMMODITY_WITHDRAW_REASON = "trade.player_commodity_withdraw";
    private static final String UNIQUE_WITHDRAW_REASON = "trade.player_unique_withdraw";
    private static final String CANCEL_REASON = "trade.player_cancel";
    private static final String SETTLE_REASON = "trade.player_settle";

    private final MinecraftServerPlugin plugin;
    private final PaperSessionController sessions;
    private final PaperPlayerIdentityResolver playerIdentities;
    private final PaperCommodityDeliveryController commodityDeliveries;
    private final PaperUniqueDeliveryController uniqueDeliveries;
    private final SecureTradeRepository trades;
    private final SecureTradeAssetRepository assets;
    private final SecureTradeWithdrawalRepository withdrawals;
    private final SecureTradeConfirmationRepository confirmations;
    private final SecureTradeResolutionRepository resolutions;
    private final SecureTradeQueryRepository queries;
    private final ItemCatalog itemCatalog;
    private final PaperCommodityStateMutator commodityMutator;
    private final PaperUniqueItemStateRemovalMutator uniqueItemRemoval;
    private final PaperItemIdentityCodec identityCodec;

    PaperTradeCommand(
            MinecraftServerPlugin plugin,
            PaperSessionController sessions,
            PaperPlayerIdentityResolver playerIdentities,
            PaperCommodityDeliveryController commodityDeliveries,
            PaperUniqueDeliveryController uniqueDeliveries,
            SecureTradeRepository trades,
            SecureTradeAssetRepository assets,
            SecureTradeWithdrawalRepository withdrawals,
            SecureTradeConfirmationRepository confirmations,
            SecureTradeResolutionRepository resolutions,
            SecureTradeQueryRepository queries,
            ItemCatalog itemCatalog,
            PaperCommodityStateMutator commodityMutator,
            PaperUniqueItemStateRemovalMutator uniqueItemRemoval
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.playerIdentities = Objects.requireNonNull(playerIdentities, "playerIdentities");
        this.commodityDeliveries = Objects.requireNonNull(commodityDeliveries, "commodityDeliveries");
        this.uniqueDeliveries = Objects.requireNonNull(uniqueDeliveries, "uniqueDeliveries");
        this.trades = Objects.requireNonNull(trades, "trades");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.withdrawals = Objects.requireNonNull(withdrawals, "withdrawals");
        this.confirmations = Objects.requireNonNull(confirmations, "confirmations");
        this.resolutions = Objects.requireNonNull(resolutions, "resolutions");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.commodityMutator = Objects.requireNonNull(commodityMutator, "commodityMutator");
        this.uniqueItemRemoval = Objects.requireNonNull(uniqueItemRemoval, "uniqueItemRemoval");
        this.identityCodec = new PaperItemIdentityCodec(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can use secure direct trade."));
            return true;
        }
        if (args.length == 0) {
            usage(player);
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (action) {
                case "start" -> {
                    if (args.length != 2) {
                        usage(player);
                    } else {
                        startTrade(player, args[1]);
                    }
                }
                case "view" -> {
                    if (args.length != 2) {
                        usage(player);
                    } else {
                        scheduleView(player.getUniqueId(), parseUuid(args[1], "trade ID"));
                    }
                }
                case "coin" -> {
                    if (args.length != 3) {
                        usage(player);
                    } else {
                        scheduleCoinOffer(
                                player.getUniqueId(),
                                parseUuid(args[1], "trade ID"),
                                parseCoinAllowZero(args[2])
                        );
                    }
                }
                case "commodity" -> {
                    if (args.length != 4) {
                        usage(player);
                    } else {
                        addCommodityOffer(
                                player,
                                parseUuid(args[1], "trade ID"),
                                requireCommodityDefinition(args[2]).definitionId(),
                                parsePositiveLong(args[3], "quantity")
                        );
                    }
                }
                case "item" -> {
                    if (args.length != 2) {
                        usage(player);
                    } else {
                        addUniqueOffer(player, parseUuid(args[1], "trade ID"));
                    }
                }
                case "take-commodity" -> {
                    if (args.length != 4) {
                        usage(player);
                    } else {
                        scheduleCommodityWithdrawal(
                                player.getUniqueId(),
                                parseUuid(args[1], "trade ID"),
                                requireCommodityDefinition(args[2]).definitionId(),
                                parsePositiveLong(args[3], "quantity")
                        );
                    }
                }
                case "take-item" -> {
                    if (args.length != 4) {
                        usage(player);
                    } else {
                        scheduleUniqueWithdrawal(
                                player.getUniqueId(),
                                parseUuid(args[1], "trade ID"),
                                parseUuid(args[2], "item instance ID"),
                                parseNonNegativeLong(args[3], "escrow item version")
                        );
                    }
                }
                case "confirm" -> {
                    if (args.length != 3) {
                        usage(player);
                    } else {
                        scheduleConfirmation(
                                player.getUniqueId(),
                                parseUuid(args[1], "trade ID"),
                                parseNonNegativeLong(args[2], "revision")
                        );
                    }
                }
                case "cancel" -> {
                    if (args.length != 2) {
                        usage(player);
                    } else {
                        scheduleCancel(player.getUniqueId(), parseUuid(args[1], "trade ID"));
                    }
                }
                default -> usage(player);
            }
        } catch (IllegalArgumentException exception) {
            player.sendMessage(Component.text(playerMessage(exception, "Invalid trade command arguments.")));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of(
                            "start", "view", "coin", "commodity", "item",
                            "take-commodity", "take-item", "confirm", "cancel"
                    ).stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
        if (args.length == 3
                && (args[0].equalsIgnoreCase("commodity") || args[0].equalsIgnoreCase("take-commodity"))) {
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

    private void startTrade(Player player, String targetName) {
        Player target = plugin.getServer().getPlayerExact(targetName);
        if (target == null || !target.isOnline()) {
            player.sendMessage(Component.text("That player must be online to start a direct trade."));
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("You cannot trade with yourself."));
            return;
        }

        UUID actorMinecraftUuid = player.getUniqueId();
        UUID targetMinecraftUuid = target.getUniqueId();
        String actorName = player.getName();
        String liveTargetName = target.getName();
        runAsync(() -> {
            try {
                UUID actorPlayerId = requirePlayerId(actorMinecraftUuid);
                UUID targetPlayerId = requirePlayerId(targetMinecraftUuid);
                SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), actorPlayerId, targetPlayerId);
                sendIfOnline(
                        actorMinecraftUuid,
                        "Opened secure trade " + trade.tradeId() + " with " + liveTargetName
                                + ". Use /trade view " + trade.tradeId()
                );
                sendIfOnline(
                        targetMinecraftUuid,
                        actorName + " opened secure trade " + trade.tradeId()
                                + ". Nothing moves until you explicitly offer/confirm. Use /trade view " + trade.tradeId()
                );
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(actorMinecraftUuid, "Could not start secure trade.", exception);
            }
        });
    }

    private void scheduleView(UUID minecraftUuid, UUID tradeId) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                SecureTradeOfferView view = queries.loadOffer(tradeId);
                if (!view.trade().participant(playerId)) {
                    throw new SecureTradeException("You are not a participant in that secure trade.");
                }
                sendMessagesIfOnline(minecraftUuid, formatView(view, playerId));
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(minecraftUuid, "Could not load secure trade.", exception);
            }
        });
    }

    private void scheduleCoinOffer(UUID minecraftUuid, UUID tradeId, long amountMinor) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                var result = trades.setCoinOffer(
                        UUID.randomUUID(), tradeId, playerId, amountMinor, COIN_REASON
                );
                sendIfOnline(
                        minecraftUuid,
                        "Coin offer is now " + formatCoin(result.escrowAmountMinor())
                                + ". Trade revision is " + result.trade().revision() + "; confirmations were reset."
                );
                notifyOtherParticipant(
                        result.trade(),
                        playerId,
                        "Secure trade " + tradeId + " changed to revision " + result.trade().revision()
                                + ". Use /trade view " + tradeId
                );
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(minecraftUuid, "Could not change Coin offer.", exception);
            }
        });
    }

    private void addCommodityOffer(Player player, UUID tradeId, String definitionId, long quantity) {
        UUID minecraftUuid = player.getUniqueId();
        if (sessions.isMutationFrozen(minecraftUuid)) {
            player.sendMessage(Component.text("Your persistent state is busy. Try again shortly."));
            return;
        }

        AtomicReference<SecureTradeCommodityOfferResult> committed = new AtomicReference<>();
        sessions.mutateAuthoritativeState(player, context -> {
            byte[] nextPayload = commodityMutator.remove(
                    context.playerId(), definitionId, quantity, context.currentStatePayload()
            );
            SecureTradeCommodityOfferResult result = assets.addCommodity(
                    UUID.randomUUID(),
                    tradeId,
                    context.sessionId(),
                    context.backendId(),
                    context.stateVersion(),
                    definitionId,
                    quantity,
                    context.logicalZoneId(),
                    context.entryPoint(),
                    nextPayload,
                    COMMODITY_ADD_REASON
            );
            committed.set(result);
            return new PaperAuthoritativeStateMutation.Result(result.playerStateVersion(), nextPayload);
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                handleMutationFailure(minecraftUuid, "Could not add commodity to secure trade.", failure);
                return;
            }
            SecureTradeCommodityOfferResult result = committed.get();
            if (result == null) {
                plugin.getLogger().severe("Secure-trade commodity add committed without captured result");
                return;
            }
            sendIfOnline(
                    minecraftUuid,
                    "Added " + quantity + " " + displayName(definitionId) + " to trade " + tradeId
                            + ". Revision is now " + result.trade().revision() + "; confirmations were reset."
            );
            runAsync(() -> notifyOtherParticipant(
                    result.trade(),
                    result.playerId(),
                    "Secure trade " + tradeId + " changed to revision " + result.trade().revision()
                            + ". Use /trade view " + tradeId
            ));
        });
    }

    private void addUniqueOffer(Player player, UUID tradeId) {
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

        AtomicReference<SecureTradeUniqueItemOfferResult> committed = new AtomicReference<>();
        sessions.mutateAuthoritativeState(player, context -> {
            byte[] nextPayload = uniqueItemRemoval.remove(
                    context.playerId(),
                    claim.itemInstanceId(),
                    claim.authorityVersion(),
                    context.currentStatePayload()
            );
            SecureTradeUniqueItemOfferResult result = assets.addUniqueItem(
                    UUID.randomUUID(),
                    tradeId,
                    context.sessionId(),
                    context.backendId(),
                    context.stateVersion(),
                    claim.itemInstanceId(),
                    claim.authorityVersion(),
                    context.logicalZoneId(),
                    context.entryPoint(),
                    nextPayload,
                    UNIQUE_ADD_REASON
            );
            committed.set(result);
            return new PaperAuthoritativeStateMutation.Result(result.playerStateVersion(), nextPayload);
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                handleMutationFailure(minecraftUuid, "Could not add unique item to secure trade.", failure);
                return;
            }
            SecureTradeUniqueItemOfferResult result = committed.get();
            if (result == null) {
                plugin.getLogger().severe("Secure-trade unique add committed without captured result");
                return;
            }
            sendIfOnline(
                    minecraftUuid,
                    "Added " + displayName(claim.definitionId()) + " to trade " + tradeId
                            + " as " + result.itemInstanceId() + " @v" + result.escrowItemVersion()
                            + ". Revision is now " + result.trade().revision() + "; confirmations were reset."
            );
            runAsync(() -> notifyOtherParticipant(
                    result.trade(),
                    result.playerId(),
                    "Secure trade " + tradeId + " changed to revision " + result.trade().revision()
                            + ". Use /trade view " + tradeId
            ));
        });
    }

    private void scheduleCommodityWithdrawal(
            UUID minecraftUuid,
            UUID tradeId,
            String definitionId,
            long quantity
    ) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                SecureTradeWithdrawalResult result = withdrawals.withdrawCommodity(
                        UUID.randomUUID(),
                        tradeId,
                        playerId,
                        definitionId,
                        quantity,
                        COMMODITY_WITHDRAW_REASON
                );
                commodityDeliveries.requestDrain(minecraftUuid);
                sendIfOnline(
                        minecraftUuid,
                        "Removed " + quantity + " " + displayName(definitionId) + " from trade " + tradeId
                                + ". Return delivery is secured. Revision is now " + result.trade().revision() + "."
                );
                notifyOtherParticipant(
                        result.trade(),
                        playerId,
                        "Secure trade " + tradeId + " changed to revision " + result.trade().revision()
                                + ". Use /trade view " + tradeId
                );
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(minecraftUuid, "Could not withdraw commodity offer.", exception);
            }
        });
    }

    private void scheduleUniqueWithdrawal(
            UUID minecraftUuid,
            UUID tradeId,
            UUID itemInstanceId,
            long escrowItemVersion
    ) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                SecureTradeWithdrawalResult result = withdrawals.withdrawUniqueItem(
                        UUID.randomUUID(),
                        tradeId,
                        playerId,
                        itemInstanceId,
                        escrowItemVersion,
                        UNIQUE_WITHDRAW_REASON
                );
                uniqueDeliveries.requestDrain(minecraftUuid);
                sendIfOnline(
                        minecraftUuid,
                        "Removed unique item " + itemInstanceId + " from trade " + tradeId
                                + ". Return delivery is secured. Revision is now " + result.trade().revision() + "."
                );
                notifyOtherParticipant(
                        result.trade(),
                        playerId,
                        "Secure trade " + tradeId + " changed to revision " + result.trade().revision()
                                + ". Use /trade view " + tradeId
                );
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(minecraftUuid, "Could not withdraw unique-item offer.", exception);
            }
        });
    }

    private void scheduleConfirmation(UUID minecraftUuid, UUID tradeId, long viewedRevision) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                SecureTradeSnapshot confirmed = confirmations.confirmViewedRevision(
                        tradeId, playerId, viewedRevision
                );
                if (confirmed.status() == SecureTradeStatus.OPEN) {
                    sendIfOnline(
                            minecraftUuid,
                            "Confirmed secure trade " + tradeId + " revision " + viewedRevision
                                    + ". The other player must confirm this same revision."
                    );
                    notifyOtherParticipant(
                            confirmed,
                            playerId,
                            "The other player confirmed secure trade " + tradeId + " revision " + viewedRevision
                                    + ". Review it with /trade view " + tradeId
                    );
                    return;
                }
                if (confirmed.status() != SecureTradeStatus.LOCKED) {
                    throw new SecureTradeException("secure trade did not reach a settleable state");
                }
                settleLockedTrade(minecraftUuid, confirmed);
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(minecraftUuid, "Could not confirm secure trade.", exception);
            }
        });
    }

    private void settleLockedTrade(UUID callerMinecraftUuid, SecureTradeSnapshot locked) throws SQLException {
        UUID operationId = settlementOperationId(locked.tradeId(), locked.revision());
        SecureTradeResolutionResult settled;
        try {
            settled = resolutions.settle(operationId, locked.tradeId(), SETTLE_REASON);
        } catch (SecureTradeException exception) {
            SecureTradeSnapshot current = trades.load(locked.tradeId());
            if (current.status() != SecureTradeStatus.SETTLED) {
                throw exception;
            }
            wakeParticipantDeliveries(current);
            notifyParticipants(current, "Secure trade " + current.tradeId() + " is settled.");
            return;
        }

        wakeParticipantDeliveries(settled.trade());
        notifyParticipants(
                settled.trade(),
                "Secure trade " + settled.trade().tradeId() + " revision " + settled.trade().revision()
                        + " settled exactly. Pending items/materials are secured for delivery."
        );
        sendIfOnline(callerMinecraftUuid, "Both confirmations matched; settlement completed.");
    }

    private void scheduleCancel(UUID minecraftUuid, UUID tradeId) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                SecureTradeResolutionResult cancelled = resolutions.cancel(
                        UUID.randomUUID(), tradeId, playerId, CANCEL_REASON
                );
                wakeParticipantDeliveries(cancelled.trade());
                notifyParticipants(
                        cancelled.trade(),
                        "Secure trade " + tradeId + " was cancelled. Escrowed assets are secured for return delivery."
                );
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(minecraftUuid, "Could not cancel secure trade.", exception);
            }
        });
    }

    private List<String> formatView(SecureTradeOfferView view, UUID playerId) {
        SecureTradeSnapshot trade = view.trade();
        UUID otherPlayerId = trade.otherParticipant(playerId);
        ArrayList<String> lines = new ArrayList<>();
        lines.add("Secure trade " + trade.tradeId() + " — " + trade.status() + " — revision " + trade.revision());
        lines.add(formatSide("You", view, playerId));
        lines.add(formatSide("Other", view, otherPlayerId));

        boolean playerA = trade.playerAId().equals(playerId);
        Long ownConfirmed = playerA ? trade.playerAConfirmedRevision() : trade.playerBConfirmedRevision();
        Long otherConfirmed = playerA ? trade.playerBConfirmedRevision() : trade.playerAConfirmedRevision();
        lines.add(
                "Confirmed on revision " + trade.revision() + ": you=" + confirmationLabel(ownConfirmed, trade.revision())
                        + ", other=" + confirmationLabel(otherConfirmed, trade.revision())
        );
        if (trade.status() == SecureTradeStatus.OPEN) {
            lines.add("Confirm exactly this snapshot: /trade confirm " + trade.tradeId() + " " + trade.revision());
            lines.add("Any offer change or withdrawal advances the revision and invalidates confirmations.");
        } else if (trade.status() == SecureTradeStatus.LOCKED) {
            lines.add(
                    "Both players confirmed this revision. /trade confirm " + trade.tradeId() + " " + trade.revision()
                            + " safely retries settlement if needed."
            );
        }
        return List.copyOf(lines);
    }

    private String formatSide(String label, SecureTradeOfferView view, UUID ownerPlayerId) {
        ArrayList<String> assets = new ArrayList<>();
        long coins = view.coinOffersMinor().getOrDefault(ownerPlayerId, 0L);
        if (coins > 0) {
            assets.add(formatCoin(coins));
        }
        view.commodityOffers().stream()
                .filter(offer -> offer.ownerPlayerId().equals(ownerPlayerId))
                .sorted(Comparator.comparing(SecureTradeCommodityOffer::commodityDefinitionId))
                .forEach(offer -> assets.add(offer.quantity() + " " + displayName(offer.commodityDefinitionId())));
        view.uniqueOffers().stream()
                .filter(offer -> offer.ownerPlayerId().equals(ownerPlayerId))
                .sorted(Comparator.comparing(SecureTradeUniqueOffer::itemInstanceId))
                .forEach(offer -> assets.add(formatUniqueOffer(offer)));
        return label + ": " + (assets.isEmpty() ? "nothing" : String.join(" + ", assets));
    }

    private String formatUniqueOffer(SecureTradeUniqueOffer offer) {
        String rolls = offer.rollQualityBasisPoints().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + " " + formatPercent(entry.getValue()))
                .collect(Collectors.joining(", "));
        return displayName(offer.definitionId())
                + (rolls.isEmpty() ? "" : " [" + rolls + "]")
                + " {" + offer.itemInstanceId() + " @v" + offer.escrowItemVersion() + "}";
    }

    private UniqueClaim requireUniqueClaim(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("Hold one unique item in your main hand to offer it.");
        }
        Optional<ItemRepresentationClaim> optional = identityCodec.readClaim(stack, "main_hand");
        if (optional.isEmpty()) {
            throw new IllegalArgumentException("The main-hand item is not a managed server item.");
        }
        ItemRepresentationClaim claim = optional.orElseThrow();
        if (!claim.individualClaim() || claim.itemInstanceId() == null || claim.authorityVersion() == null
                || claim.amount() != 1) {
            throw new IllegalArgumentException("Only individualized one-of-one items can be offered directly.");
        }
        ItemDefinition definition = itemCatalog.find(claim.definitionId()).orElseThrow(
                () -> new PaperItemRepresentationException("The main-hand item has an unknown definition.")
        );
        if (definition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
            throw new IllegalArgumentException("Only individualized items can be offered directly.");
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

    private UUID requirePlayerId(UUID minecraftUuid) throws SQLException {
        return playerIdentities.resolve(minecraftUuid).orElseThrow(
                () -> new SecureTradeException("Persistent player identity is not available.")
        );
    }

    private void wakeParticipantDeliveries(SecureTradeSnapshot trade) throws SQLException {
        wakeDeliveries(trade.playerAId());
        wakeDeliveries(trade.playerBId());
    }

    private void wakeDeliveries(UUID playerId) throws SQLException {
        Optional<UUID> minecraftUuid = playerIdentities.resolveMinecraftUuid(playerId);
        if (minecraftUuid.isEmpty()) {
            return;
        }
        UUID value = minecraftUuid.orElseThrow();
        commodityDeliveries.requestDrain(value);
        uniqueDeliveries.requestDrain(value);
    }

    private void notifyOtherParticipant(SecureTradeSnapshot trade, UUID actorPlayerId, String message) {
        try {
            sendToPlayerId(trade.otherParticipant(actorPlayerId), message);
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not notify other secure-trade participant", exception);
        }
    }

    private void notifyParticipants(SecureTradeSnapshot trade, String message) throws SQLException {
        sendToPlayerId(trade.playerAId(), message);
        sendToPlayerId(trade.playerBId(), message);
    }

    private void sendToPlayerId(UUID playerId, String message) throws SQLException {
        Optional<UUID> minecraftUuid = playerIdentities.resolveMinecraftUuid(playerId);
        minecraftUuid.ifPresent(value -> sendIfOnline(value, message));
    }

    private void handleMutationFailure(UUID minecraftUuid, String fallback, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof SessionConflictException) {
            sendIfOnline(minecraftUuid, "Your persistent state changed. Review the trade and try again.");
            return;
        }
        if (cause instanceof SecureTradeException
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
        if (cause instanceof SecureTradeException || cause instanceof IllegalArgumentException) {
            sendIfOnline(minecraftUuid, playerMessage(cause, fallback));
            return;
        }
        plugin.getLogger().log(Level.WARNING, fallback, cause);
        sendIfOnline(minecraftUuid, fallback);
    }

    private void runAsync(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule secure-trade work", exception);
        }
    }

    private void runOnMainThread(Runnable task) {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    private void sendIfOnline(UUID minecraftUuid, String message) {
        runOnMainThread(() -> {
            Player player = plugin.getServer().getPlayer(minecraftUuid);
            if (player != null && player.isOnline()) {
                player.sendMessage(Component.text(message));
            }
        });
    }

    private void sendMessagesIfOnline(UUID minecraftUuid, List<String> messages) {
        runOnMainThread(() -> {
            Player player = plugin.getServer().getPlayer(minecraftUuid);
            if (player == null || !player.isOnline()) {
                return;
            }
            messages.forEach(message -> player.sendMessage(Component.text(message)));
        });
    }

    private void usage(Player player) {
        player.sendMessage(Component.text(
                "Trade: /trade start <player> | view <id> | coin <id> <coins> | commodity <id> <commodity> <qty>"
        ));
        player.sendMessage(Component.text(
                "       /trade item <id> | take-commodity <id> <commodity> <qty> | take-item <id> <item-id> <version>"
        ));
        player.sendMessage(Component.text("       /trade confirm <id> <viewed-revision> | cancel <id>"));
    }

    private String displayName(String definitionId) {
        return itemCatalog.require(definitionId).displayName();
    }

    private static UUID parseUuid(String raw, String label) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(label + " must be a valid UUID", exception);
        }
    }

    private static long parseCoinAllowZero(String raw) {
        try {
            BigDecimal coins = new BigDecimal(raw.trim());
            if (coins.signum() < 0) {
                throw new IllegalArgumentException("Coin offer must be >= 0");
            }
            return coins.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("Coin offer must have at most two decimals", exception);
        }
    }

    private static long parsePositiveLong(String raw, String label) {
        long value = parseNonNegativeLong(raw, label);
        if (value == 0) {
            throw new IllegalArgumentException(label + " must be > 0");
        }
        return value;
    }

    private static long parseNonNegativeLong(String raw, String label) {
        try {
            long value = Long.parseLong(raw.trim());
            if (value < 0) {
                throw new IllegalArgumentException(label + " must be >= 0");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a whole number", exception);
        }
    }

    private static String formatCoin(long amountMinor) {
        long whole = amountMinor / CoinCurrency.MINOR_UNITS_PER_COIN;
        long fraction = amountMinor % CoinCurrency.MINOR_UNITS_PER_COIN;
        return String.format(Locale.ROOT, "%d.%02d Coin", whole, fraction);
    }

    private static String formatPercent(int basisPoints) {
        return String.format(Locale.ROOT, "%d.%02d%%", basisPoints / 100, basisPoints % 100);
    }

    private static String confirmationLabel(Long confirmedRevision, long currentRevision) {
        return Long.valueOf(currentRevision).equals(confirmedRevision) ? "yes" : "no";
    }

    private static UUID settlementOperationId(UUID tradeId, long revision) {
        return UUID.nameUUIDFromBytes(
                ("paper.secure_trade.settle|" + tradeId + "|" + revision).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String playerMessage(Throwable exception, String fallback) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record UniqueClaim(UUID itemInstanceId, long authorityVersion, String definitionId) { }
}

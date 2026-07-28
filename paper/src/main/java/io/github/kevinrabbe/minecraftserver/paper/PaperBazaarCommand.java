package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.economy.BazaarBuyOrderCreateResult;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarCancelResult;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarException;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarMatchResult;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarOrderRequest;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarOrderSide;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarOrderSnapshot;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarPolicy;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarSellOrderCreateResult;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinCurrency;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.session.SessionConflictException;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/** Minimal live Bazaar surface over the proven price-time order-book authority. */
final class PaperBazaarCommand implements CommandExecutor, TabCompleter {
    private static final String BUY_REASON = "bazaar.player_buy";
    private static final String SELL_REASON = "bazaar.player_sell";
    private static final String MATCH_REASON = "bazaar.player_match";
    private static final String CANCEL_REASON = "bazaar.player_cancel";

    private final JavaPlugin plugin;
    private final PaperSessionController sessions;
    private final PaperPlayerIdentityResolver playerIdentities;
    private final PaperCommodityStateMutator commodities;
    private final PaperCommodityDeliveryController deliveries;
    private final BazaarRepository bazaar;
    private final BazaarPolicy policy;
    private final List<String> commodityIds;

    PaperBazaarCommand(
            JavaPlugin plugin,
            PaperSessionController sessions,
            PaperPlayerIdentityResolver playerIdentities,
            PaperCommodityStateMutator commodities,
            PaperCommodityDeliveryController deliveries,
            BazaarRepository bazaar,
            BazaarPolicy policy,
            ItemCatalog itemCatalog
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.playerIdentities = Objects.requireNonNull(playerIdentities, "playerIdentities");
        this.commodities = Objects.requireNonNull(commodities, "commodities");
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
        this.bazaar = Objects.requireNonNull(bazaar, "bazaar");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.commodityIds = Objects.requireNonNull(itemCatalog, "itemCatalog").definitions().stream()
                .filter(definition -> definition.identityKind() == ItemIdentityKind.COMMODITY)
                .map(ItemDefinition::definitionId)
                .sorted()
                .toList();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can use the Bazaar."));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(Component.text(
                    "Bazaar commodities: " + String.join(", ", commodityIds)
                            + " — execution fee " + formatBasisPoints(policy.executionFeeBasisPoints())
            ));
            player.sendMessage(Component.text(
                    "Usage: /bazaar buy <commodity> <quantity> <coin-per-unit> | sell ... | cancel <order-id>"
            ));
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("cancel") && args.length == 2) {
            final UUID orderId;
            try {
                orderId = UUID.fromString(args[1]);
            } catch (IllegalArgumentException exception) {
                player.sendMessage(Component.text("Bazaar order ID must be a valid UUID."));
                return true;
            }
            scheduleCancel(player.getUniqueId(), orderId);
            return true;
        }
        if ((action.equals("buy") || action.equals("sell")) && args.length == 4) {
            final long quantity;
            final long priceMinor;
            try {
                quantity = parseQuantity(args[2]);
                priceMinor = parseCoin(args[3]);
            } catch (IllegalArgumentException exception) {
                player.sendMessage(Component.text(
                        "Quantity must be a positive whole number and price a positive Coin value with at most two decimals."
                ));
                return true;
            }
            String commodity = args[1].trim().toLowerCase(Locale.ROOT);
            if (!commodityIds.contains(commodity)) {
                player.sendMessage(Component.text("Unknown Bazaar commodity: " + commodity));
                return true;
            }
            BazaarOrderRequest request;
            try {
                request = new BazaarOrderRequest(
                        commodity,
                        action.equals("buy") ? BazaarOrderSide.BUY : BazaarOrderSide.SELL,
                        quantity,
                        priceMinor
                );
            } catch (IllegalArgumentException | ArithmeticException exception) {
                player.sendMessage(Component.text("Bazaar order quantity/price is too large."));
                return true;
            }
            if (request.side() == BazaarOrderSide.BUY) {
                scheduleBuy(player.getUniqueId(), request);
            } else {
                createSell(player, request);
            }
            return true;
        }

        player.sendMessage(Component.text(
                "Usage: /bazaar buy <commodity> <quantity> <coin-per-unit> | "
                        + "sell <commodity> <quantity> <coin-per-unit> | cancel <order-id>"
        ));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("buy", "sell", "cancel").stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("buy") || args[0].equalsIgnoreCase("sell"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return commodityIds.stream().filter(value -> value.startsWith(prefix)).toList();
        }
        return List.of();
    }

    private void scheduleBuy(UUID minecraftUuid, BazaarOrderRequest request) {
        runAsync(() -> {
            try {
                Optional<UUID> playerId = playerIdentities.resolve(minecraftUuid);
                if (playerId.isEmpty()) {
                    sendIfOnline(minecraftUuid, "Bazaar could not resolve your persistent player identity.");
                    return;
                }
                BazaarBuyOrderCreateResult created = bazaar.createBuyOrder(
                        UUID.randomUUID(),
                        playerId.orElseThrow(),
                        request,
                        BUY_REASON
                );
                BazaarMatchResult matched = match(request.commodityDefinitionId());
                reportOrder(minecraftUuid, created.orderId(), matched);
            } catch (BazaarException exception) {
                sendIfOnline(minecraftUuid, playerMessage(exception, "Bazaar rejected that buy order."));
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.WARNING, "Bazaar buy persistence failed", exception);
                sendIfOnline(minecraftUuid, "Bazaar is temporarily unavailable.");
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Bazaar buy failed closed", exception);
                sendIfOnline(minecraftUuid, "Bazaar is temporarily unavailable.");
            }
        });
    }

    private void createSell(Player player, BazaarOrderRequest request) {
        UUID minecraftUuid = player.getUniqueId();
        if (sessions.isMutationFrozen(minecraftUuid)) {
            player.sendMessage(Component.text("Your persistent state is busy. Try again shortly."));
            return;
        }

        AtomicReference<BazaarSellOrderCreateResult> committed = new AtomicReference<>();
        UUID operationId = UUID.randomUUID();
        sessions.mutateAuthoritativeState(player, context -> {
            byte[] nextPayload = commodities.remove(
                    context.playerId(),
                    request.commodityDefinitionId(),
                    request.quantity(),
                    context.currentStatePayload()
            );
            BazaarSellOrderCreateResult created = bazaar.createSellOrder(
                    operationId,
                    context.sessionId(),
                    context.backendId(),
                    context.stateVersion(),
                    request,
                    context.logicalZoneId(),
                    context.entryPoint(),
                    nextPayload,
                    SELL_REASON
            );
            committed.set(created);
            return new PaperAuthoritativeStateMutation.Result(
                    created.playerStateVersion(),
                    nextPayload
            );
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                Throwable cause = unwrap(failure);
                if (cause instanceof SessionConflictException) {
                    sendIfOnline(minecraftUuid, "Your persistent state changed. Try the sell order again.");
                } else if (cause instanceof BazaarException bazaarFailure) {
                    sendIfOnline(minecraftUuid, playerMessage(bazaarFailure, "Bazaar rejected that sell order."));
                } else {
                    plugin.getLogger().log(Level.WARNING, "Bazaar sell failed closed", cause);
                    sendIfOnline(minecraftUuid, "Bazaar is temporarily unavailable.");
                }
                return;
            }
            BazaarSellOrderCreateResult created = committed.get();
            if (created == null) {
                plugin.getLogger().severe("Bazaar sell committed without a captured order result");
                return;
            }
            runAsync(() -> {
                try {
                    BazaarMatchResult matched = match(request.commodityDefinitionId());
                    reportOrder(minecraftUuid, created.orderId(), matched);
                } catch (SQLException | RuntimeException exception) {
                    plugin.getLogger().log(
                            Level.WARNING,
                            "Bazaar sell order committed but immediate matching failed; order remains durable",
                            exception
                    );
                    sendIfOnline(
                            minecraftUuid,
                            "Sell order created: " + created.orderId() + ". Matching will recover on later Bazaar activity."
                    );
                }
            });
        });
    }

    private void scheduleCancel(UUID minecraftUuid, UUID orderId) {
        runAsync(() -> {
            try {
                Optional<UUID> playerId = playerIdentities.resolve(minecraftUuid);
                if (playerId.isEmpty()) {
                    sendIfOnline(minecraftUuid, "Bazaar could not resolve your persistent player identity.");
                    return;
                }
                BazaarCancelResult cancelled = bazaar.cancelOrder(
                        UUID.randomUUID(),
                        orderId,
                        playerId.orElseThrow(),
                        CANCEL_REASON
                );
                if (cancelled.returnedCommodityQuantity() > 0) {
                    deliveries.requestDrain(minecraftUuid);
                }
                sendIfOnline(
                        minecraftUuid,
                        "Cancelled Bazaar order " + orderId
                                + ". Returned " + formatCoin(cancelled.returnedMoneyMinor())
                                + " and " + cancelled.returnedCommodityQuantity() + " commodity units."
                );
            } catch (BazaarException exception) {
                sendIfOnline(minecraftUuid, playerMessage(exception, "Bazaar rejected that cancellation."));
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.WARNING, "Bazaar cancellation persistence failed", exception);
                sendIfOnline(minecraftUuid, "Bazaar is temporarily unavailable.");
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Bazaar cancellation failed closed", exception);
                sendIfOnline(minecraftUuid, "Bazaar is temporarily unavailable.");
            }
        });
    }

    private BazaarMatchResult match(String commodityDefinitionId) throws SQLException {
        return bazaar.matchCommodity(
                UUID.randomUUID(),
                commodityDefinitionId,
                policy.maxFillsPerMatch(),
                MATCH_REASON
        );
    }

    private void reportOrder(UUID minecraftUuid, UUID orderId, BazaarMatchResult matched) throws SQLException {
        BazaarOrderSnapshot current = bazaar.loadOrder(orderId);
        requestOnlineCommodityDrains();
        sendIfOnline(
                minecraftUuid,
                "Bazaar order " + orderId
                        + " — " + current.status()
                        + " — remaining " + current.remainingQuantity()
                        + " — limit " + formatCoin(current.limitPriceMinor()) + "/unit"
                        + (matched.fills() == 0
                        ? ""
                        : " — matching pass filled " + matched.quantityFilled() + " units in " + matched.fills() + " fills")
        );
    }

    private void requestOnlineCommodityDrains() {
        runOnMainThread(() -> plugin.getServer().getOnlinePlayers().forEach(
                player -> deliveries.requestDrain(player.getUniqueId())
        ));
    }

    private void runAsync(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule Bazaar work", exception);
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

    private static long parseQuantity(String raw) {
        try {
            long value = Long.parseLong(raw.trim());
            if (value <= 0) {
                throw new IllegalArgumentException("quantity must be positive");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid quantity", exception);
        }
    }

    private static long parseCoin(String raw) {
        try {
            BigDecimal coins = new BigDecimal(raw.trim());
            if (coins.signum() <= 0) {
                throw new IllegalArgumentException("amount must be positive");
            }
            BigDecimal minor = coins.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2);
            long value = minor.longValueExact();
            if (value <= 0) {
                throw new IllegalArgumentException("amount must be positive");
            }
            return value;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("invalid Coin amount", exception);
        }
    }

    private static String formatCoin(long amountMinor) {
        long whole = amountMinor / CoinCurrency.MINOR_UNITS_PER_COIN;
        long fraction = amountMinor % CoinCurrency.MINOR_UNITS_PER_COIN;
        return String.format(Locale.ROOT, "%d.%02d Coin", whole, fraction);
    }

    private static String formatBasisPoints(int basisPoints) {
        return String.format(Locale.ROOT, "%d.%02d%%", basisPoints / 100, basisPoints % 100);
    }

    private static String playerMessage(RuntimeException exception, String fallback) {
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
}

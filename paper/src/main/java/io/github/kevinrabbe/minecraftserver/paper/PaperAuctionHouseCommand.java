package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.economy.AuctionBrowseListing;
import io.github.kevinrabbe.minecraftserver.common.economy.AuctionCancelResult;
import io.github.kevinrabbe.minecraftserver.common.economy.AuctionHouseException;
import io.github.kevinrabbe.minecraftserver.common.economy.AuctionHouseQueryRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.AuctionHouseRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.AuctionListingCreateResult;
import io.github.kevinrabbe.minecraftserver.common.economy.AuctionPurchaseResult;
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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/** Minimal player-facing fixed-price Auction House bridge for individualized items. */
final class PaperAuctionHouseCommand implements CommandExecutor, TabCompleter {
    private static final String LIST_REASON = "auction.player_list";
    private static final String BUY_REASON = "auction.player_buy";
    private static final String CANCEL_REASON = "auction.player_cancel";
    private static final int DEFAULT_BROWSE_LIMIT = 10;
    private static final int MAX_PLAYER_BROWSE_LIMIT = 25;

    private final MinecraftServerPlugin plugin;
    private final PaperSessionController sessions;
    private final PaperPlayerIdentityResolver playerIdentities;
    private final PaperUniqueDeliveryController uniqueDeliveries;
    private final AuctionHouseRepository auctions;
    private final AuctionHouseQueryRepository queries;
    private final ItemCatalog itemCatalog;
    private final PaperItemIdentityCodec identityCodec;
    private final PaperUniqueItemStateRemovalMutator uniqueItemRemoval;

    PaperAuctionHouseCommand(
            MinecraftServerPlugin plugin,
            PaperSessionController sessions,
            PaperPlayerIdentityResolver playerIdentities,
            PaperUniqueDeliveryController uniqueDeliveries,
            AuctionHouseRepository auctions,
            AuctionHouseQueryRepository queries,
            ItemCatalog itemCatalog
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.playerIdentities = Objects.requireNonNull(playerIdentities, "playerIdentities");
        this.uniqueDeliveries = Objects.requireNonNull(uniqueDeliveries, "uniqueDeliveries");
        this.auctions = Objects.requireNonNull(auctions, "auctions");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.identityCodec = new PaperItemIdentityCodec(plugin);
        this.uniqueItemRemoval = new PaperUniqueItemStateRemovalMutator(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can use the Auction House."));
            return true;
        }
        if (args.length == 0) {
            scheduleBrowse(player.getUniqueId(), DEFAULT_BROWSE_LIMIT);
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("browse") && args.length <= 2) {
            final int limit;
            try {
                limit = args.length == 2 ? parseBrowseLimit(args[1]) : DEFAULT_BROWSE_LIMIT;
            } catch (IllegalArgumentException exception) {
                player.sendMessage(Component.text(
                        "Browse limit must be a whole number from 1 to " + MAX_PLAYER_BROWSE_LIMIT + "."
                ));
                return true;
            }
            scheduleBrowse(player.getUniqueId(), limit);
            return true;
        }
        if (action.equals("sell") && args.length == 2) {
            final long priceMinor;
            try {
                priceMinor = parseCoin(args[1]);
            } catch (IllegalArgumentException exception) {
                player.sendMessage(Component.text("Price must be a positive Coin value with at most two decimals."));
                return true;
            }
            createListing(player, priceMinor);
            return true;
        }
        if ((action.equals("buy") || action.equals("cancel")) && args.length == 2) {
            final UUID listingId;
            try {
                listingId = UUID.fromString(args[1]);
            } catch (IllegalArgumentException exception) {
                player.sendMessage(Component.text("Auction listing ID must be a valid UUID."));
                return true;
            }
            if (action.equals("buy")) {
                scheduleBuy(player.getUniqueId(), listingId);
            } else {
                scheduleCancel(player.getUniqueId(), listingId);
            }
            return true;
        }

        player.sendMessage(Component.text(
                "Usage: /ah [browse [limit]] | sell <coins> | buy <listing-id> | cancel <listing-id>"
        ));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("browse", "sell", "buy", "cancel").stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    private void createListing(Player player, long priceMinor) {
        UUID minecraftUuid = player.getUniqueId();
        if (sessions.isMutationFrozen(minecraftUuid)) {
            player.sendMessage(Component.text("Your persistent state is busy. Try again shortly."));
            return;
        }

        final SellClaim sellClaim;
        try {
            sellClaim = requireSellClaim(player.getInventory().getItemInMainHand());
        } catch (PaperItemRepresentationException | IllegalArgumentException exception) {
            player.sendMessage(Component.text(playerMessage(exception, "Hold one authoritative unique item in your main hand.")));
            return;
        }

        UUID operationId = UUID.randomUUID();
        AtomicReference<AuctionListingCreateResult> committed = new AtomicReference<>();
        sessions.mutateAuthoritativeState(player, context -> {
            byte[] nextPayload = uniqueItemRemoval.remove(
                    context.playerId(),
                    sellClaim.itemInstanceId(),
                    sellClaim.authorityVersion(),
                    context.currentStatePayload()
            );
            AuctionListingCreateResult created = auctions.createListing(
                    operationId,
                    context.sessionId(),
                    context.backendId(),
                    context.stateVersion(),
                    sellClaim.itemInstanceId(),
                    sellClaim.authorityVersion(),
                    priceMinor,
                    context.logicalZoneId(),
                    context.entryPoint(),
                    nextPayload,
                    LIST_REASON
            );
            committed.set(created);
            return new PaperAuthoritativeStateMutation.Result(created.playerStateVersion(), nextPayload);
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                Throwable cause = unwrap(failure);
                if (cause instanceof SessionConflictException) {
                    sendIfOnline(minecraftUuid, "Your persistent state changed. Try listing the item again.");
                } else if (cause instanceof AuctionHouseException auctionFailure) {
                    sendIfOnline(minecraftUuid, playerMessage(auctionFailure, "Auction House rejected that listing."));
                } else if (cause instanceof PaperItemRepresentationException representationFailure) {
                    sendIfOnline(minecraftUuid, playerMessage(
                            representationFailure,
                            "The held unique item no longer matches your authoritative inventory."
                    ));
                } else {
                    plugin.getLogger().log(Level.WARNING, "Auction House listing failed closed", cause);
                    sendIfOnline(minecraftUuid, "Auction House is temporarily unavailable.");
                }
                return;
            }

            AuctionListingCreateResult created = committed.get();
            if (created == null) {
                plugin.getLogger().severe("Auction House listing committed without a captured result");
                return;
            }
            sendIfOnline(
                    minecraftUuid,
                    "Listed " + displayName(created.definitionId()) + " for " + formatCoin(created.priceMinor())
                            + " — listing " + created.listingId()
            );
        });
    }

    private SellClaim requireSellClaim(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("Hold one unique item in your main hand to sell it.");
        }
        Optional<ItemRepresentationClaim> optional = identityCodec.readClaim(stack, "main_hand");
        if (optional.isEmpty()) {
            throw new IllegalArgumentException("The main-hand item is not a managed server item.");
        }
        ItemRepresentationClaim claim = optional.orElseThrow();
        if (!claim.individualClaim() || claim.itemInstanceId() == null || claim.authorityVersion() == null
                || claim.amount() != 1) {
            throw new IllegalArgumentException("Only individualized one-of-one items can be sold on the Auction House.");
        }
        ItemDefinition definition = itemCatalog.require(claim.definitionId());
        if (definition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
            throw new IllegalArgumentException("Only individualized items can be sold on the Auction House.");
        }
        if (!definition.minecraftMaterial().equals(claim.minecraftMaterial())) {
            throw new PaperItemRepresentationException("The main-hand item material does not match its definition.");
        }
        return new SellClaim(claim.itemInstanceId(), claim.authorityVersion());
    }

    private void scheduleBrowse(UUID minecraftUuid, int limit) {
        runAsync(() -> {
            try {
                List<AuctionBrowseListing> listings = queries.listActive(limit);
                if (listings.isEmpty()) {
                    sendIfOnline(minecraftUuid, "Auction House has no active listings.");
                    return;
                }
                sendIfOnline(minecraftUuid, "Auction House — newest " + listings.size() + " active listings:");
                for (AuctionBrowseListing listing : listings) {
                    sendIfOnline(minecraftUuid, formatListing(listing));
                }
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.WARNING, "Auction House browse persistence failed", exception);
                sendIfOnline(minecraftUuid, "Auction House is temporarily unavailable.");
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Auction House browse failed closed", exception);
                sendIfOnline(minecraftUuid, "Auction House is temporarily unavailable.");
            }
        });
    }

    private void scheduleBuy(UUID minecraftUuid, UUID listingId) {
        runAsync(() -> {
            try {
                Optional<UUID> playerId = playerIdentities.resolve(minecraftUuid);
                if (playerId.isEmpty()) {
                    sendIfOnline(minecraftUuid, "Auction House could not resolve your persistent player identity.");
                    return;
                }
                AuctionPurchaseResult purchased = auctions.purchase(
                        UUID.randomUUID(),
                        listingId,
                        playerId.orElseThrow(),
                        BUY_REASON
                );
                uniqueDeliveries.requestDrain(minecraftUuid);
                sendIfOnline(
                        minecraftUuid,
                        "Bought " + displayName(purchased.definitionId()) + " for " + formatCoin(purchased.priceMinor())
                                + ". Delivery is secured while it is added to your inventory."
                );
            } catch (AuctionHouseException exception) {
                sendIfOnline(minecraftUuid, playerMessage(exception, "Auction House rejected that purchase."));
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.WARNING, "Auction House purchase persistence failed", exception);
                sendIfOnline(minecraftUuid, "Auction House is temporarily unavailable.");
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Auction House purchase failed closed", exception);
                sendIfOnline(minecraftUuid, "Auction House is temporarily unavailable.");
            }
        });
    }

    private void scheduleCancel(UUID minecraftUuid, UUID listingId) {
        runAsync(() -> {
            try {
                Optional<UUID> playerId = playerIdentities.resolve(minecraftUuid);
                if (playerId.isEmpty()) {
                    sendIfOnline(minecraftUuid, "Auction House could not resolve your persistent player identity.");
                    return;
                }
                AuctionCancelResult cancelled = auctions.cancel(
                        UUID.randomUUID(),
                        listingId,
                        playerId.orElseThrow(),
                        CANCEL_REASON
                );
                uniqueDeliveries.requestDrain(minecraftUuid);
                sendIfOnline(
                        minecraftUuid,
                        "Cancelled listing " + cancelled.listingId() + ". "
                                + displayName(cancelled.definitionId()) + " is secured for return delivery."
                );
            } catch (AuctionHouseException exception) {
                sendIfOnline(minecraftUuid, playerMessage(exception, "Auction House rejected that cancellation."));
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.WARNING, "Auction House cancellation persistence failed", exception);
                sendIfOnline(minecraftUuid, "Auction House is temporarily unavailable.");
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Auction House cancellation failed closed", exception);
                sendIfOnline(minecraftUuid, "Auction House is temporarily unavailable.");
            }
        });
    }

    private String formatListing(AuctionBrowseListing listing) {
        ItemDefinition definition = itemCatalog.require(listing.definitionId());
        String rolls = String.join(
                ", ",
                PaperItemRuntimePresentation.describeRolls(
                        definition,
                        listing.rollQualityBasisPoints()
                )
        );
        String upgrade = PaperItemRuntimePresentation.describeUpgrade(
                definition,
                listing.upgradeLevel()
        ).orElse(null);
        String requirements = String.join(
                ", ",
                PaperItemRuntimePresentation.describeUseRequirements(definition)
        );
        return listing.listingId() + " — " + definition.displayName()
                + (rolls.isEmpty() ? "" : " — " + rolls)
                + (upgrade == null ? "" : " — " + upgrade)
                + (requirements.isEmpty() ? "" : " — " + requirements)
                + " — " + formatCoin(listing.priceMinor());
    }

    private String displayName(String definitionId) {
        return itemCatalog.require(definitionId).displayName();
    }

    private void runAsync(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule Auction House work", exception);
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

    private static int parseBrowseLimit(String raw) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1 || value > MAX_PLAYER_BROWSE_LIMIT) {
                throw new IllegalArgumentException("browse limit out of range");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid browse limit", exception);
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

    private record SellClaim(UUID itemInstanceId, long authorityVersion) { }
}

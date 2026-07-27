package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.crafting.CraftRecipeCatalog;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftRecipeVersion;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftingCommissionCompletionRepository;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftingCommissionCompletionResult;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftingException;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftingExperienceFulfillmentRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarException;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinCurrency;
import io.github.kevinrabbe.minecraftserver.common.economy.CraftingCommissionBrowseEntry;
import io.github.kevinrabbe.minecraftserver.common.economy.CraftingCommissionCancelResult;
import io.github.kevinrabbe.minecraftserver.common.economy.CraftingCommissionCreateResult;
import io.github.kevinrabbe.minecraftserver.common.economy.CraftingCommissionException;
import io.github.kevinrabbe.minecraftserver.common.economy.CraftingCommissionQueryRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.CraftingCommissionRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.CraftingCommissionRequest;
import io.github.kevinrabbe.minecraftserver.common.economy.CraftingCommissionSnapshot;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.session.SessionConflictException;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

/** Player-facing funded crafting jobs over the proven commission and crafting-output authorities. */
final class PaperCraftingCommissionCommand implements CommandExecutor, TabCompleter {
    private static final int DEFAULT_BROWSE_LIMIT = 20;
    private static final int MAX_BROWSE_LIMIT = 100;
    private static final String CREATE_REASON = "commission.player_create";
    private static final String CANCEL_REASON = "commission.player_cancel";
    private static final String COMPLETE_REASON = "commission.player_complete";

    private final MinecraftServerPlugin plugin;
    private final PaperSessionController sessions;
    private final PaperPlayerIdentityResolver playerIdentities;
    private final PaperCommodityDeliveryController commodityDeliveries;
    private final PaperUniqueDeliveryController uniqueDeliveries;
    private final CraftingCommissionRepository commissions;
    private final CraftingCommissionCompletionRepository completion;
    private final CraftingCommissionQueryRepository queries;
    private final CraftingExperienceFulfillmentRepository experience;
    private final PaperItemUseEligibilityController itemUseEligibility;
    private final CraftRecipeCatalog recipes;
    private final ItemCatalog itemCatalog;
    private final PaperCommodityBatchStateMutator ingredientMutator;
    private final Map<String, CraftRecipeVersion> currentRecipes;

    PaperCraftingCommissionCommand(
            MinecraftServerPlugin plugin,
            PaperSessionController sessions,
            PaperPlayerIdentityResolver playerIdentities,
            PaperCommodityDeliveryController commodityDeliveries,
            PaperUniqueDeliveryController uniqueDeliveries,
            CraftingCommissionRepository commissions,
            CraftingCommissionCompletionRepository completion,
            CraftingCommissionQueryRepository queries,
            CraftingExperienceFulfillmentRepository experience,
            PaperItemUseEligibilityController itemUseEligibility,
            CraftRecipeCatalog recipes,
            ItemCatalog itemCatalog,
            PaperCommodityBatchStateMutator ingredientMutator
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.playerIdentities = Objects.requireNonNull(playerIdentities, "playerIdentities");
        this.commodityDeliveries = Objects.requireNonNull(commodityDeliveries, "commodityDeliveries");
        this.uniqueDeliveries = Objects.requireNonNull(uniqueDeliveries, "uniqueDeliveries");
        this.commissions = Objects.requireNonNull(commissions, "commissions");
        this.completion = Objects.requireNonNull(completion, "completion");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.experience = Objects.requireNonNull(experience, "experience");
        this.itemUseEligibility = Objects.requireNonNull(itemUseEligibility, "itemUseEligibility");
        this.recipes = Objects.requireNonNull(recipes, "recipes");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.ingredientMutator = Objects.requireNonNull(ingredientMutator, "ingredientMutator");
        this.currentRecipes = latestVersions(recipes);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can use crafting commissions."));
            return true;
        }
        try {
            if (args.length == 0 || args[0].equalsIgnoreCase("browse")) {
                int limit = args.length == 2 ? parseLimit(args[1]) : DEFAULT_BROWSE_LIMIT;
                if (args.length > 2) {
                    usage(player);
                } else {
                    scheduleBrowse(player.getUniqueId(), limit);
                }
                return true;
            }

            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "view" -> {
                    if (args.length != 2) usage(player); else scheduleView(player.getUniqueId(), parseUuid(args[1]));
                }
                case "create" -> {
                    if (args.length != 3) usage(player); else create(player, args[1], parseNonNegativeCoin(args[2]));
                }
                case "accept" -> {
                    if (args.length != 2) usage(player); else scheduleAccept(player.getUniqueId(), parseUuid(args[1]));
                }
                case "complete" -> {
                    if (args.length != 2) usage(player); else scheduleComplete(player.getUniqueId(), parseUuid(args[1]));
                }
                case "cancel" -> {
                    if (args.length != 2) usage(player); else scheduleCancel(player.getUniqueId(), parseUuid(args[1]));
                }
                default -> usage(player);
            }
        } catch (IllegalArgumentException exception) {
            player.sendMessage(Component.text(playerMessage(exception, "Invalid commission command arguments.")));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("browse", "view", "create", "accept", "complete", "cancel").stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return currentRecipes.keySet().stream().filter(id -> id.startsWith(prefix)).sorted().toList();
        }
        return List.of();
    }

    private void scheduleBrowse(UUID minecraftUuid, int limit) {
        runAsync(() -> {
            try {
                List<CraftingCommissionBrowseEntry> entries = queries.listOpen(limit);
                ArrayList<String> messages = new ArrayList<>();
                messages.add("Open crafting commissions: " + entries.size() + " shown.");
                for (CraftingCommissionBrowseEntry entry : entries) {
                    CraftRecipeVersion recipe = recipes.require(entry.recipeId(), entry.recipeVersion());
                    messages.add(
                            "- " + entry.commissionId() + " | " + entry.recipeId() + " v" + entry.recipeVersion()
                                    + " -> " + itemCatalog.require(recipe.recipe().outputDefinitionId()).displayName()
                                    + " | pay " + formatCoin(entry.paymentMinor())
                                    + " | materials " + formatMaterials(entry.materialQuantities())
                    );
                }
                sendMessagesIfOnline(minecraftUuid, messages);
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(minecraftUuid, "Could not browse crafting commissions.", exception);
            }
        });
    }

    private void scheduleView(UUID minecraftUuid, UUID commissionId) {
        runAsync(() -> {
            try {
                CraftingCommissionSnapshot commission = commissions.load(commissionId);
                CraftRecipeVersion recipe = recipes.require(commission.recipeId(), commission.recipeVersion());
                ArrayList<String> messages = new ArrayList<>();
                messages.add("Commission " + commission.commissionId() + " — " + commission.status());
                messages.add(
                        commission.recipeId() + " v" + commission.recipeVersion() + " -> "
                                + itemCatalog.require(recipe.recipe().outputDefinitionId()).displayName()
                                + " | pay " + formatCoin(commission.paymentMinor())
                );
                messages.add("Materials: " + formatMaterials(commission.materialQuantities()));
                messages.add("Requester: " + commission.requesterPlayerId());
                if (commission.workerPlayerId() != null) messages.add("Worker: " + commission.workerPlayerId());
                sendMessagesIfOnline(minecraftUuid, messages);
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(minecraftUuid, "Could not load crafting commission.", exception);
            }
        });
    }

    private void create(Player player, String rawRecipeId, long paymentMinor) {
        String recipeId = rawRecipeId.trim().toLowerCase(Locale.ROOT);
        CraftRecipeVersion recipe = currentRecipes.get(recipeId);
        if (recipe == null) {
            player.sendMessage(Component.text("Unknown recipe. Available: " + String.join(", ", currentRecipes.keySet())));
            return;
        }
        UUID minecraftUuid = player.getUniqueId();
        if (sessions.isMutationFrozen(minecraftUuid)) {
            player.sendMessage(Component.text("Your persistent state is busy. Try again shortly."));
            return;
        }

        UUID operationId = UUID.randomUUID();
        Map<String, Long> materials = ingredientQuantities(recipe);
        AtomicReference<CraftingCommissionCreateResult> committed = new AtomicReference<>();
        sessions.mutateAuthoritativeState(player, context -> {
            byte[] nextPayload = ingredientMutator.remove(
                    context.playerId(), materials, context.currentStatePayload()
            );
            CraftingCommissionCreateResult result = commissions.createFunded(
                    operationId,
                    context.sessionId(),
                    context.backendId(),
                    context.stateVersion(),
                    new CraftingCommissionRequest(
                            recipe.recipe().recipeId(),
                            recipe.version(),
                            materials,
                            paymentMinor
                    ),
                    context.logicalZoneId(),
                    context.entryPoint(),
                    nextPayload,
                    CREATE_REASON
            );
            committed.set(result);
            return new PaperAuthoritativeStateMutation.Result(result.playerStateVersion(), nextPayload);
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                handleMutationFailure(minecraftUuid, "Could not create crafting commission.", failure);
                return;
            }
            CraftingCommissionCreateResult result = committed.get();
            if (result == null) {
                plugin.getLogger().severe("Commission creation committed without captured result");
                return;
            }
            sendIfOnline(
                    minecraftUuid,
                    "Created commission " + result.commission().commissionId() + " for "
                            + itemCatalog.require(recipe.recipe().outputDefinitionId()).displayName()
                            + ". Escrowed " + formatMaterials(materials)
                            + " and " + formatCoin(paymentMinor) + " payment."
            );
        });
    }

    private void scheduleAccept(UUID minecraftUuid, UUID commissionId) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                CraftingCommissionSnapshot accepted = commissions.accept(
                        stableOperationId("accept", commissionId, playerId),
                        commissionId,
                        playerId
                );
                sendIfOnline(
                        minecraftUuid,
                        "Accepted commission " + accepted.commissionId()
                                + ". Complete it with /commission complete " + accepted.commissionId() + "."
                );
                notifyPlayerId(
                        accepted.requesterPlayerId(),
                        "Your crafting commission " + accepted.commissionId() + " was accepted."
                );
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(minecraftUuid, "Could not accept crafting commission.", exception);
            }
        });
    }

    private void scheduleComplete(UUID minecraftUuid, UUID commissionId) {
        runAsync(() -> {
            try {
                UUID workerPlayerId = requirePlayerId(minecraftUuid);
                CraftingCommissionCompletionResult result = completion.complete(
                        stableOperationId("complete", commissionId, workerPlayerId),
                        commissionId,
                        workerPlayerId,
                        COMPLETE_REASON
                );
                Optional<UUID> requesterMinecraftUuid = playerIdentities.resolveMinecraftUuid(
                        result.commission().requesterPlayerId()
                );
                requesterMinecraftUuid.ifPresent(value -> {
                    commodityDeliveries.requestDrain(value);
                    uniqueDeliveries.requestDrain(value);
                });

                long grantedExperience = fulfillExperience(result);
                String xp = grantedExperience >= 0 ? " Crafting XP +" + grantedExperience + "." : "";
                sendIfOnline(
                        minecraftUuid,
                        "Completed commission " + commissionId + ". Payment received: "
                                + formatCoin(result.commission().paymentMinor()) + "." + xp
                );
                requesterMinecraftUuid.ifPresent(value -> sendIfOnline(
                        value,
                        "Your commission " + commissionId + " is complete. "
                                + itemCatalog.require(result.craft().outputDefinitionId()).displayName()
                                + " is secured for delivery."
                ));
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(minecraftUuid, "Could not complete crafting commission.", exception);
            }
        });
    }

    private void scheduleCancel(UUID minecraftUuid, UUID commissionId) {
        runAsync(() -> {
            try {
                UUID requesterPlayerId = requirePlayerId(minecraftUuid);
                CraftingCommissionCancelResult result = commissions.cancelOpen(
                        stableOperationId("cancel", commissionId, requesterPlayerId),
                        commissionId,
                        requesterPlayerId,
                        CANCEL_REASON
                );
                commodityDeliveries.requestDrain(minecraftUuid);
                sendIfOnline(
                        minecraftUuid,
                        "Cancelled commission " + commissionId + ". Refunded "
                                + formatCoin(result.commission().paymentMinor()) + " and returned "
                                + formatMaterials(result.commission().materialQuantities()) + " through secured delivery."
                );
            } catch (SQLException | RuntimeException exception) {
                handleAsyncFailure(minecraftUuid, "Could not cancel crafting commission.", exception);
            }
        });
    }

    private long fulfillExperience(CraftingCommissionCompletionResult result) {
        try {
            var fulfilled = experience.fulfill(result.craft().craftId());
            itemUseEligibility.applyCommittedAward(fulfilled.experienceAward());
            return fulfilled.experienceAward().grantedExperience();
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Commission craft completed but Crafting XP remains recoverable for " + result.craft().craftId(),
                    exception
            );
            return -1L;
        }
    }

    private void notifyPlayerId(UUID playerId, String message) {
        try {
            playerIdentities.resolveMinecraftUuid(playerId).ifPresent(uuid -> sendIfOnline(uuid, message));
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not resolve commission participant for notification", exception);
        }
    }

    private UUID requirePlayerId(UUID minecraftUuid) throws SQLException {
        return playerIdentities.resolve(minecraftUuid).orElseThrow(
                () -> new CraftingCommissionException("Persistent player identity is not available.")
        );
    }

    private void handleMutationFailure(UUID minecraftUuid, String fallback, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof SessionConflictException) {
            sendIfOnline(minecraftUuid, "Your persistent state changed. Review the commission and try again.");
            return;
        }
        handleKnownFailure(minecraftUuid, fallback, cause);
    }

    private void handleAsyncFailure(UUID minecraftUuid, String fallback, Throwable failure) {
        handleKnownFailure(minecraftUuid, fallback, unwrap(failure));
    }

    private void handleKnownFailure(UUID minecraftUuid, String fallback, Throwable failure) {
        if (failure instanceof CraftingCommissionException
                || failure instanceof CraftingException
                || failure instanceof BazaarException
                || failure instanceof IllegalArgumentException) {
            sendIfOnline(minecraftUuid, playerMessage(failure, fallback));
            return;
        }
        plugin.getLogger().log(Level.WARNING, fallback, failure);
        sendIfOnline(minecraftUuid, fallback);
    }

    private void runAsync(Runnable task) {
        if (!plugin.isEnabled()) return;
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule crafting commission work", exception);
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
        player.sendMessage(Component.text(
                "Commission: /commission [browse [limit]] | view <id> | create <recipe> <payment-coins>"
        ));
        player.sendMessage(Component.text(
                "            /commission accept <id> | complete <id> | cancel <id>"
        ));
    }

    private String formatMaterials(Map<String, Long> materials) {
        return materials.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getValue() + " " + itemCatalog.require(entry.getKey()).displayName())
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
    }

    private static Map<String, CraftRecipeVersion> latestVersions(CraftRecipeCatalog recipes) {
        LinkedHashMap<String, CraftRecipeVersion> latest = new LinkedHashMap<>();
        for (CraftRecipeVersion version : recipes.all()) {
            latest.merge(
                    version.recipe().recipeId(),
                    version,
                    (left, right) -> right.version() > left.version() ? right : left
            );
        }
        return Map.copyOf(latest);
    }

    private static Map<String, Long> ingredientQuantities(CraftRecipeVersion recipe) {
        LinkedHashMap<String, Long> quantities = new LinkedHashMap<>();
        recipe.recipe().ingredients().forEach(ingredient -> quantities.merge(
                ingredient.definitionId(),
                ingredient.quantity(),
                Math::addExact
        ));
        return Map.copyOf(quantities);
    }

    private static UUID stableOperationId(String action, UUID commissionId, UUID playerId) {
        return UUID.nameUUIDFromBytes(
                ("paper-commission:" + action + ":" + commissionId + ":" + playerId)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    private static int parseLimit(String raw) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1 || value > MAX_BROWSE_LIMIT) {
                throw new IllegalArgumentException("limit must be between 1 and " + MAX_BROWSE_LIMIT);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("limit must be a whole number", exception);
        }
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("commission ID must be a valid UUID", exception);
        }
    }

    private static long parseNonNegativeCoin(String raw) {
        try {
            BigDecimal coins = new BigDecimal(raw.trim());
            if (coins.signum() < 0) throw new IllegalArgumentException("payment must be >= 0 Coin");
            return coins.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("payment must be nonnegative with at most two decimals", exception);
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
}

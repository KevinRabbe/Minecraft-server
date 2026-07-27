package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.crafting.CraftRecipeCatalog;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftRecipeVersion;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftingException;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftingExperienceFulfillmentRepository;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftingStateExecutionResult;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftingStateExecutionService;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarException;
import io.github.kevinrabbe.minecraftserver.common.session.SessionConflictException;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/** Minimal live personal-crafting bridge over the proven fenced crafting authority. */
final class PaperCraftingController implements CommandExecutor, TabCompleter {
    private static final String CRAFT_REASON = "craft.personal";
    private static final int XP_RECOVERY_BATCH = 100;

    private final JavaPlugin plugin;
    private final PaperSessionController sessions;
    private final CraftingStateExecutionService crafting;
    private final CraftingExperienceFulfillmentRepository experience;
    private final PaperItemUseEligibilityController itemUseEligibility;
    private final PaperCommodityBatchStateMutator ingredientMutator;
    private final PaperCommodityDeliveryController commodityDeliveries;
    private final PaperUniqueDeliveryController uniqueDeliveries;
    private final Map<String, CraftRecipeVersion> currentRecipes;

    PaperCraftingController(
            MinecraftServerPlugin plugin,
            PaperSessionController sessions,
            CraftingStateExecutionService crafting,
            CraftingExperienceFulfillmentRepository experience,
            PaperItemUseEligibilityController itemUseEligibility,
            CraftRecipeCatalog recipes,
            PaperCommodityBatchStateMutator ingredientMutator,
            PaperCommodityDeliveryController commodityDeliveries,
            PaperUniqueDeliveryController uniqueDeliveries
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.crafting = Objects.requireNonNull(crafting, "crafting");
        this.experience = Objects.requireNonNull(experience, "experience");
        this.itemUseEligibility = Objects.requireNonNull(itemUseEligibility, "itemUseEligibility");
        this.ingredientMutator = Objects.requireNonNull(ingredientMutator, "ingredientMutator");
        this.commodityDeliveries = Objects.requireNonNull(commodityDeliveries, "commodityDeliveries");
        this.uniqueDeliveries = Objects.requireNonNull(uniqueDeliveries, "uniqueDeliveries");
        this.currentRecipes = latestVersions(Objects.requireNonNull(recipes, "recipes"));
    }

    void recoverPendingExperience() {
        runAsync(() -> {
            try {
                List<UUID> pending = experience.listUnfulfilled(XP_RECOVERY_BATCH);
                for (UUID craftId : pending) {
                    try {
                        var recovered = experience.fulfill(craftId);
                        itemUseEligibility.applyCommittedAward(recovered.experienceAward());
                    } catch (SQLException | RuntimeException exception) {
                        plugin.getLogger().log(
                                Level.WARNING,
                                "Could not recover Crafting XP for craft " + craftId,
                                exception
                        );
                    }
                }
            } catch (SQLException | RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not scan pending Crafting XP recovery", exception);
            }
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can craft through this command."));
            return true;
        }
        if (args.length != 1) {
            player.sendMessage(Component.text("Usage: /craft <recipe>"));
            return true;
        }
        String recipeId = args[0].trim().toLowerCase(Locale.ROOT);
        CraftRecipeVersion recipe = currentRecipes.get(recipeId);
        if (recipe == null) {
            player.sendMessage(Component.text("Unknown recipe. Available: " + String.join(", ", currentRecipes.keySet())));
            return true;
        }
        if (sessions.isMutationFrozen(player.getUniqueId())) {
            player.sendMessage(Component.text("Your persistent state is busy. Try again shortly."));
            return true;
        }

        UUID operationId = UUID.randomUUID();
        UUID minecraftUuid = player.getUniqueId();
        Map<String, Long> ingredients = ingredientQuantities(recipe);
        AtomicReference<CraftingStateExecutionResult> committed = new AtomicReference<>();

        sessions.mutateAuthoritativeState(player, context -> {
            byte[] nextPayload = ingredientMutator.remove(
                    context.playerId(),
                    ingredients,
                    context.currentStatePayload()
            );
            CraftingStateExecutionResult result = crafting.craftFromPlayerState(
                    operationId,
                    context.sessionId(),
                    context.backendId(),
                    context.stateVersion(),
                    recipe.recipe().recipeId(),
                    recipe.version(),
                    context.logicalZoneId(),
                    context.entryPoint(),
                    nextPayload,
                    CRAFT_REASON
            );
            committed.set(result);
            return new PaperAuthoritativeStateMutation.Result(
                    result.playerStateVersion(),
                    nextPayload
            );
        }).whenComplete((stateResult, failure) -> {
            if (failure != null) {
                handleCraftFailure(minecraftUuid, unwrap(failure));
                return;
            }

            CraftingStateExecutionResult result = committed.get();
            if (result == null) {
                plugin.getLogger().severe("Craft state committed without a captured craft result");
                return;
            }
            commodityDeliveries.requestDrain(minecraftUuid);
            uniqueDeliveries.requestDrain(minecraftUuid);
            fulfillExperienceAndReport(minecraftUuid, result);
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return currentRecipes.keySet().stream()
                .filter(recipe -> recipe.startsWith(prefix))
                .toList();
    }

    private void fulfillExperienceAndReport(UUID minecraftUuid, CraftingStateExecutionResult result) {
        runAsync(() -> {
            try {
                var xp = experience.fulfill(result.craft().craftId());
                itemUseEligibility.applyCommittedAward(xp.experienceAward());
                String roll = result.craft().rollQualityBasisPoints().isEmpty()
                        ? ""
                        : " Roll: " + result.craft().rollQualityBasisPoints();
                sendIfOnline(
                        minecraftUuid,
                        "Crafted " + result.craft().outputDefinitionId()
                                + ". Crafting XP +" + xp.experienceAward().grantedExperience() + "." + roll
                );
            } catch (SQLException | RuntimeException exception) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Craft committed but Crafting XP fulfillment remains recoverable for "
                                + result.craft().craftId(),
                        exception
                );
                sendIfOnline(minecraftUuid, "Craft completed. Progression fulfillment will recover automatically.");
            }
        });
    }

    private void handleCraftFailure(UUID minecraftUuid, Throwable failure) {
        if (failure instanceof SessionConflictException) {
            sendIfOnline(minecraftUuid, "Your persistent state changed. Try the craft again.");
            return;
        }
        if (failure instanceof BazaarException || failure instanceof CraftingException) {
            String message = failure.getMessage();
            sendIfOnline(
                    minecraftUuid,
                    message == null || message.isBlank() ? "Craft requirements are not met." : message
            );
            return;
        }
        plugin.getLogger().log(Level.WARNING, "Personal craft failed closed", failure);
        sendIfOnline(minecraftUuid, "Crafting service is temporarily unavailable.");
    }

    private void sendIfOnline(UUID minecraftUuid, String message) {
        runOnMainThread(() -> {
            Player player = plugin.getServer().getPlayer(minecraftUuid);
            if (player != null && player.isOnline()) {
                player.sendMessage(Component.text(message));
            }
        });
    }

    private void runAsync(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule crafting work", exception);
        }
    }

    private void runOnMainThread(Runnable task) {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
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

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}

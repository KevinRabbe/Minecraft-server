package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.economy.CoinCurrency;
import io.github.kevinrabbe.minecraftserver.common.economy.SalvageCatalog;
import io.github.kevinrabbe.minecraftserver.common.economy.SalvageCommodityReturn;
import io.github.kevinrabbe.minecraftserver.common.economy.SalvageDefinition;
import io.github.kevinrabbe.minecraftserver.common.economy.SalvageException;
import io.github.kevinrabbe.minecraftserver.common.economy.SalvageRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.SalvageResult;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/** Irreversible player-facing salvage bridge over the PostgreSQL unique-item authority. */
final class PaperSalvageCommand implements CommandExecutor, TabCompleter {
    private static final String SALVAGE_REASON = "salvage.player";

    private final MinecraftServerPlugin plugin;
    private final PaperSessionController sessions;
    private final PaperCommodityDeliveryController commodityDeliveries;
    private final SalvageRepository salvageRepository;
    private final SalvageCatalog salvageCatalog;
    private final ItemCatalog itemCatalog;
    private final PaperItemIdentityCodec identityCodec;
    private final PaperUniqueItemStateRemovalMutator uniqueItemRemoval;

    PaperSalvageCommand(
            MinecraftServerPlugin plugin,
            PaperSessionController sessions,
            PaperCommodityDeliveryController commodityDeliveries,
            SalvageRepository salvageRepository,
            SalvageCatalog salvageCatalog,
            ItemCatalog itemCatalog
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.commodityDeliveries = Objects.requireNonNull(commodityDeliveries, "commodityDeliveries");
        this.salvageRepository = Objects.requireNonNull(salvageRepository, "salvageRepository");
        this.salvageCatalog = Objects.requireNonNull(salvageCatalog, "salvageCatalog");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.identityCodec = new PaperItemIdentityCodec(plugin);
        this.uniqueItemRemoval = new PaperUniqueItemStateRemovalMutator(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can salvage an item."));
            return true;
        }
        if (args.length == 0) {
            preview(player);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {
            salvage(player);
            return true;
        }
        player.sendMessage(Component.text("Usage: /salvage [confirm]"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && "confirm".startsWith(args[0].toLowerCase(Locale.ROOT))) {
            return List.of("confirm");
        }
        return List.of();
    }

    private void preview(Player player) {
        try {
            SalvageClaim claim = requireSalvageClaim(player.getInventory().getItemInMainHand());
            player.sendMessage(Component.text(
                    "Salvage " + displayName(claim.definitionId()) + " for " + formatReturns(claim.definition())
                            + "? This permanently destroys the item. Use /salvage confirm while holding it."
            ));
        } catch (SalvageException | PaperItemRepresentationException | IllegalArgumentException exception) {
            player.sendMessage(Component.text(playerMessage(exception, "Hold one salvageable unique item in your main hand.")));
        }
    }

    private void salvage(Player player) {
        UUID minecraftUuid = player.getUniqueId();
        if (sessions.isMutationFrozen(minecraftUuid)) {
            player.sendMessage(Component.text("Your persistent state is busy. Try again shortly."));
            return;
        }

        final SalvageClaim claim;
        try {
            claim = requireSalvageClaim(player.getInventory().getItemInMainHand());
        } catch (SalvageException | PaperItemRepresentationException | IllegalArgumentException exception) {
            player.sendMessage(Component.text(playerMessage(exception, "Hold one salvageable unique item in your main hand.")));
            return;
        }

        UUID operationId = UUID.randomUUID();
        AtomicReference<SalvageResult> committed = new AtomicReference<>();
        sessions.mutateAuthoritativeState(player, context -> {
            byte[] nextPayload = uniqueItemRemoval.remove(
                    context.playerId(),
                    claim.itemInstanceId(),
                    claim.authorityVersion(),
                    context.currentStatePayload()
            );
            SalvageResult result = salvageRepository.salvage(
                    operationId,
                    context.sessionId(),
                    context.backendId(),
                    context.stateVersion(),
                    claim.itemInstanceId(),
                    claim.authorityVersion(),
                    context.logicalZoneId(),
                    context.entryPoint(),
                    nextPayload,
                    SALVAGE_REASON
            );
            committed.set(result);
            return new PaperAuthoritativeStateMutation.Result(result.playerStateVersion(), nextPayload);
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                Throwable cause = unwrap(failure);
                if (cause instanceof SessionConflictException) {
                    sendIfOnline(minecraftUuid, "Your persistent state changed. Try salvaging again.");
                } else if (cause instanceof SalvageException salvageFailure) {
                    sendIfOnline(minecraftUuid, playerMessage(salvageFailure, "Salvage rejected that item."));
                } else if (cause instanceof PaperItemRepresentationException representationFailure) {
                    sendIfOnline(minecraftUuid, playerMessage(
                            representationFailure,
                            "The held unique item no longer matches your authoritative inventory."
                    ));
                } else {
                    plugin.getLogger().log(Level.WARNING, "Salvage failed closed", cause);
                    sendIfOnline(minecraftUuid, "Salvage is temporarily unavailable.");
                }
                return;
            }

            SalvageResult result = committed.get();
            if (result == null) {
                plugin.getLogger().severe("Salvage committed without a captured result");
                return;
            }
            if (!result.commodityReturns().isEmpty()) {
                commodityDeliveries.requestDrain(minecraftUuid);
            }
            sendIfOnline(
                    minecraftUuid,
                    "Salvaged " + displayName(result.itemDefinitionId()) + " for " + formatReturns(result) + "."
            );
        });
    }

    private SalvageClaim requireSalvageClaim(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("Hold one unique item in your main hand to salvage it.");
        }
        Optional<ItemRepresentationClaim> optional = identityCodec.readClaim(stack, "main_hand");
        if (optional.isEmpty()) {
            throw new IllegalArgumentException("The main-hand item is not a managed server item.");
        }
        ItemRepresentationClaim claim = optional.orElseThrow();
        if (!claim.individualClaim() || claim.itemInstanceId() == null || claim.authorityVersion() == null
                || claim.amount() != 1) {
            throw new IllegalArgumentException("Only individualized one-of-one items can be salvaged.");
        }
        ItemDefinition itemDefinition = itemCatalog.find(claim.definitionId()).orElseThrow(
                () -> new PaperItemRepresentationException("The main-hand item has an unknown definition.")
        );
        if (itemDefinition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
            throw new IllegalArgumentException("Only individualized items can be salvaged.");
        }
        if (!itemDefinition.minecraftMaterial().equals(claim.minecraftMaterial())) {
            throw new PaperItemRepresentationException("The main-hand item material does not match its definition.");
        }
        SalvageDefinition definition = salvageCatalog.require(claim.definitionId());
        return new SalvageClaim(
                claim.itemInstanceId(),
                claim.authorityVersion(),
                claim.definitionId(),
                definition
        );
    }

    private String formatReturns(SalvageDefinition definition) {
        ArrayList<String> returns = new ArrayList<>();
        if (definition.coinReturnMinor() > 0) {
            returns.add(formatCoin(definition.coinReturnMinor()));
        }
        definition.commodityReturns().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> returns.add(entry.getValue() + " " + displayName(entry.getKey())));
        return returns.isEmpty() ? "no return" : String.join(" + ", returns);
    }

    private String formatReturns(SalvageResult result) {
        ArrayList<String> returns = new ArrayList<>();
        if (result.coinReturnMinor() > 0) {
            returns.add(formatCoin(result.coinReturnMinor()));
        }
        result.commodityReturns().stream()
                .sorted(java.util.Comparator.comparing(SalvageCommodityReturn::commodityDefinitionId))
                .forEach(value -> returns.add(
                        value.quantity() + " " + displayName(value.commodityDefinitionId())
                ));
        return returns.isEmpty() ? "no return" : String.join(" + ", returns);
    }

    private String displayName(String definitionId) {
        return itemCatalog.require(definitionId).displayName();
    }

    private static String formatCoin(long amountMinor) {
        long whole = amountMinor / CoinCurrency.MINOR_UNITS_PER_COIN;
        long fraction = amountMinor % CoinCurrency.MINOR_UNITS_PER_COIN;
        return String.format(Locale.ROOT, "%d.%02d Coin", whole, fraction);
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

    private record SalvageClaim(
            UUID itemInstanceId,
            long authorityVersion,
            String definitionId,
            SalvageDefinition definition
    ) { }
}

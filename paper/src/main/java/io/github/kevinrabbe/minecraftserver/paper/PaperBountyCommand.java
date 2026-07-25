package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.economy.CoinCurrency;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyContentCatalog;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyContractSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyContractStartResult;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyContractStatus;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyException;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyFamilyId;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyPouchBalanceSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyPouchRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyPouchWithdrawalResult;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyTierDefinition;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;

/** Player-facing contract/pouch surface; physical boss materialization remains a separate encounter boundary. */
final class PaperBountyCommand implements CommandExecutor, TabCompleter {
    private static final String START_REASON = "bounty.player_contract_start";
    private static final String POUCH_WITHDRAW_REASON = "bounty.player_pouch_withdraw";

    private final MinecraftServerPlugin plugin;
    private final PaperPlayerIdentityResolver playerIdentities;
    private final PaperCommodityDeliveryController commodityDeliveries;
    private final BountyContentCatalog content;
    private final BountyRepository bounties;
    private final BountyPouchRepository pouches;

    PaperBountyCommand(
            MinecraftServerPlugin plugin,
            PaperPlayerIdentityResolver playerIdentities,
            PaperCommodityDeliveryController commodityDeliveries,
            BountyContentCatalog content,
            BountyRepository bounties,
            BountyPouchRepository pouches
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.playerIdentities = Objects.requireNonNull(playerIdentities, "playerIdentities");
        this.commodityDeliveries = Objects.requireNonNull(commodityDeliveries, "commodityDeliveries");
        this.content = Objects.requireNonNull(content, "content");
        this.bounties = Objects.requireNonNull(bounties, "bounties");
        this.pouches = Objects.requireNonNull(pouches, "pouches");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can use bounty commands."));
            return true;
        }
        try {
            if (args.length == 0 || args[0].equalsIgnoreCase("tiers")) {
                if (args.length > 1) usage(player); else showTiers(player);
                return true;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "start" -> {
                    if (args.length != 3) {
                        usage(player);
                    } else {
                        scheduleStart(
                                player.getUniqueId(),
                                new BountyFamilyId(args[1]),
                                parsePositiveInt(args[2], "tier")
                        );
                    }
                }
                case "status" -> {
                    if (args.length != 2) usage(player); else scheduleStatus(player.getUniqueId(), parseUuid(args[1]));
                }
                case "pouch" -> {
                    if (args.length != 2) usage(player); else schedulePouch(player.getUniqueId(), new BountyFamilyId(args[1]));
                }
                case "withdraw" -> {
                    if (args.length != 4) {
                        usage(player);
                    } else {
                        scheduleWithdraw(
                                player.getUniqueId(),
                                new BountyFamilyId(args[1]),
                                args[2],
                                parsePositiveLong(args[3], "quantity")
                        );
                    }
                }
                default -> usage(player);
            }
        } catch (IllegalArgumentException exception) {
            player.sendMessage(Component.text(playerMessage(exception, "Invalid bounty command arguments.")));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("tiers", "start", "status", "pouch", "withdraw").stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && List.of("start", "pouch", "withdraw").contains(args[0].toLowerCase(Locale.ROOT))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return content.definitions().stream()
                    .map(definition -> definition.familyId().value())
                    .distinct()
                    .filter(value -> value.startsWith(prefix))
                    .sorted()
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("start")) {
            String family = args[1].toLowerCase(Locale.ROOT);
            String prefix = args[2];
            return content.definitions().stream()
                    .filter(definition -> definition.familyId().value().equals(family))
                    .map(definition -> Integer.toString(definition.tier()))
                    .filter(value -> value.startsWith(prefix))
                    .sorted()
                    .toList();
        }
        return List.of();
    }

    private void showTiers(Player player) {
        player.sendMessage(Component.text("Available bounty tiers:"));
        content.definitions().stream()
                .sorted(java.util.Comparator.comparing((BountyTierDefinition value) -> value.familyId().value())
                        .thenComparingInt(BountyTierDefinition::tier))
                .forEach(definition -> player.sendMessage(Component.text(
                        "- " + definition.familyId().value() + " T" + definition.tier()
                                + " | fee " + formatCoin(definition.contractFeeMinor())
                                + " | hunt " + definition.requiredEligibleKills() + " eligible kills"
                                + " | boss " + definition.bossDefinitionId()
                )));
    }

    private void scheduleStart(UUID minecraftUuid, BountyFamilyId familyId, int tier) {
        BountyTierDefinition definition;
        try {
            definition = content.tiers().require(familyId, tier);
        } catch (RuntimeException exception) {
            sendIfOnline(minecraftUuid, playerMessage(exception, "Unknown bounty tier."));
            return;
        }
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                BountyContractStartResult result = bounties.startContract(
                        UUID.randomUUID(),
                        playerId,
                        familyId,
                        tier,
                        START_REASON
                );
                BountyContractSnapshot contract = result.contract();
                sendIfOnline(
                        minecraftUuid,
                        "Started " + familyId.value() + " T" + tier + " bounty " + contract.contractId()
                                + ". Hunt progress 0/" + definition.requiredEligibleKills()
                                + "; wallet " + formatCoin(result.walletBalanceMinor()) + "."
                );
            } catch (SQLException | RuntimeException exception) {
                handleFailure(minecraftUuid, "Could not start bounty contract.", exception);
            }
        });
    }

    private void scheduleStatus(UUID minecraftUuid, UUID contractId) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                BountyContractSnapshot contract = bounties.loadContract(contractId);
                if (!contract.playerId().equals(playerId)) {
                    throw new BountyException("That bounty contract belongs to another player.");
                }
                String suffix = contract.status() == BountyContractStatus.SUMMON_READY
                        ? " Boss summon authorization is ready; physical boss materialization is not exposed yet."
                        : "";
                sendIfOnline(
                        minecraftUuid,
                        "Bounty " + contract.familyId().value() + " T" + contract.tier() + " — " + contract.status()
                                + " — " + contract.eligibleKillProgress() + "/" + contract.requiredEligibleKills()
                                + " kills — summon authorizations " + contract.summonAuthorizationsRemaining() + "."
                                + suffix
                );
            } catch (SQLException | RuntimeException exception) {
                handleFailure(minecraftUuid, "Could not load bounty contract.", exception);
            }
        });
    }

    private void schedulePouch(UUID minecraftUuid, BountyFamilyId familyId) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                List<BountyPouchBalanceSnapshot> balances = pouches.listBalances(playerId, familyId);
                ArrayList<String> messages = new ArrayList<>();
                messages.add("Bounty pouch " + familyId.value() + ":");
                if (balances.isEmpty()) {
                    messages.add("- empty");
                } else {
                    for (BountyPouchBalanceSnapshot balance : balances) {
                        messages.add("- " + balance.commodityDefinitionId() + ": " + balance.quantity());
                    }
                }
                sendMessagesIfOnline(minecraftUuid, messages);
            } catch (SQLException | RuntimeException exception) {
                handleFailure(minecraftUuid, "Could not load bounty pouch.", exception);
            }
        });
    }

    private void scheduleWithdraw(
            UUID minecraftUuid,
            BountyFamilyId familyId,
            String commodityDefinitionId,
            long quantity
    ) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                BountyPouchWithdrawalResult result = pouches.withdraw(
                        UUID.randomUUID(),
                        playerId,
                        familyId,
                        commodityDefinitionId,
                        quantity,
                        POUCH_WITHDRAW_REASON
                );
                commodityDeliveries.requestDrain(minecraftUuid);
                sendIfOnline(
                        minecraftUuid,
                        "Withdrew " + result.withdrawnQuantity() + " " + result.balance().commodityDefinitionId()
                                + " from bounty pouch. Remaining: " + result.balance().quantity()
                                + ". Delivery is secured."
                );
            } catch (SQLException | RuntimeException exception) {
                handleFailure(minecraftUuid, "Could not withdraw bounty pouch material.", exception);
            }
        });
    }

    private UUID requirePlayerId(UUID minecraftUuid) throws SQLException {
        return playerIdentities.resolve(minecraftUuid).orElseThrow(
                () -> new BountyException("Persistent player identity is not available.")
        );
    }

    private void handleFailure(UUID minecraftUuid, String fallback, Throwable failure) {
        if (failure instanceof BountyException || failure instanceof IllegalArgumentException) {
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
            plugin.getLogger().log(Level.WARNING, "Could not schedule bounty command work", exception);
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
                "Bounty: /bounty tiers | start <family> <tier> | status <contract-id> | pouch <family>"
        ));
        player.sendMessage(Component.text(
                "        /bounty withdraw <family> <commodity> <quantity>"
        ));
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("contract ID must be a valid UUID", exception);
        }
    }

    private static int parsePositiveInt(String raw, String label) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value <= 0) throw new IllegalArgumentException(label + " must be > 0");
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a whole number", exception);
        }
    }

    private static long parsePositiveLong(String raw, String label) {
        try {
            long value = Long.parseLong(raw.trim());
            if (value <= 0) throw new IllegalArgumentException(label + " must be > 0");
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

    private static String playerMessage(Throwable exception, String fallback) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }
}

package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.economy.BankAccountSnapshot;
import io.github.kevinrabbe.minecraftserver.common.economy.BankManagerException;
import io.github.kevinrabbe.minecraftserver.common.economy.BankManagerRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.BankTierCatalog;
import io.github.kevinrabbe.minecraftserver.common.economy.BankTierDefinition;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinCurrency;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletSnapshot;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;

/** Minimal live Bank Manager surface over the fixed-point PostgreSQL authority. */
final class PaperBankCommand implements CommandExecutor, TabCompleter {
    private static final String DEPOSIT_REASON = "bank.player_deposit";
    private static final String WITHDRAW_REASON = "bank.player_withdraw";
    private static final String UPGRADE_REASON = "bank.player_upgrade";

    private final JavaPlugin plugin;
    private final PaperPlayerIdentityResolver playerIdentities;
    private final BankManagerRepository bank;
    private final CoinWalletRepository wallet;
    private final BankTierCatalog tiers;

    PaperBankCommand(
            JavaPlugin plugin,
            PaperPlayerIdentityResolver playerIdentities,
            BankManagerRepository bank,
            CoinWalletRepository wallet,
            BankTierCatalog tiers
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.playerIdentities = Objects.requireNonNull(playerIdentities, "playerIdentities");
        this.bank = Objects.requireNonNull(bank, "bank");
        this.wallet = Objects.requireNonNull(wallet, "wallet");
        this.tiers = Objects.requireNonNull(tiers, "tiers");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player has a Bank Manager account."));
            return true;
        }
        if (args.length == 0) {
            schedule(player.getUniqueId(), Action.STATUS, 0L);
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("upgrade") && args.length == 1) {
            schedule(player.getUniqueId(), Action.UPGRADE, 0L);
            return true;
        }
        if ((action.equals("deposit") || action.equals("withdraw")) && args.length == 2) {
            final long amountMinor;
            try {
                amountMinor = parseCoin(args[1]);
            } catch (IllegalArgumentException exception) {
                player.sendMessage(Component.text("Amount must be a positive Coin value with at most two decimals."));
                return true;
            }
            schedule(
                    player.getUniqueId(),
                    action.equals("deposit") ? Action.DEPOSIT : Action.WITHDRAW,
                    amountMinor
            );
            return true;
        }

        player.sendMessage(Component.text("Usage: /bank [deposit <coins>|withdraw <coins>|upgrade]"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("deposit", "withdraw", "upgrade").stream()
                .filter(value -> value.startsWith(prefix))
                .toList();
    }

    private void schedule(UUID minecraftUuid, Action action, long amountMinor) {
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(
                    plugin,
                    () -> execute(minecraftUuid, action, amountMinor)
            );
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule Bank Manager operation", exception);
            sendIfOnline(minecraftUuid, "Bank Manager is temporarily unavailable.");
        }
    }

    private void execute(UUID minecraftUuid, Action action, long amountMinor) {
        try {
            Optional<UUID> resolved = playerIdentities.resolve(minecraftUuid);
            if (resolved.isEmpty()) {
                sendIfOnline(minecraftUuid, "Bank Manager could not resolve your persistent player identity.");
                return;
            }
            UUID playerId = resolved.orElseThrow();
            switch (action) {
                case STATUS -> { }
                case DEPOSIT -> bank.deposit(UUID.randomUUID(), playerId, amountMinor, DEPOSIT_REASON);
                case WITHDRAW -> bank.withdraw(UUID.randomUUID(), playerId, amountMinor, WITHDRAW_REASON);
                case UPGRADE -> bank.upgrade(UUID.randomUUID(), playerId, UPGRADE_REASON);
            }
            sendIfOnline(minecraftUuid, describe(playerId));
        } catch (BankManagerException exception) {
            sendIfOnline(minecraftUuid, exception.getMessage() == null
                    ? "Bank Manager rejected that operation."
                    : exception.getMessage());
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Bank Manager persistence failed", exception);
            sendIfOnline(minecraftUuid, "Bank Manager is temporarily unavailable.");
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Bank Manager operation failed closed", exception);
            sendIfOnline(minecraftUuid, "Bank Manager is temporarily unavailable.");
        }
    }

    private String describe(UUID playerId) throws SQLException {
        CoinWalletSnapshot walletState = wallet.load(playerId);
        BankAccountSnapshot bankState = bank.load(playerId);
        BankTierDefinition tier = tiers.require(bankState.tier());

        StringBuilder result = new StringBuilder()
                .append("Bank Tier ").append(bankState.tier())
                .append(" — Protected ").append(formatCoin(bankState.balanceMinor()))
                .append(" / ").append(formatCoin(tier.capacityMinor()))
                .append(" — Wallet ").append(formatCoin(walletState.balanceMinor()))
                .append(" — Daily interest ").append(formatBasisPoints(tier.dailyInterestBasisPoints()));
        if (bankState.tier() < tiers.maxTier()) {
            BankTierDefinition next = tiers.next(bankState.tier());
            result.append(" — Next tier cost ").append(formatCoin(next.upgradeCostMinor()));
        } else {
            result.append(" — Max tier");
        }
        return result.toString();
    }

    private void sendIfOnline(UUID minecraftUuid, String message) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(minecraftUuid);
            if (player != null && player.isOnline()) {
                player.sendMessage(Component.text(message));
            }
        });
    }

    private static long parseCoin(String raw) {
        try {
            BigDecimal coins = new BigDecimal(raw.trim());
            if (coins.signum() <= 0) {
                throw new IllegalArgumentException("amount must be positive");
            }
            BigDecimal minor = coins
                    .setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2);
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

    private enum Action {
        STATUS,
        DEPOSIT,
        WITHDRAW,
        UPGRADE
    }
}

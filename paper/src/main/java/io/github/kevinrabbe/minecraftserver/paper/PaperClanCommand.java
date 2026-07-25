package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.clan.ClanAssetException;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanMemberSnapshot;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanMembershipException;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanMembershipRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanRole;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanRoleRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanSnapshot;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanTreasuryRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanTreasurySnapshot;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanTreasuryTransferResult;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinCurrency;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;

/** Command-first Paper bridge for persistent MMO clan membership, roles, and protected treasury Coin. */
final class PaperClanCommand implements CommandExecutor, TabCompleter {
    private static final Duration INVITE_LIFETIME = Duration.ofDays(7);
    private static final String TREASURY_DEPOSIT_REASON = "clan.player_treasury_deposit";
    private static final String TREASURY_WITHDRAW_REASON = "clan.player_treasury_withdraw";

    private final MinecraftServerPlugin plugin;
    private final PaperPlayerIdentityResolver playerIdentities;
    private final ClanMembershipRepository memberships;
    private final ClanRoleRepository roles;
    private final ClanTreasuryRepository treasury;

    PaperClanCommand(
            MinecraftServerPlugin plugin,
            PaperPlayerIdentityResolver playerIdentities,
            ClanMembershipRepository memberships,
            ClanRoleRepository roles,
            ClanTreasuryRepository treasury
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.playerIdentities = Objects.requireNonNull(playerIdentities, "playerIdentities");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.treasury = Objects.requireNonNull(treasury, "treasury");
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
            return List.of("status", "create", "invite", "accept", "leave", "kick", "role", "transfer", "treasury")
                    .stream().filter(value -> value.startsWith(prefix)).toList();
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
        if (args.length == 3 && args[0].equalsIgnoreCase("role")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return List.of("member", "officer").stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("transfer")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return List.of("member", "officer").stream().filter(value -> value.startsWith(prefix)).toList();
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
                sendIfOnline(actorMinecraftUuid, "Invited " + target.getName() + " to [" + clan.tag() + "] " + clan.name() + ".");
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

    private void handleAsyncFailure(UUID minecraftUuid, String fallback, Throwable failure) {
        if (failure instanceof ClanMembershipException || failure instanceof ClanAssetException || failure instanceof IllegalArgumentException) {
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

    private void usage(Player player) {
        player.sendMessage(Component.text("Clan: /clan [status] | create <tag> <name> | invite <player> | accept <invite-id> | leave"));
        player.sendMessage(Component.text("      /clan kick <player> | role <player> <member|officer> | transfer <player> [member|officer]"));
        player.sendMessage(Component.text("      /clan treasury [deposit <coins>|withdraw <coins>]"));
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

    private static String formatCoin(long amountMinor) {
        long whole = amountMinor / CoinCurrency.MINOR_UNITS_PER_COIN;
        long fraction = amountMinor % CoinCurrency.MINOR_UNITS_PER_COIN;
        return String.format(Locale.ROOT, "%d.%02d Coin", whole, fraction);
    }

    private static String playerMessage(Throwable exception, String fallback) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    @FunctionalInterface
    private interface PlayerPairAction {
        void run(UUID actorMinecraftUuid, UUID targetMinecraftUuid);
    }
}

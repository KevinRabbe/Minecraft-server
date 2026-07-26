package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.clan.ClanInvitationSnapshot;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanInvitationView;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanMemberSnapshot;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanMemberView;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanMembershipException;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanMembershipRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanQueryRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanRole;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;

/** Adds bounded clan social/war subcommands while delegating the established clan mutation/storage surface. */
final class PaperClanRouterCommand implements CommandExecutor, TabCompleter {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_CHAT_LIMIT = 20;

    private final MinecraftServerPlugin plugin;
    private final PaperClanCommand delegate;
    private final PaperClanWarCommand warCommand;
    private final PaperPlayerIdentityResolver playerIdentities;
    private final ClanMembershipRepository memberships;
    private final ClanQueryRepository queries;

    PaperClanRouterCommand(
            MinecraftServerPlugin plugin,
            PaperClanCommand delegate,
            PaperClanWarCommand warCommand,
            PaperPlayerIdentityResolver playerIdentities,
            ClanMembershipRepository memberships,
            ClanQueryRepository queries
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.warCommand = Objects.requireNonNull(warCommand, "warCommand");
        this.playerIdentities = Objects.requireNonNull(playerIdentities, "playerIdentities");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return delegate.onCommand(sender, command, label, args);
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (!List.of("members", "invites", "cancel-invite", "war").contains(subcommand)) {
            return delegate.onCommand(sender, command, label, args);
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can use clan commands."));
            return true;
        }
        if ("war".equals(subcommand)) {
            return warCommand.onCommand(player, Arrays.copyOfRange(args, 1, args.length));
        }

        try {
            switch (subcommand) {
                case "members" -> {
                    if (args.length > 2) {
                        usage(player);
                    } else {
                        int limit = args.length == 2 ? parseLimit(args[1]) : DEFAULT_LIMIT;
                        scheduleMembers(player.getUniqueId(), limit);
                    }
                }
                case "invites" -> {
                    if (args.length > 2) {
                        usage(player);
                    } else {
                        int limit = args.length == 2 ? parseLimit(args[1]) : DEFAULT_LIMIT;
                        scheduleInvites(player.getUniqueId(), limit);
                    }
                }
                case "cancel-invite" -> {
                    if (args.length != 2) {
                        usage(player);
                    } else {
                        scheduleCancelInvite(player.getUniqueId(), parseUuid(args[1]));
                    }
                }
                default -> throw new IllegalStateException("unreachable clan router subcommand");
            }
        } catch (IllegalArgumentException exception) {
            player.sendMessage(Component.text(playerMessage(exception, "Invalid clan query arguments.")));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            LinkedHashSet<String> result = new LinkedHashSet<>(delegate.onTabComplete(sender, command, alias, args));
            List.of("members", "invites", "cancel-invite", "war").stream()
                    .filter(value -> value.startsWith(prefix))
                    .forEach(result::add);
            return result.stream().sorted().toList();
        }
        if ("war".equals(args[0].toLowerCase(Locale.ROOT))) {
            return warCommand.onTabComplete(Arrays.copyOfRange(args, 1, args.length));
        }
        if (args.length >= 2 && List.of("members", "invites", "cancel-invite").contains(args[0].toLowerCase(Locale.ROOT))) {
            return List.of();
        }
        return delegate.onTabComplete(sender, command, alias, args);
    }

    private void scheduleMembers(UUID minecraftUuid, int limit) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                ClanMemberSnapshot caller = memberships.loadMember(playerId);
                List<ClanMemberView> members = queries.listMembers(caller.clanId(), limit);
                ArrayList<String> messages = new ArrayList<>();
                messages.add("Clan members: " + members.size() + " shown.");
                for (ClanMemberView member : members) {
                    messages.add("- [" + member.role() + "] " + member.playerName());
                }
                sendMessagesIfOnline(minecraftUuid, messages);
            } catch (SQLException | RuntimeException exception) {
                handleFailure(minecraftUuid, "Could not list clan members.", exception);
            }
        });
    }

    private void scheduleInvites(UUID minecraftUuid, int limit) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                ClanMemberSnapshot caller = memberships.loadMember(playerId);
                requireInviteManager(caller);
                List<ClanInvitationView> invites = queries.listPendingInvitations(caller.clanId(), limit);
                ArrayList<String> messages = new ArrayList<>();
                messages.add("Pending clan invites: " + invites.size() + " shown.");
                if (invites.isEmpty()) {
                    messages.add("- none");
                } else {
                    for (ClanInvitationView invitation : invites) {
                        messages.add(
                                "- " + invitation.invitedPlayerName()
                                        + " — id " + invitation.inviteId()
                                        + " — by " + invitation.invitedByPlayerName()
                                        + " — expires " + invitation.expiresAt()
                        );
                    }
                }
                sendMessagesIfOnline(minecraftUuid, messages);
            } catch (SQLException | RuntimeException exception) {
                handleFailure(minecraftUuid, "Could not list clan invitations.", exception);
            }
        });
    }

    private void scheduleCancelInvite(UUID minecraftUuid, UUID inviteId) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                ClanInvitationSnapshot cancelled = memberships.cancelInvite(UUID.randomUUID(), inviteId, playerId);
                sendIfOnline(
                        minecraftUuid,
                        "Cancelled clan invitation " + cancelled.inviteId() + " for player "
                                + cancelled.invitedPlayerId() + "."
                );
            } catch (SQLException | RuntimeException exception) {
                handleFailure(minecraftUuid, "Could not cancel clan invitation.", exception);
            }
        });
    }

    private UUID requirePlayerId(UUID minecraftUuid) throws SQLException {
        return playerIdentities.resolve(minecraftUuid).orElseThrow(
                () -> new ClanMembershipException("Persistent player identity is not available.")
        );
    }

    private static void requireInviteManager(ClanMemberSnapshot member) {
        if (member.role() != ClanRole.LEADER && member.role() != ClanRole.OFFICER) {
            throw new ClanMembershipException("Only LEADER or OFFICER may view pending clan invitations.");
        }
    }

    private void handleFailure(UUID minecraftUuid, String fallback, Throwable failure) {
        if (failure instanceof ClanMembershipException || failure instanceof IllegalArgumentException) {
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
            plugin.getLogger().log(Level.WARNING, "Could not schedule clan query work", exception);
        }
    }

    private void sendIfOnline(UUID minecraftUuid, String message) {
        sendMessagesIfOnline(minecraftUuid, List.of(message));
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
                "Clan: /clan members [1-20] | invites [1-20] | cancel-invite <invite-id> | war ..."
        ));
    }

    private static int parseLimit(String raw) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1 || value > MAX_CHAT_LIMIT) {
                throw new IllegalArgumentException("limit must be between 1 and " + MAX_CHAT_LIMIT);
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
            throw new IllegalArgumentException("invite ID must be a valid UUID", exception);
        }
    }

    private static String playerMessage(Throwable exception, String fallback) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }
}

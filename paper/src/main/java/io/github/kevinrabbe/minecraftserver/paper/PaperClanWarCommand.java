package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.clan.ClanMemberSnapshot;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanMemberView;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanMembershipException;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanMembershipRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanQueryRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarException;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarLifecycleRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarQueryRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarResolutionRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarTerminalResult;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarView;
import net.kyori.adventure.text.Component;
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

/** Thin Paper adapter for player-authorized Clan-War lifecycle steps; trusted lock/start/settlement remain control-side. */
final class PaperClanWarCommand {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_CHAT_LIMIT = 20;

    private final MinecraftServerPlugin plugin;
    private final PaperPlayerIdentityResolver playerIdentities;
    private final ClanMembershipRepository memberships;
    private final ClanQueryRepository clanQueries;
    private final ClanWarLifecycleRepository wars;
    private final ClanWarQueryRepository warQueries;
    private final ClanWarResolutionRepository resolutions;

    PaperClanWarCommand(
            MinecraftServerPlugin plugin,
            PaperPlayerIdentityResolver playerIdentities,
            ClanMembershipRepository memberships,
            ClanQueryRepository clanQueries,
            ClanWarLifecycleRepository wars,
            ClanWarQueryRepository warQueries,
            ClanWarResolutionRepository resolutions
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.playerIdentities = Objects.requireNonNull(playerIdentities, "playerIdentities");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.clanQueries = Objects.requireNonNull(clanQueries, "clanQueries");
        this.wars = Objects.requireNonNull(wars, "wars");
        this.warQueries = Objects.requireNonNull(warQueries, "warQueries");
        this.resolutions = Objects.requireNonNull(resolutions, "resolutions");
    }

    boolean onCommand(Player player, String[] args) {
        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (action) {
                case "list" -> {
                    if (args.length > 2) usage(player);
                    else scheduleList(player.getUniqueId(), args.length == 2 ? parseLimit(args[1]) : DEFAULT_LIMIT);
                }
                case "status" -> {
                    if (args.length != 2) usage(player);
                    else scheduleStatus(player.getUniqueId(), parseWarId(args[1]));
                }
                case "challenge" -> {
                    if (args.length != 2) usage(player);
                    else scheduleChallenge(player.getUniqueId(), args[1]);
                }
                case "accept" -> {
                    if (args.length != 2) usage(player);
                    else scheduleAccept(player.getUniqueId(), parseWarId(args[1]));
                }
                case "roster" -> {
                    if (args.length < 3) usage(player);
                    else scheduleRoster(
                            player.getUniqueId(),
                            parseWarId(args[1]),
                            Arrays.asList(Arrays.copyOfRange(args, 2, args.length))
                    );
                }
                case "cancel" -> {
                    if (args.length != 2) usage(player);
                    else scheduleCancel(player.getUniqueId(), parseWarId(args[1]));
                }
                default -> usage(player);
            }
        } catch (IllegalArgumentException exception) {
            player.sendMessage(Component.text(playerMessage(exception, "Invalid Clan-War arguments.")));
        }
        return true;
    }

    List<String> onTabComplete(String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("list", "status", "challenge", "accept", "roster", "cancel").stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    private void scheduleList(UUID minecraftUuid, int limit) {
        runAsync(() -> {
            try {
                ClanMemberSnapshot caller = requireCaller(minecraftUuid);
                List<ClanWarView> openWars = warQueries.listOpenForClan(caller.clanId(), limit);
                ArrayList<String> messages = new ArrayList<>();
                messages.add("Open Clan Wars: " + openWars.size() + " shown.");
                if (openWars.isEmpty()) {
                    messages.add("- none");
                } else {
                    for (ClanWarView war : openWars) {
                        messages.add(summary(war));
                    }
                }
                sendMessagesIfOnline(minecraftUuid, messages);
            } catch (SQLException | RuntimeException exception) {
                handleFailure(minecraftUuid, "Could not list Clan Wars.", exception);
            }
        });
    }

    private void scheduleStatus(UUID minecraftUuid, UUID warId) {
        runAsync(() -> {
            try {
                ClanMemberSnapshot caller = requireCaller(minecraftUuid);
                ClanWarView war = warQueries.load(warId).orElseThrow(
                        () -> new ClanWarException("Unknown Clan War: " + warId)
                );
                requireParticipantClan(war, caller.clanId());
                sendMessagesIfOnline(minecraftUuid, List.of(
                        summary(war),
                        "Ruleset: " + war.rulesetId() + "@" + war.rulesetVersion(),
                        "Rosters: [" + war.challengerTag() + "] " + war.challengerRosterCount() + "/" + war.teamSize()
                                + " vs [" + war.defenderTag() + "] " + war.defenderRosterCount() + "/" + war.teamSize()
                ));
            } catch (SQLException | RuntimeException exception) {
                handleFailure(minecraftUuid, "Could not load Clan-War status.", exception);
            }
        });
    }

    private void scheduleChallenge(UUID minecraftUuid, String defenderTag) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                ClanMemberSnapshot caller = memberships.loadMember(playerId);
                UUID defenderClanId = warQueries.findClanIdByTag(defenderTag).orElseThrow(
                        () -> new ClanWarException("Unknown clan tag: " + defenderTag)
                );
                ClanWarSnapshot war = wars.challenge(
                        UUID.randomUUID(),
                        playerId,
                        caller.clanId(),
                        defenderClanId
                );
                sendIfOnline(
                        minecraftUuid,
                        "Clan War challenged: " + war.warId() + ". The defending clan must accept it."
                );
            } catch (SQLException | RuntimeException exception) {
                handleFailure(minecraftUuid, "Could not challenge that clan.", exception);
            }
        });
    }

    private void scheduleAccept(UUID minecraftUuid, UUID warId) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                ClanWarSnapshot war = wars.accept(UUID.randomUUID(), warId, playerId);
                sendIfOnline(
                        minecraftUuid,
                        "Accepted Clan War " + war.warId() + ". Both clans may now set their exact rosters."
                );
            } catch (SQLException | RuntimeException exception) {
                handleFailure(minecraftUuid, "Could not accept that Clan War.", exception);
            }
        });
    }

    private void scheduleRoster(UUID minecraftUuid, UUID warId, List<String> memberNames) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                ClanMemberSnapshot caller = memberships.loadMember(playerId);
                LinkedHashSet<UUID> rosterIds = new LinkedHashSet<>();
                for (String memberName : memberNames) {
                    ClanMemberView member = clanQueries.findMemberByCurrentName(caller.clanId(), memberName).orElseThrow(
                            () -> new ClanWarException("No current clan member named " + memberName)
                    );
                    if (!rosterIds.add(member.playerId())) {
                        throw new ClanWarException("Clan-War roster contains duplicate player " + member.playerName());
                    }
                }
                wars.setRoster(
                        UUID.randomUUID(),
                        warId,
                        playerId,
                        caller.clanId(),
                        List.copyOf(rosterIds)
                );
                sendIfOnline(
                        minecraftUuid,
                        "Updated your clan roster for war " + warId + " with " + rosterIds.size() + " player(s)."
                );
            } catch (SQLException | RuntimeException exception) {
                handleFailure(minecraftUuid, "Could not update the Clan-War roster.", exception);
            }
        });
    }

    private void scheduleCancel(UUID minecraftUuid, UUID warId) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                ClanWarTerminalResult result = resolutions.cancel(UUID.randomUUID(), warId, playerId);
                sendIfOnline(
                        minecraftUuid,
                        "Cancelled Clan War " + result.war().warId()
                                + "; " + result.returnDeliveryIds().size() + " custody item(s) queued for return."
                );
            } catch (SQLException | RuntimeException exception) {
                handleFailure(minecraftUuid, "Could not cancel that Clan War.", exception);
            }
        });
    }

    private ClanMemberSnapshot requireCaller(UUID minecraftUuid) throws SQLException {
        return memberships.loadMember(requirePlayerId(minecraftUuid));
    }

    private UUID requirePlayerId(UUID minecraftUuid) throws SQLException {
        return playerIdentities.resolve(minecraftUuid).orElseThrow(
                () -> new ClanMembershipException("Persistent player identity is not available.")
        );
    }

    private static void requireParticipantClan(ClanWarView war, UUID clanId) {
        if (!clanId.equals(war.challengerClanId()) && !clanId.equals(war.defenderClanId())) {
            throw new ClanWarException("Clan War is not associated with your clan.");
        }
    }

    private static String summary(ClanWarView war) {
        return "- " + war.warId() + " — " + war.status()
                + " — [" + war.challengerTag() + "] " + war.challengerName()
                + " vs [" + war.defenderTag() + "] " + war.defenderName();
    }

    private void handleFailure(UUID minecraftUuid, String fallback, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof ClanWarException
                || cause instanceof ClanMembershipException
                || cause instanceof IllegalArgumentException) {
            sendIfOnline(minecraftUuid, playerMessage(cause, fallback));
            return;
        }
        plugin.getLogger().log(Level.WARNING, fallback, cause);
        sendIfOnline(minecraftUuid, fallback);
    }

    private void runAsync(Runnable task) {
        if (!plugin.isEnabled()) return;
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule Clan-War work", exception);
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

    private static UUID parseWarId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("war ID must be a valid UUID", exception);
        }
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

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private static String playerMessage(Throwable exception, String fallback) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private static void usage(Player player) {
        player.sendMessage(Component.text(
                "Clan War: /clan war [list [1-20]|status <war-id>|challenge <tag>|accept <war-id>"
                        + "|roster <war-id> <member...>|cancel <war-id>]"
        ));
    }
}

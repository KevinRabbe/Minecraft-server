package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.clan.ClanMemberSnapshot;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanMemberView;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanMembershipException;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanMembershipRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanQueryRepository;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationClaim;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarCustodiedItemSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarCustodyDepositResult;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarException;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarLifecycleRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarLoadoutConfirmation;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarLoadoutReadinessRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarLoadoutRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarQueryRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarResolutionRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarTerminalResult;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarView;
import io.github.kevinrabbe.minecraftserver.common.session.SessionConflictException;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/** Thin Paper adapter for player-authorized Clan-War preparation; trusted lock/start/settlement remain control-side. */
final class PaperClanWarCommand {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_CHAT_LIMIT = 20;
    private static final String LOADOUT_DEPOSIT_REASON = "clan.war_player_loadout_deposit";

    private final MinecraftServerPlugin plugin;
    private final PaperSessionController sessions;
    private final PaperPlayerIdentityResolver playerIdentities;
    private final ClanMembershipRepository memberships;
    private final ClanQueryRepository clanQueries;
    private final ClanWarLifecycleRepository wars;
    private final ClanWarQueryRepository warQueries;
    private final ClanWarResolutionRepository resolutions;
    private final ClanWarLoadoutRepository loadouts;
    private final ClanWarLoadoutReadinessRepository readiness;
    private final ItemCatalog itemCatalog;
    private final PaperUniqueItemStateRemovalMutator uniqueItemRemoval;
    private final PaperItemIdentityCodec identityCodec;

    PaperClanWarCommand(
            MinecraftServerPlugin plugin,
            PaperSessionController sessions,
            PaperPlayerIdentityResolver playerIdentities,
            ClanMembershipRepository memberships,
            ClanQueryRepository clanQueries,
            ClanWarLifecycleRepository wars,
            ClanWarQueryRepository warQueries,
            ClanWarResolutionRepository resolutions,
            ClanWarLoadoutRepository loadouts,
            ClanWarLoadoutReadinessRepository readiness,
            ItemCatalog itemCatalog,
            PaperUniqueItemStateRemovalMutator uniqueItemRemoval
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.playerIdentities = Objects.requireNonNull(playerIdentities, "playerIdentities");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.clanQueries = Objects.requireNonNull(clanQueries, "clanQueries");
        this.wars = Objects.requireNonNull(wars, "wars");
        this.warQueries = Objects.requireNonNull(warQueries, "warQueries");
        this.resolutions = Objects.requireNonNull(resolutions, "resolutions");
        this.loadouts = Objects.requireNonNull(loadouts, "loadouts");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.uniqueItemRemoval = Objects.requireNonNull(uniqueItemRemoval, "uniqueItemRemoval");
        this.identityCodec = new PaperItemIdentityCodec(plugin);
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
                case "loadout" -> {
                    if (args.length != 2) usage(player);
                    else scheduleLoadout(player.getUniqueId(), parseWarId(args[1]));
                }
                case "deposit" -> {
                    if (args.length != 2) usage(player);
                    else depositLoadoutItem(player, parseWarId(args[1]));
                }
                case "ready" -> {
                    if (args.length != 2) usage(player);
                    else scheduleReady(player.getUniqueId(), parseWarId(args[1]));
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
            return List.of(
                            "list", "status", "challenge", "accept", "roster", "loadout", "deposit", "ready", "cancel"
                    ).stream()
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
                    for (ClanWarView war : openWars) messages.add(summary(war));
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
                ClanWarView war = requireParticipantWar(warId, caller.clanId());
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
                ClanWarSnapshot war = wars.challenge(UUID.randomUUID(), playerId, caller.clanId(), defenderClanId);
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
                wars.setRoster(UUID.randomUUID(), warId, playerId, caller.clanId(), List.copyOf(rosterIds));
                sendIfOnline(
                        minecraftUuid,
                        "Updated your clan roster for war " + warId + " with " + rosterIds.size() + " player(s)."
                );
            } catch (SQLException | RuntimeException exception) {
                handleFailure(minecraftUuid, "Could not update the Clan-War roster.", exception);
            }
        });
    }

    private void scheduleLoadout(UUID minecraftUuid, UUID warId) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                ClanMemberSnapshot caller = memberships.loadMember(playerId);
                requireParticipantWar(warId, caller.clanId());
                List<ClanWarCustodiedItemSnapshot> items = loadouts.loadActiveCombatSnapshot(warId).stream()
                        .filter(item -> item.playerId().equals(playerId))
                        .toList();
                boolean finalized = readiness.load(warId, playerId).isPresent();

                ArrayList<String> messages = new ArrayList<>();
                messages.add("Clan-War loadout: " + items.size() + " item(s) — finalized: " + finalized + ".");
                if (items.isEmpty()) {
                    messages.add("- no individualized gear selected");
                } else {
                    for (ClanWarCustodiedItemSnapshot item : items) {
                        messages.add(
                                "- " + displayName(item.definitionId())
                                        + " {" + item.itemInstanceId() + " @v" + item.itemStateVersion()
                                        + ", upgrade " + item.upgradeLevel() + "}"
                        );
                    }
                }
                if (!finalized) {
                    messages.add("Use /clan war ready " + warId + " when your current selection is final.");
                }
                sendMessagesIfOnline(minecraftUuid, messages);
            } catch (SQLException | RuntimeException exception) {
                handleFailure(minecraftUuid, "Could not load your Clan-War loadout.", exception);
            }
        });
    }

    private void depositLoadoutItem(Player player, UUID warId) {
        UUID minecraftUuid = player.getUniqueId();
        if (sessions.isMutationFrozen(minecraftUuid)) {
            player.sendMessage(Component.text("Your persistent state is busy. Try again shortly."));
            return;
        }

        final UniqueClaim claim;
        try {
            claim = requireUniqueClaim(player.getInventory().getItemInMainHand());
        } catch (PaperItemRepresentationException | IllegalArgumentException exception) {
            player.sendMessage(Component.text(playerMessage(exception, "Hold one managed unique item in your main hand.")));
            return;
        }

        AtomicReference<ClanWarCustodyDepositResult> committed = new AtomicReference<>();
        sessions.mutateAuthoritativeState(player, context -> {
            byte[] nextPayload = uniqueItemRemoval.remove(
                    context.playerId(),
                    claim.itemInstanceId(),
                    claim.authorityVersion(),
                    context.currentStatePayload()
            );
            ClanWarCustodyDepositResult result = loadouts.depositPlayerItem(
                    UUID.randomUUID(),
                    warId,
                    context.sessionId(),
                    context.backendId(),
                    context.stateVersion(),
                    claim.itemInstanceId(),
                    claim.authorityVersion(),
                    context.logicalZoneId(),
                    context.entryPoint(),
                    nextPayload,
                    LOADOUT_DEPOSIT_REASON
            );
            committed.set(result);
            return new PaperAuthoritativeStateMutation.Result(result.playerStateVersion(), nextPayload);
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                handleMutationFailure(minecraftUuid, "Could not escrow that item for the Clan War.", failure);
                return;
            }
            ClanWarCustodyDepositResult result = committed.get();
            if (result == null) {
                plugin.getLogger().severe("Clan-War custody deposit committed without captured result");
                return;
            }
            sendIfOnline(
                    minecraftUuid,
                    "Escrowed " + displayName(claim.definitionId()) + " into war " + warId
                            + " as " + result.item().itemInstanceId() + " @v" + result.item().itemStateVersion()
                            + ". Your loadout is not finalized until /clan war ready " + warId + "."
            );
        });
    }

    private void scheduleReady(UUID minecraftUuid, UUID warId) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                ClanWarLoadoutConfirmation confirmation = readiness.confirm(UUID.randomUUID(), warId, playerId);
                sendIfOnline(
                        minecraftUuid,
                        "Finalized your current Clan-War loadout for " + confirmation.warId()
                                + " at " + confirmation.confirmedAt() + "."
                );
            } catch (SQLException | RuntimeException exception) {
                handleFailure(minecraftUuid, "Could not finalize your Clan-War loadout.", exception);
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

    private UniqueClaim requireUniqueClaim(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("Hold one unique item in your main hand to escrow it for the war.");
        }
        Optional<ItemRepresentationClaim> optional = identityCodec.readClaim(stack, "main_hand");
        if (optional.isEmpty()) {
            throw new IllegalArgumentException("The main-hand item is not a managed server item.");
        }
        ItemRepresentationClaim claim = optional.orElseThrow();
        if (!claim.individualClaim() || claim.itemInstanceId() == null || claim.authorityVersion() == null
                || claim.amount() != 1) {
            throw new IllegalArgumentException("Only individualized one-of-one items can enter Clan-War custody.");
        }
        ItemDefinition definition = itemCatalog.find(claim.definitionId()).orElseThrow(
                () -> new PaperItemRepresentationException("The main-hand item has an unknown definition.")
        );
        if (definition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
            throw new IllegalArgumentException("Only individualized items can enter Clan-War custody.");
        }
        if (!definition.minecraftMaterial().equals(claim.minecraftMaterial())) {
            throw new PaperItemRepresentationException("The main-hand item material does not match its definition.");
        }
        return new UniqueClaim(claim.itemInstanceId(), claim.authorityVersion(), claim.definitionId());
    }

    private ClanMemberSnapshot requireCaller(UUID minecraftUuid) throws SQLException {
        return memberships.loadMember(requirePlayerId(minecraftUuid));
    }

    private UUID requirePlayerId(UUID minecraftUuid) throws SQLException {
        return playerIdentities.resolve(minecraftUuid).orElseThrow(
                () -> new ClanMembershipException("Persistent player identity is not available.")
        );
    }

    private ClanWarView requireParticipantWar(UUID warId, UUID clanId) throws SQLException {
        ClanWarView war = warQueries.load(warId).orElseThrow(
                () -> new ClanWarException("Unknown Clan War: " + warId)
        );
        requireParticipantClan(war, clanId);
        return war;
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

    private String displayName(String definitionId) {
        return itemCatalog.require(definitionId).displayName();
    }

    private void handleMutationFailure(UUID minecraftUuid, String fallback, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof SessionConflictException) {
            sendIfOnline(minecraftUuid, "Your persistent state changed. Review your Clan-War loadout and try again.");
            return;
        }
        if (cause instanceof ClanWarException
                || cause instanceof ClanMembershipException
                || cause instanceof PaperItemRepresentationException
                || cause instanceof IllegalArgumentException) {
            sendIfOnline(minecraftUuid, playerMessage(cause, fallback));
            return;
        }
        plugin.getLogger().log(Level.WARNING, fallback, cause);
        sendIfOnline(minecraftUuid, fallback);
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
                        + "|roster <war-id> <member...>|loadout <war-id>|deposit <war-id>|ready <war-id>|cancel <war-id>]"
        ));
    }

    private record UniqueClaim(UUID itemInstanceId, long authorityVersion, String definitionId) { }
}

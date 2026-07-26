package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.world.ExpansionBallot;
import io.github.kevinrabbe.minecraftserver.common.world.ExpansionCandidate;
import io.github.kevinrabbe.minecraftserver.common.world.ExpansionVoteException;
import io.github.kevinrabbe.minecraftserver.common.world.ExpansionVoteQueryRepository;
import io.github.kevinrabbe.minecraftserver.common.world.ExpansionVoteRepository;
import io.github.kevinrabbe.minecraftserver.common.world.ExpansionVoteView;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;

/** Player-facing world-expansion ballots; scheduling/resolution remain separate authoritative operations. */
final class PaperExpansionVoteCommand implements CommandExecutor, TabCompleter {
    private static final int OPEN_VOTE_LIMIT = 5;
    private static final String CAST_REASON = "vote.paper_cast";

    private final JavaPlugin plugin;
    private final PaperPlayerIdentityResolver identities;
    private final ExpansionVoteRepository votes;
    private final ExpansionVoteQueryRepository queries;

    private PaperExpansionVoteCommand(
            JavaPlugin plugin,
            PaperPlayerIdentityResolver identities,
            ExpansionVoteRepository votes,
            ExpansionVoteQueryRepository queries
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.votes = Objects.requireNonNull(votes, "votes");
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    static void install(JavaPlugin plugin, javax.sql.DataSource dataSource) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(dataSource, "dataSource");
        PluginCommand command = Objects.requireNonNull(plugin.getCommand("vote"), "vote command missing from plugin.yml");
        PaperExpansionVoteCommand executor = new PaperExpansionVoteCommand(
                plugin,
                new PaperPlayerIdentityResolver(dataSource),
                new ExpansionVoteRepository(dataSource),
                new ExpansionVoteQueryRepository(dataSource)
        );
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        PaperExpansionVoteLifecycle.schedule(plugin, dataSource);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can use expansion voting."));
            return true;
        }
        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("list"))) {
            scheduleList(player.getUniqueId());
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("cast")) {
            final UUID voteId;
            try {
                voteId = UUID.fromString(args[1]);
            } catch (IllegalArgumentException exception) {
                player.sendMessage(Component.text("Vote ID must be a UUID shown by /vote."));
                return true;
            }
            scheduleCast(player.getUniqueId(), voteId, args[2]);
            return true;
        }
        usage(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("list", "cast").stream().filter(value -> value.startsWith(prefix)).toList();
        }
        return List.of();
    }

    private void scheduleList(UUID minecraftUuid) {
        runAsync(() -> {
            try {
                UUID playerId = identities.resolve(minecraftUuid).orElse(null);
                if (playerId == null) {
                    sendIfOnline(minecraftUuid, List.of("Your persistent player identity is not ready yet."));
                    return;
                }
                List<ExpansionVoteView> open = queries.listOpen(playerId, OPEN_VOTE_LIMIT);
                ArrayList<String> messages = new ArrayList<>();
                if (open.isEmpty()) {
                    messages.add("No world-expansion vote is currently open.");
                } else {
                    messages.add("World expansion — open vote" + (open.size() == 1 ? "" : "s") + ":");
                    for (ExpansionVoteView view : open) {
                        messages.add("Vote " + view.vote().voteId() + " — closes " + view.vote().closesAt());
                        for (ExpansionCandidate candidate : view.candidates()) {
                            String era = candidate.resultingWorldEraId() == null
                                    ? ""
                                    : " · era " + candidate.resultingWorldEraId().value();
                            messages.add("  " + candidate.candidateId() + " — " + candidate.displayName()
                                    + " · features " + String.join(", ", candidate.featureIds()) + era);
                        }
                        ExpansionBallot ballot = view.ballot();
                        messages.add(ballot == null
                                ? "  Your ballot: not cast"
                                : "  Your ballot: " + ballot.candidateId());
                    }
                    messages.add("Cast/change: /vote cast <vote-id> <candidate-id>");
                }
                sendIfOnline(minecraftUuid, messages);
            } catch (SQLException | RuntimeException exception) {
                handleFailure(minecraftUuid, "Could not load expansion votes.", exception);
            }
        });
    }

    private void scheduleCast(UUID minecraftUuid, UUID voteId, String candidateId) {
        runAsync(() -> {
            try {
                UUID playerId = identities.resolve(minecraftUuid).orElseThrow(
                        () -> new ExpansionVoteException("Your persistent player identity is not ready yet.")
                );
                ExpansionBallot ballot = votes.castBallot(
                        UUID.randomUUID(),
                        voteId,
                        playerId,
                        candidateId,
                        CAST_REASON
                );
                sendIfOnline(minecraftUuid, List.of(
                        "Expansion ballot recorded: " + ballot.candidateId() + " for vote " + ballot.voteId() + "."
                ));
            } catch (SQLException | RuntimeException exception) {
                handleFailure(minecraftUuid, "Could not record expansion ballot.", exception);
            }
        });
    }

    private void handleFailure(UUID minecraftUuid, String fallback, Throwable failure) {
        if (!(failure instanceof ExpansionVoteException || failure instanceof IllegalArgumentException)) {
            plugin.getLogger().log(Level.WARNING, fallback, failure);
        }
        String message = failure.getMessage();
        sendIfOnline(minecraftUuid, List.of(message == null || message.isBlank() ? fallback : message));
    }

    private void runAsync(Runnable task) {
        if (!plugin.isEnabled()) return;
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule expansion vote work", exception);
        }
    }

    private void sendIfOnline(UUID minecraftUuid, List<String> messages) {
        if (!plugin.isEnabled()) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(minecraftUuid);
            if (player == null || !player.isOnline()) return;
            messages.forEach(message -> player.sendMessage(Component.text(message)));
        });
    }

    private static void usage(Player player) {
        player.sendMessage(Component.text("Expansion vote: /vote [list] | /vote cast <vote-id> <candidate-id>"));
    }
}

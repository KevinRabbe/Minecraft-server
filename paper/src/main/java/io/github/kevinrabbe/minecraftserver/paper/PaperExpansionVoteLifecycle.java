package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.world.ExpansionVoteLifecycleAdvanceResult;
import io.github.kevinrabbe.minecraftserver.common.world.ExpansionVoteLifecycleQueryRepository;
import io.github.kevinrabbe.minecraftserver.common.world.ExpansionVoteLifecycleService;
import io.github.kevinrabbe.minecraftserver.common.world.ExpansionVoteRepository;
import org.bukkit.plugin.java.JavaPlugin;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Objects;
import java.util.logging.Level;

/** Periodically advances configured expansion votes; safe to run on every Paper backend. */
final class PaperExpansionVoteLifecycle {
    private static final int BATCH_LIMIT = 20;
    private static final long INITIAL_DELAY_TICKS = 20L;
    private static final long PERIOD_TICKS = 200L;

    private PaperExpansionVoteLifecycle() { }

    static void schedule(JavaPlugin plugin, DataSource dataSource) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(dataSource, "dataSource");
        ExpansionVoteLifecycleService lifecycle = new ExpansionVoteLifecycleService(
                new ExpansionVoteLifecycleQueryRepository(dataSource),
                new ExpansionVoteRepository(dataSource),
                BATCH_LIMIT
        );
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin,
                () -> advance(plugin, lifecycle),
                INITIAL_DELAY_TICKS,
                PERIOD_TICKS
        );
    }

    private static void advance(JavaPlugin plugin, ExpansionVoteLifecycleService lifecycle) {
        try {
            ExpansionVoteLifecycleAdvanceResult result = lifecycle.advanceOnce();
            if (result.transitioned() > 0) {
                plugin.getLogger().info(() -> "Advanced expansion votes: opened=" + result.opened()
                        + ", resolved=" + result.resolved());
            }
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Expansion vote lifecycle pass failed", exception);
        }
    }
}

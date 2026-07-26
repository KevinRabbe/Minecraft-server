package io.github.kevinrabbe.minecraftserver.competitivecontrol;

import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarLifecycleRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarResolutionRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarRuleset;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveExecutionRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveExecutionService;
import io.github.kevinrabbe.minecraftserver.common.pvp.RankedArenaRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.RankedArenaRuleset;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Standalone trusted control-plane process for competitive report settlement and expired-execution recovery. */
public final class CompetitiveControlMain {
    private static final Logger LOGGER = Logger.getLogger(CompetitiveControlMain.class.getName());

    private CompetitiveControlMain() { }

    public static void main(String[] args) throws InterruptedException {
        CompetitiveControlConfig config = CompetitiveControlConfig.fromEnvironment();
        CountDownLatch shutdown = new CountDownLatch(1);
        AtomicBoolean stopping = new AtomicBoolean();
        Thread shutdownHook = new Thread(() -> {
            if (stopping.compareAndSet(false, true)) {
                shutdown.countDown();
            }
        }, "competitive-control-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "competitive-control-worker");
            thread.setDaemon(false);
            return thread;
        });

        try (Database database = Database.open(DatabaseConfig.fromEnvironment())) {
            database.migrate();

            CompetitiveExecutionRepository executions = new CompetitiveExecutionRepository(
                    database.dataSource(),
                    config.backendFreshness(),
                    config.maxExecutionLease()
            );
            CompetitiveExecutionService service = new CompetitiveExecutionService(
                    executions,
                    new RankedArenaRepository(database.dataSource(), RankedArenaRuleset.legacy189V1()),
                    new ClanWarLifecycleRepository(database.dataSource(), ClanWarRuleset.legacy189V1()),
                    new ClanWarResolutionRepository(database.dataSource())
            );
            CompetitiveControlWorker worker = new CompetitiveControlWorker(
                    executions,
                    service,
                    config.batchLimit(),
                    LOGGER
            );

            LOGGER.info(() -> "Started competitive control worker: batchLimit=" + config.batchLimit()
                    + ", pollPeriod=" + config.pollPeriod()
                    + ", backendFreshness=" + config.backendFreshness()
                    + ", maxExecutionLease=" + config.maxExecutionLease());

            scheduler.scheduleWithFixedDelay(
                    () -> runPass(worker),
                    0,
                    config.pollPeriod().toMillis(),
                    TimeUnit.MILLISECONDS
            );

            shutdown.await();
        } finally {
            stopping.set(true);
            scheduler.shutdown();
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            }
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown is already in progress.
            }
            LOGGER.info("Competitive control worker stopped");
        }
    }

    private static void runPass(CompetitiveControlWorker worker) {
        try {
            CompetitiveControlPassResult result = worker.runOnce();
            if (result.transitions() > 0 || result.failures() > 0) {
                Level level = result.failures() == 0 ? Level.INFO : Level.WARNING;
                LOGGER.log(
                        level,
                        "Competitive control pass: reports=" + result.reportsApplied() + "/" + result.pendingReportsSeen()
                                + ", recovered=" + result.executionsRecovered() + "/" + result.expiredExecutionsSeen()
                                + ", failures=" + result.failures()
                );
            }
        } catch (SQLException | RuntimeException exception) {
            // A failed scan must not terminate future fixed-delay passes.
            LOGGER.log(Level.WARNING, "Competitive control scan failed", exception);
        }
    }
}

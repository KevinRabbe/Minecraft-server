package io.github.kevinrabbe.minecraftserver.legacy;

import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Minecraft 1.8.9 runtime shell. It sees only sanitized execution manifests and can emit only WINNER/FAILURE reports.
 * Persistent ratings, Coin, inventory, unique item identity and custody remain outside this JVM's API surface.
 */
public final class LegacyCompetitivePlugin extends JavaPlugin {
    private static final long INITIAL_POLL_DELAY_TICKS = 20L;
    private static final long POLL_PERIOD_TICKS = 100L;

    private final AtomicBoolean pollInFlight = new AtomicBoolean();
    private final ConcurrentMap<UUID, LegacyExecution> activeExecutions = new ConcurrentHashMap<UUID, LegacyExecution>();
    private final ConcurrentMap<UUID, PendingOutcome> pendingOutcomes = new ConcurrentHashMap<UUID, PendingOutcome>();

    private LegacyRuntimeDatabase database;
    private String backendId;
    private int executionLeaseSeconds;
    private int pollLimit;
    private BukkitTask pumpTask;

    @Override
    public void onEnable() {
        backendId = requireEnvironment("COMPETITIVE_BACKEND_ID");
        executionLeaseSeconds = optionalPositiveInt("COMPETITIVE_EXECUTION_LEASE_SECONDS", 60, 3600);
        pollLimit = optionalPositiveInt("COMPETITIVE_POLL_LIMIT", 20, 50);
        database = new LegacyRuntimeDatabase(
                requireEnvironment("COMPETITIVE_DATABASE_URL"),
                requireEnvironment("COMPETITIVE_DATABASE_USER"),
                requireEnvironmentAllowEmpty("COMPETITIVE_DATABASE_PASSWORD")
        );
        try {
            database.initializeDriver();
            requireMappedBackend(database.heartbeatBackend(getServer().getOnlinePlayers().size()));
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Bundled PostgreSQL JDBC driver is unavailable", exception);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize competitive runtime database boundary", exception);
        }

        pumpTask = getServer().getScheduler().runTaskTimer(
                this,
                new Runnable() {
                    @Override
                    public void run() {
                        schedulePoll(getServer().getOnlinePlayers().size());
                    }
                },
                INITIAL_POLL_DELAY_TICKS,
                POLL_PERIOD_TICKS
        );
        getLogger().info("Started isolated 1.8.9 competitive runtime backend " + backendId);
    }

    @Override
    public void onDisable() {
        if (pumpTask != null) {
            pumpTask.cancel();
            pumpTask = null;
        }
        activeExecutions.clear();
        pendingOutcomes.clear();
        LegacyRuntimeDatabase current = database;
        database = null;
        if (current != null) {
            try {
                requireMappedBackend(current.markOffline());
            } catch (SQLException | RuntimeException exception) {
                getLogger().log(Level.WARNING, "Could not mark legacy competitive backend offline", exception);
            }
        }
    }

    /** Future combat controller entry point: queue an exactly-once winner report using the frozen runtime side identity. */
    void recordWinner(UUID executionId, UUID winnerMinecraftUuid) {
        LegacyExecution execution = requireActiveExecution(executionId);
        UUID winnerSideId = execution.sideIdForMinecraftUuid(winnerMinecraftUuid);
        queueOutcome(execution, new PendingOutcome(false, winnerSideId));
    }

    /** Future combat controller entry point for runtime failure/abort. */
    void recordFailure(UUID executionId) {
        LegacyExecution execution = requireActiveExecution(executionId);
        queueOutcome(execution, new PendingOutcome(true, null));
    }

    LegacyExecution findExecutionForPlayer(UUID minecraftUuid) {
        for (LegacyExecution execution : activeExecutions.values()) {
            if (execution.containsMinecraftUuid(minecraftUuid)) {
                return execution;
            }
        }
        return null;
    }

    Map<UUID, LegacyExecution> snapshotActiveExecutions() {
        return Collections.unmodifiableMap(new LinkedHashMap<UUID, LegacyExecution>(activeExecutions));
    }

    private void queueOutcome(LegacyExecution execution, PendingOutcome outcome) {
        PendingOutcome existing = pendingOutcomes.putIfAbsent(execution.getExecutionId(), outcome);
        if (existing != null && !existing.sameAs(outcome)) {
            throw new IllegalStateException("Competitive execution already has a different local terminal outcome");
        }
        activeExecutions.remove(execution.getExecutionId(), execution);
        schedulePoll(getServer().getOnlinePlayers().size());
    }

    private LegacyExecution requireActiveExecution(UUID executionId) {
        if (executionId == null) throw new NullPointerException("executionId");
        LegacyExecution execution = activeExecutions.get(executionId);
        if (execution == null) {
            throw new IllegalStateException("Competitive execution is not active on this runtime: " + executionId);
        }
        return execution;
    }

    private void schedulePoll(final int playerCount) {
        if (!isEnabled() || database == null || !pollInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            getServer().getScheduler().runTaskAsynchronously(this, new Runnable() {
                @Override
                public void run() {
                    try {
                        pollOnce(playerCount);
                    } finally {
                        pollInFlight.set(false);
                    }
                }
            });
        } catch (RuntimeException exception) {
            pollInFlight.set(false);
            throw exception;
        }
    }

    private void pollOnce(int playerCount) {
        LegacyRuntimeDatabase current = database;
        if (current == null) return;
        try {
            requireMappedBackend(current.heartbeatBackend(playerCount));
            flushPendingOutcomes(current);

            Map<UUID, LegacyExecution> refreshed = new LinkedHashMap<UUID, LegacyExecution>();
            for (LegacyExecution execution : current.pollActive(pollLimit)) {
                if (pendingOutcomes.containsKey(execution.getExecutionId())) {
                    continue;
                }
                LegacyExecution renewed = current.heartbeatExecution(execution, executionLeaseSeconds);
                refreshed.put(renewed.getExecutionId(), renewed);
            }
            activeExecutions.clear();
            activeExecutions.putAll(refreshed);
        } catch (SQLException | RuntimeException exception) {
            activeExecutions.clear();
            getLogger().log(Level.WARNING, "Legacy competitive runtime poll failed closed", exception);
        }
    }

    private void flushPendingOutcomes(LegacyRuntimeDatabase current) {
        for (Map.Entry<UUID, PendingOutcome> entry : pendingOutcomes.entrySet()) {
            UUID executionId = entry.getKey();
            PendingOutcome outcome = entry.getValue();
            try {
                if (outcome.failure) {
                    current.submitFailure(executionId);
                } else {
                    current.submitWinner(executionId, outcome.winnerSideId);
                }
                pendingOutcomes.remove(executionId, outcome);
            } catch (SQLException exception) {
                getLogger().log(Level.WARNING, "Could not submit competitive outcome for " + executionId, exception);
            }
        }
    }

    private void requireMappedBackend(String mappedBackend) {
        if (!backendId.equals(mappedBackend)) {
            throw new IllegalStateException(
                    "Database runtime principal maps to backend " + mappedBackend + " but process expected " + backendId
            );
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value.trim();
    }

    private static String requireEnvironmentAllowEmpty(String name) {
        String value = System.getenv(name);
        if (value == null) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private static int optionalPositiveInt(String name, int defaultValue, int maximum) {
        String raw = System.getenv(name);
        if (raw == null || raw.trim().isEmpty()) return defaultValue;
        final int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(name + " must be a whole number", exception);
        }
        if (parsed < 1 || parsed > maximum) {
            throw new IllegalStateException(name + " must be between 1 and " + maximum);
        }
        return parsed;
    }

    private static final class PendingOutcome {
        private final boolean failure;
        private final UUID winnerSideId;

        private PendingOutcome(boolean failure, UUID winnerSideId) {
            if (failure == (winnerSideId != null)) {
                throw new IllegalArgumentException("failure outcome forbids winner; winner outcome requires winner");
            }
            this.failure = failure;
            this.winnerSideId = winnerSideId;
        }

        private boolean sameAs(PendingOutcome other) {
            if (other == null || failure != other.failure) return false;
            return winnerSideId == null ? other.winnerSideId == null : winnerSideId.equals(other.winnerSideId);
        }
    }
}

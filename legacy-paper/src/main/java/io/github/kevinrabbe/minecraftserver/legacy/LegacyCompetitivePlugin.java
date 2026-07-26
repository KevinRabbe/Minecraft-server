package io.github.kevinrabbe.minecraftserver.legacy;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Minecraft 1.8.9 runtime shell. It sees only sanitized execution manifests and can emit only WINNER/FAILURE reports.
 * Persistent ratings, Coin, inventory, unique item identity and custody remain outside this JVM's API surface.
 */
public final class LegacyCompetitivePlugin extends JavaPlugin implements Listener {
    private static final long INITIAL_POLL_DELAY_TICKS = 20L;
    private static final long POLL_PERIOD_TICKS = 100L;
    private static final int MAX_ACTIVE_EXECUTIONS = 64;

    private final AtomicBoolean pollInFlight = new AtomicBoolean();
    private final ConcurrentMap<UUID, LegacyExecution> activeExecutions = new ConcurrentHashMap<UUID, LegacyExecution>();
    private final ConcurrentMap<UUID, LegacyExecution> admittedPlayerExecutions = new ConcurrentHashMap<UUID, LegacyExecution>();
    private final ConcurrentMap<UUID, LegacyClanWarLoadout> clanWarLoadouts = new ConcurrentHashMap<UUID, LegacyClanWarLoadout>();
    private final ConcurrentMap<UUID, PendingOutcome> pendingOutcomes = new ConcurrentHashMap<UUID, PendingOutcome>();
    private final ConcurrentMap<UUID, Boolean> onlineMinecraftUuids = new ConcurrentHashMap<UUID, Boolean>();
    private final LegacyCompetitiveCombatGate combatGate = new LegacyCompetitiveCombatGate();

    private volatile LegacyRuntimeDatabase database;
    private String backendId;
    private int executionLeaseSeconds;
    private BukkitTask pumpTask;

    @Override
    public void onEnable() {
        backendId = requireEnvironment("COMPETITIVE_BACKEND_ID");
        executionLeaseSeconds = optionalPositiveInt("COMPETITIVE_EXECUTION_LEASE_SECONDS", 60, 3600);
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

        onlineMinecraftUuids.clear();
        for (Player player : getServer().getOnlinePlayers()) {
            onlineMinecraftUuids.put(player.getUniqueId(), Boolean.TRUE);
        }

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new LegacyCompetitiveIsolationListener(this, combatGate), this);
        pumpTask = getServer().getScheduler().runTaskTimer(
                this,
                new Runnable() {
                    @Override
                    public void run() {
                        schedulePoll(onlineMinecraftUuids.size());
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
        combatGate.clear();
        onlineMinecraftUuids.clear();
        admittedPlayerExecutions.clear();
        activeExecutions.clear();
        clanWarLoadouts.clear();
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

    /**
     * Fail-closed admission for the isolated runtime. An exact ACTIVE execution must exist for this Minecraft identity
     * on this database principal's mapped backend. Clan War additionally requires its complete sealed V71/V74 loadout
     * before the player is admitted.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }

        LegacyRuntimeDatabase current = database;
        if (current == null) {
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "Competitive match admission is temporarily unavailable."
            );
            return;
        }

        try {
            LegacyExecution execution = current.findPlayerExecution(event.getUniqueId());
            if (execution == null) {
                event.disallow(
                        AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        "No active competitive match is assigned to this player on this backend."
                );
                return;
            }

            LegacyClanWarLoadout loadout = LegacyExecutionAdmission.prepare(
                    execution,
                    current::pageExecutionLoadout
            );
            if (loadout != null) {
                clanWarLoadouts.put(execution.getExecutionId(), loadout);
            }
            activeExecutions.put(execution.getExecutionId(), execution);
            admittedPlayerExecutions.put(event.getUniqueId(), execution);
        } catch (SQLException | RuntimeException exception) {
            getLogger().log(Level.WARNING, "Competitive player admission lookup failed closed", exception);
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "Competitive match admission is temporarily unavailable."
            );
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        onlineMinecraftUuids.put(event.getPlayer().getUniqueId(), Boolean.TRUE);
        schedulePoll(onlineMinecraftUuids.size());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID minecraftUuid = event.getPlayer().getUniqueId();
        onlineMinecraftUuids.remove(minecraftUuid);
        admittedPlayerExecutions.remove(minecraftUuid);
        schedulePoll(onlineMinecraftUuids.size());
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
        LegacyExecution admitted = admittedPlayerExecutions.get(minecraftUuid);
        if (admitted != null) {
            return admitted;
        }
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
        combatGate.disable(execution.getExecutionId());
        clanWarLoadouts.remove(execution.getExecutionId());
        activeExecutions.remove(execution.getExecutionId(), execution);
        schedulePoll(onlineMinecraftUuids.size());
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
        Set<UUID> onlineSnapshot = new HashSet<UUID>(onlineMinecraftUuids.keySet());
        try {
            requireMappedBackend(current.heartbeatBackend(playerCount));
            flushPendingOutcomes(current);

            Map<UUID, LegacyExecution> refreshed = new LinkedHashMap<UUID, LegacyExecution>();
            Map<UUID, LegacyClanWarLoadout> refreshedClanWarLoadouts = new LinkedHashMap<UUID, LegacyClanWarLoadout>();
            for (LegacyExecution execution : current.pollActive(MAX_ACTIVE_EXECUTIONS)) {
                if (pendingOutcomes.containsKey(execution.getExecutionId())) {
                    continue;
                }
                try {
                    requireSupportedManifest(execution);
                } catch (RuntimeException exception) {
                    getLogger().log(
                            Level.SEVERE,
                            "Refusing to renew unsupported competitive manifest " + execution.getExecutionId(),
                            exception
                    );
                    continue;
                }

                if (LegacyClanWarExecution.ACTIVITY_KIND.equals(execution.getActivityKind())) {
                    LegacyClanWarLoadout loadout = clanWarLoadouts.get(execution.getExecutionId());
                    try {
                        if (loadout == null) {
                            LegacyClanWarExecution war = LegacyClanWarExecution.requireSupported(execution);
                            loadout = LegacyClanWarLoadoutLoader.load(war, current::pageExecutionLoadout);
                        } else {
                            // The immutable loadout can stay cached, but V74 seal/ownership/liveness must still be checked
                            // on every lease cycle so a corrupted/unsealed execution does not keep renewing from cache.
                            current.pageExecutionLoadout(execution.getExecutionId(), null, null, 1);
                        }
                    } catch (SQLException | RuntimeException exception) {
                        getLogger().log(
                                Level.SEVERE,
                                "Refusing to renew Clan-War execution without a valid frozen loadout "
                                        + execution.getExecutionId(),
                                exception
                        );
                        continue;
                    }
                    refreshedClanWarLoadouts.put(execution.getExecutionId(), loadout);
                }

                if (LegacyRankedExecution.ACTIVITY_KIND.equals(execution.getActivityKind())
                        && !execution.allParticipantsOnline(onlineSnapshot)) {
                    // Keep the still-live manifest locally for admission/isolation, but deliberately do not extend its
                    // database lease. The trusted control worker will cancel it after the original lease expires.
                    refreshed.put(execution.getExecutionId(), execution);
                    continue;
                }

                LegacyExecution renewed = current.heartbeatExecution(execution, executionLeaseSeconds);
                refreshed.put(renewed.getExecutionId(), renewed);
            }
            activeExecutions.clear();
            activeExecutions.putAll(refreshed);
            clanWarLoadouts.clear();
            clanWarLoadouts.putAll(refreshedClanWarLoadouts);
        } catch (SQLException | RuntimeException exception) {
            activeExecutions.clear();
            clanWarLoadouts.clear();
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

    private static void requireSupportedManifest(LegacyExecution execution) {
        if (LegacyRankedExecution.ACTIVITY_KIND.equals(execution.getActivityKind())) {
            LegacyRankedExecution.requireSupported(execution);
            return;
        }
        if (LegacyClanWarExecution.ACTIVITY_KIND.equals(execution.getActivityKind())) {
            LegacyClanWarExecution.requireSupported(execution);
            return;
        }
        throw new IllegalArgumentException("Unsupported competitive activity kind: " + execution.getActivityKind());
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

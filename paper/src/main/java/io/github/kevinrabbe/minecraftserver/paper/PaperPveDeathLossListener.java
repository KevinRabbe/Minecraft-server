package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.economy.PveDeathLossConfig;
import io.github.kevinrabbe.minecraftserver.common.economy.PveDeathLossRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.PveDeathLossResult;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Paper adapter for ordinary persistent-world PvE death loss.
 *
 * <p>Map encounter backends are excluded because disposable Map death/failure semantics are owned by the Map runtime.
 * Direct player-kill deaths are also excluded so this policy cannot silently become a PvP penalty. The bundled policy
 * is disabled until launch tuning explicitly opts it in.</p>
 */
final class PaperPveDeathLossListener implements Listener {
    private static final String REASON = "pve.death";

    private final JavaPlugin plugin;
    private final PaperPlayerIdentityResolver playerIdentities;
    private final PveDeathLossRepository deathLoss;
    private final PveDeathLossConfig config;
    private final boolean ordinaryPersistentZone;
    private final ConcurrentHashMap<UUID, UUID> deathOperationByMinecraftUuid = new ConcurrentHashMap<>();

    PaperPveDeathLossListener(
            JavaPlugin plugin,
            PaperPlayerIdentityResolver playerIdentities,
            PveDeathLossRepository deathLoss,
            PveDeathLossConfig config,
            boolean ordinaryPersistentZone
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.playerIdentities = Objects.requireNonNull(playerIdentities, "playerIdentities");
        this.deathLoss = Objects.requireNonNull(deathLoss, "deathLoss");
        this.config = Objects.requireNonNull(config, "config");
        this.ordinaryPersistentZone = ordinaryPersistentZone;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (!PveDeathLossEligibility.shouldApply(
                config.enabled(),
                ordinaryPersistentZone,
                player.getKiller() != null
        )) {
            return;
        }

        UUID minecraftUuid = player.getUniqueId();
        UUID operationId = UUID.randomUUID();
        UUID existing = deathOperationByMinecraftUuid.putIfAbsent(minecraftUuid, operationId);
        if (existing != null) {
            plugin.getLogger().warning(
                    "Ignoring duplicate PvE death-loss signal for " + minecraftUuid + "; operation " + existing
                            + " already owns this death lifecycle"
            );
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<UUID> playerId = playerIdentities.resolve(minecraftUuid);
                if (playerId.isEmpty()) {
                    plugin.getLogger().severe("PvE death player has no persistent identity: " + minecraftUuid);
                    return;
                }

                PveDeathLossResult result = deathLoss.apply(
                        operationId,
                        playerId.orElseThrow(),
                        config.policyVersion(),
                        config,
                        REASON
                );
                if (result.lossMinor() > 0) {
                    plugin.getLogger().info(() -> "Applied ordinary-PvE pocket-Coin death loss operation "
                            + operationId + " for player " + result.playerId() + ": " + result.lossMinor()
                            + " minor units destroyed");
                }
            } catch (SQLException | RuntimeException exception) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Could not settle ordinary-PvE pocket-Coin death loss operation " + operationId,
                        exception
                );
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        deathOperationByMinecraftUuid.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        deathOperationByMinecraftUuid.remove(event.getPlayer().getUniqueId());
    }
}

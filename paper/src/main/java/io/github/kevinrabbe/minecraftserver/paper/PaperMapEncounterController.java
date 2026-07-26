package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityException;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterHandoffQueryRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterHandoffSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunStatus;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/** Owns target-Paper attachment of an exact persisted Map handoff to one disposable encounter instance. */
final class PaperMapEncounterController implements Listener {
    private static final String START_REASON = "map.encounter_start";
    private static final Component UNASSIGNED_MESSAGE = Component.text(
            "This disposable Map encounter is not assigned to your persistent run. Please reconnect."
    );
    private static final Component START_FAILED_MESSAGE = Component.text(
            "Your Map encounter could not be started safely. Please reconnect."
    );

    private final MinecraftServerPlugin plugin;
    private final BootstrapZoneInstance instance;
    private final PaperPlayerIdentityResolver identities;
    private final MapEncounterHandoffQueryRepository handoffs;
    private final MapAuthorityRepository maps;
    private final PaperMapExterminationController gameplay;

    PaperMapEncounterController(
            MinecraftServerPlugin plugin,
            BootstrapZoneInstance instance,
            PaperPlayerIdentityResolver identities,
            MapEncounterHandoffQueryRepository handoffs,
            MapAuthorityRepository maps,
            PaperMapExterminationController gameplay
    ) {
        this.plugin = plugin;
        this.instance = instance;
        this.identities = identities;
        this.handoffs = handoffs;
        this.maps = maps;
        this.gameplay = gameplay;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID minecraftUuid = event.getPlayer().getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player live = plugin.getServer().getPlayer(minecraftUuid);
            if (live == null || !live.isOnline()) {
                return;
            }
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> startAssignedEncounter(minecraftUuid));
        }, 1L);
    }

    void shutdown() {
        gameplay.shutdown();
    }

    private void startAssignedEncounter(UUID minecraftUuid) {
        try {
            UUID playerId = identities.resolve(minecraftUuid).orElse(null);
            if (playerId == null) {
                kick(minecraftUuid, UNASSIGNED_MESSAGE);
                return;
            }
            Optional<MapEncounterHandoffSnapshot> assigned = handoffs.findCreatedForPlayerInstance(
                    playerId,
                    instance.instanceId()
            );
            if (assigned.isEmpty()) {
                if (!gameplay.evacuateTerminalReconnect(minecraftUuid, playerId)) {
                    kick(minecraftUuid, UNASSIGNED_MESSAGE);
                }
                return;
            }

            MapEncounterHandoffSnapshot handoff = assigned.orElseThrow();
            MapRunSnapshot created = maps.loadRun(handoff.runId());
            if (created.status() != MapRunStatus.CREATED) {
                if (!gameplay.evacuateTerminalReconnect(minecraftUuid, playerId)) {
                    kick(minecraftUuid, START_FAILED_MESSAGE);
                }
                return;
            }
            maps.startRun(
                    deterministicOperation("start", handoff.runId()),
                    handoff.runId(),
                    created.stateVersion(),
                    List.of(playerId),
                    START_REASON
            );
            MapRunSnapshot started = maps.loadRun(handoff.runId());
            if (started.status() != MapRunStatus.ACTIVE) {
                throw new MapAuthorityException("Map run did not become ACTIVE after target attachment: " + handoff.runId());
            }
            gameplay.attach(minecraftUuid, handoff, started);
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not attach player to reserved Map encounter", exception);
            kick(minecraftUuid, START_FAILED_MESSAGE);
        }
    }

    private void kick(UUID minecraftUuid, Component message) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(minecraftUuid);
            if (player != null && player.isOnline()) {
                player.kick(message);
            }
        });
    }

    private static UUID deterministicOperation(String action, UUID runId) {
        return UUID.nameUUIDFromBytes(
                ("minecraft-server:map-encounter:" + action + ":" + runId).getBytes(StandardCharsets.UTF_8)
        );
    }
}

package io.github.kevinrabbe.minecraftserver.legacy;

import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Objects;
import java.util.UUID;

/**
 * Execution-isolation guard for the disposable 1.8.9 competitive tier.
 *
 * <p>This is deliberately independent of arena geometry/loadout. It prevents players assigned to one logical execution
 * from interacting with another execution, prevents temporary match inventory/world mutation from leaking through
 * shared backend infrastructure, and keeps combat closed until the materialized execution is currently runnable.</p>
 */
final class LegacyCompetitiveIsolationListener implements Listener {
    private final LegacyCompetitivePlugin plugin;
    private final LegacyCompetitiveCombatGate combatGate;

    LegacyCompetitiveIsolationListener(LegacyCompetitivePlugin plugin, LegacyCompetitiveCombatGate combatGate) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.combatGate = Objects.requireNonNull(combatGate, "combatGate");
    }

    /** An admitted player that does not yet own executable temporary state cannot interfere with the active arena. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        LegacyExecution execution = plugin.findExecutionForPlayer(event.getPlayer().getUniqueId());
        if (execution != null && !combatGate.isEnabled(execution.getExecutionId())) {
            event.getPlayer().setGameMode(GameMode.SPECTATOR);
        }
    }

    /** Environmental damage is blocked while the execution is not currently runnable. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        LegacyExecution execution = plugin.findExecutionForPlayer(((Player) event.getEntity()).getUniqueId());
        if (execution != null && !combatEnabled(execution)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDamagePlayer(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;

        Player victim = (Player) event.getEntity();
        Player attacker = attackingPlayer(event.getDamager());
        if (attacker == null) return;

        if (!sameEnabledExecutionOrUnrelated(attacker, victim)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityCombustPlayer(EntityCombustByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;

        Player victim = (Player) event.getEntity();
        Player attacker = attackingPlayer(event.getCombuster());
        if (attacker == null) return;

        if (!sameEnabledExecutionOrUnrelated(attacker, victim)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        ProjectileSource shooter = event.getPotion().getShooter();
        if (!(shooter instanceof Player)) return;

        Player attacker = (Player) shooter;
        for (LivingEntity affected : event.getAffectedEntities()) {
            if (!(affected instanceof Player)) continue;
            Player victim = (Player) affected;
            if (!sameEnabledExecutionOrUnrelated(attacker, victim)) {
                event.setIntensity(affected, 0.0D);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isCompetitive(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isCompetitive(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (isCompetitive(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (isCompetitive(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isCompetitive(event.getPlayer())) event.setCancelled(true);
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent event) {
        if (isCompetitive(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!isCompetitive(event.getEntity())) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    private boolean sameEnabledExecutionOrUnrelated(Player first, Player second) {
        LegacyExecution firstExecution = plugin.findExecutionForPlayer(first.getUniqueId());
        LegacyExecution secondExecution = plugin.findExecutionForPlayer(second.getUniqueId());
        if (firstExecution == null && secondExecution == null) return true;
        if (firstExecution == null || secondExecution == null) return false;
        if (!firstExecution.getExecutionId().equals(secondExecution.getExecutionId())) return false;
        return combatEnabled(firstExecution);
    }

    private boolean combatEnabled(LegacyExecution execution) {
        return LegacyCombatAvailability.isEnabled(execution, combatGate, this::isOnline);
    }

    private boolean isOnline(UUID minecraftUuid) {
        Player player = plugin.getServer().getPlayer(minecraftUuid);
        return player != null && player.isOnline();
    }

    private boolean isCompetitive(Player player) {
        return plugin.findExecutionForPlayer(player.getUniqueId()) != null;
    }

    private static Player attackingPlayer(Entity damager) {
        if (damager instanceof Player) return (Player) damager;
        if (!(damager instanceof Projectile)) return null;
        ProjectileSource shooter = ((Projectile) damager).getShooter();
        return shooter instanceof Player ? (Player) shooter : null;
    }
}

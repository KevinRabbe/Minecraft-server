package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.economy.BazaarPolicy;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarRepository;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;

/** One bounded restart pass so a crash between order creation and matching does not strand crossed books. */
final class PaperBazaarRecovery {
    private static final String MATCH_REASON = "bazaar.restart_match";

    private PaperBazaarRecovery() { }

    static void schedule(
            JavaPlugin plugin,
            BazaarRepository bazaar,
            BazaarPolicy policy,
            ItemCatalog items,
            PaperCommodityDeliveryController deliveries
    ) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(bazaar, "bazaar");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(deliveries, "deliveries");
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean filledAny = false;
                for (ItemDefinition item : items.definitions()) {
                    if (item.identityKind() != ItemIdentityKind.COMMODITY) {
                        continue;
                    }
                    try {
                        if (bazaar.matchCommodity(
                                UUID.randomUUID(),
                                item.definitionId(),
                                policy.maxFillsPerMatch(),
                                MATCH_REASON
                        ).fills() > 0) {
                            filledAny = true;
                        }
                    } catch (SQLException | RuntimeException exception) {
                        plugin.getLogger().log(
                                Level.WARNING,
                                "Could not run restart Bazaar matching for " + item.definitionId(),
                                exception
                        );
                    }
                }
                if (filledAny && plugin.isEnabled()) {
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            plugin.getServer().getOnlinePlayers().forEach(
                                    player -> deliveries.requestDrain(player.getUniqueId())
                            )
                    );
                }
            });
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule restart Bazaar matching", exception);
        }
    }
}

package io.github.kevinrabbe.minecraftserver.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneRouter;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;

import java.time.Duration;

@Plugin(
        id = "minecraft-network",
        name = "Minecraft Network",
        version = "0.1.0-SNAPSHOT",
        description = "Velocity routing and network coordination"
)
public final class MinecraftNetworkPlugin {
    private static final System.Logger LOGGER = System.getLogger(MinecraftNetworkPlugin.class.getName());
    private static final Duration INSTANCE_HEARTBEAT_FRESHNESS = Duration.ofSeconds(15);

    private Database database;
    private ZoneRouter zoneRouter;

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        database = Database.open(DatabaseConfig.fromEnvironment());
        database.migrate();
        zoneRouter = new ZoneRouter(database.dataSource(), INSTANCE_HEARTBEAT_FRESHNESS);

        LOGGER.log(System.Logger.Level.INFO, "Minecraft network plugin initialized with PostgreSQL zone routing");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (database != null) {
            database.close();
            database = null;
        }
        zoneRouter = null;
    }

    ZoneRouter zoneRouter() {
        if (zoneRouter == null) {
            throw new IllegalStateException("Zone router is not initialized");
        }
        return zoneRouter;
    }
}

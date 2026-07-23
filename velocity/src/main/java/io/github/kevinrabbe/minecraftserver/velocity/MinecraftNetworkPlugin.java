package io.github.kevinrabbe.minecraftserver.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;

@Plugin(
        id = "minecraft-network",
        name = "Minecraft Network",
        version = "0.1.0-SNAPSHOT",
        description = "Velocity routing and network coordination"
)
public final class MinecraftNetworkPlugin {
    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        System.getLogger(MinecraftNetworkPlugin.class.getName())
                .log(System.Logger.Level.INFO, "Minecraft network plugin initialized");
    }
}

package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.ServerRole;
import org.bukkit.plugin.java.JavaPlugin;

public final class MinecraftServerPlugin extends JavaPlugin {
    private String serverId;
    private ServerRole serverRole;

    @Override
    public void onEnable() {
        serverId = requireEnvironment("SERVER_ID");
        serverRole = ServerRole.parse(System.getenv("SERVER_ROLE"));

        getLogger().info(() -> "Started backend " + serverId + " with role " + serverRole);
    }

    public String serverId() {
        return serverId;
    }

    public ServerRole serverRole() {
        return serverRole;
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set");
        }
        return value.trim();
    }
}

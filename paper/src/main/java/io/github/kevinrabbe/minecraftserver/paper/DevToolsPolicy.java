package io.github.kevinrabbe.minecraftserver.paper;

/** Small fail-closed policy for development-only Paper surfaces. */
final class DevToolsPolicy {
    static final String ENABLE_ENVIRONMENT_VARIABLE = "DEV_TOOLS_ENABLED";

    private DevToolsPolicy() { }

    static boolean enabled(String rawValue) {
        return rawValue != null && rawValue.trim().equalsIgnoreCase("true");
    }
}

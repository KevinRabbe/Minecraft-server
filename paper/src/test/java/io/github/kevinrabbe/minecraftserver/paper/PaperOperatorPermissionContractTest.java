package io.github.kevinrabbe.minecraftserver.paper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperOperatorPermissionContractTest {
    @Test
    void operatorCommandsUseTheCapabilitiesEnforcedByTheirExecutors() throws IOException {
        String pluginYaml = loadPluginYaml();

        assertEquals(DevZoneCommand.PERMISSION, commandPermission(pluginYaml, "devzone"));
        assertEquals(PaperIntegrityCommand.PERMISSION, commandPermission(pluginYaml, "integrity"));
        assertTrue(declaresPermission(pluginYaml, DevZoneCommand.PERMISSION));
        assertTrue(declaresPermission(pluginYaml, PaperIntegrityCommand.PERMISSION));
    }

    private static String loadPluginYaml() throws IOException {
        try (InputStream input = Objects.requireNonNull(
                PaperOperatorPermissionContractTest.class.getResourceAsStream("/plugin.yml"),
                "plugin.yml must be present on the Paper test classpath"
        )) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String commandPermission(String yaml, String commandName) {
        String[] lines = yaml.split("\\R");
        String commandHeader = "  " + commandName + ":";
        boolean insideCommand = false;
        for (String line : lines) {
            if (line.equals(commandHeader)) {
                insideCommand = true;
                continue;
            }
            if (insideCommand && line.startsWith("  ") && !line.startsWith("    ")) {
                break;
            }
            if (insideCommand && line.startsWith("    permission:")) {
                return line.substring(line.indexOf(':') + 1).trim();
            }
        }
        throw new AssertionError("Command " + commandName + " has no permission in plugin.yml");
    }

    private static boolean declaresPermission(String yaml, String permission) {
        return yaml.lines().anyMatch(line -> line.equals("  " + permission + ":"));
    }
}

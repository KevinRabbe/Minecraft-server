package io.github.kevinrabbe.minecraftserver.common;

import java.util.Locale;

/** Logical backend roles exposed to the network. */
public enum ServerRole {
    CITY,
    MINE,
    FOREST,
    FARM,
    NETHER,
    PVP,
    WAR;

    public static ServerRole parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SERVER_ROLE must be set");
        }

        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported SERVER_ROLE: " + value, exception);
        }
    }
}

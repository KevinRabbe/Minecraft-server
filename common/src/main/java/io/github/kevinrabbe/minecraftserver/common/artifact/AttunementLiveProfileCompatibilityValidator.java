package io.github.kevinrabbe.minecraftserver.common.artifact;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** Startup compatibility gate for currently selected Attunement profile identities. */
public final class AttunementLiveProfileCompatibilityValidator {
    private AttunementLiveProfileCompatibilityValidator() { }

    public static void validate(DataSource dataSource, AttunementProfileCatalog profiles) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(profiles, "profiles");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT DISTINCT active_profile_id
                     FROM player_attunement_state
                     WHERE active_profile_id IS NOT NULL
                     ORDER BY active_profile_id
                     """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                profiles.require(rows.getString("active_profile_id"));
            }
        }
    }
}

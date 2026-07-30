package io.github.kevinrabbe.minecraftserver.common.item;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Startup compatibility gate for managed value inside durable serialized player inventory. */
public final class StoredPlayerItemLiveContentCompatibilityValidator {
    private StoredPlayerItemLiveContentCompatibilityValidator() { }

    public static void validate(
            DataSource dataSource,
            ItemCatalog catalog,
            StoredPlayerItemClaimReader claimReader
    ) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(claimReader, "claimReader");

        ItemRepresentationAuthorityValidator authority = new ItemRepresentationAuthorityValidator(dataSource, catalog);
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT player_id, state_payload
                    FROM player_state
                    WHERE state_payload IS NOT NULL
                    ORDER BY player_id
                    """);
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID playerId = rows.getObject("player_id", UUID.class);
                    byte[] payload = rows.getBytes("state_payload");
                    List<ItemRepresentationClaim> claims;
                    try {
                        claims = List.copyOf(Objects.requireNonNull(
                                claimReader.readClaims(payload),
                                "claimReader returned null"
                        ));
                    } catch (RuntimeException exception) {
                        throw new ItemCatalogException(
                                "Stored player inventory cannot be decoded for player_id " + playerId,
                                exception
                        );
                    }

                    List<ItemRepresentationIssue> issues = authority.validate(playerId, claims);
                    if (!issues.isEmpty()) {
                        ItemRepresentationIssue first = issues.getFirst();
                        throw new ItemCatalogException(
                                "Stored player inventory is incompatible for player_id " + playerId
                                        + " at " + first.source()
                                        + ": " + first.code() + " - " + first.detail()
                                        + (issues.size() == 1 ? "" : " (and " + (issues.size() - 1) + " more issue(s))")
                        );
                    }
                }
            }
        }
    }
}

package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationAuthorityValidator;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationClaim;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationIssue;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationValidationResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Startup compatibility gate for every persisted network-owned player inventory payload. */
final class PaperPlayerStateLiveContentCompatibilityValidator {
    private static final int FETCH_SIZE = 16;
    private static final int MAX_REPORTED_ISSUES = 8;

    private final DataSource dataSource;
    private final StoredClaimExtractor claimExtractor;
    private final ItemRepresentationAuthorityValidator authorityValidator;

    static void validate(
            MinecraftServerPlugin plugin,
            DataSource dataSource,
            ItemCatalog itemCatalog
    ) throws SQLException {
        Objects.requireNonNull(plugin, "plugin");
        PaperPlayerStateCodec codec = new PaperPlayerStateCodec();
        PaperManagedItemScanner scanner = new PaperManagedItemScanner(plugin);
        new PaperPlayerStateLiveContentCompatibilityValidator(
                dataSource,
                itemCatalog,
                payload -> scanner.collectInventoryStateClaims(codec.decodeState(payload))
        ).validate();
    }

    PaperPlayerStateLiveContentCompatibilityValidator(
            DataSource dataSource,
            ItemCatalog itemCatalog,
            StoredClaimExtractor claimExtractor
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.claimExtractor = Objects.requireNonNull(claimExtractor, "claimExtractor");
        this.authorityValidator = new ItemRepresentationAuthorityValidator(
                dataSource,
                Objects.requireNonNull(itemCatalog, "itemCatalog")
        );
    }

    void validate() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT player_id,
                           state_version,
                           state_payload
                    FROM player_state
                    WHERE state_payload IS NOT NULL
                    ORDER BY player_id
                    """)) {
                statement.setFetchSize(FETCH_SIZE);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        validateRow(
                                rows.getObject("player_id", UUID.class),
                                rows.getLong("state_version"),
                                rows.getBytes("state_payload")
                        );
                    }
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private void validateRow(UUID playerId, long stateVersion, byte[] payload) throws SQLException {
        List<ItemRepresentationClaim> claims;
        try {
            claims = List.copyOf(claimExtractor.extract(payload));
        } catch (RuntimeException exception) {
            throw new PaperItemRepresentationException(
                    "Stored player_state payload cannot be interpreted for player_id " + playerId
                            + " at state_version " + stateVersion,
                    exception
            );
        }

        ItemRepresentationValidationResult result = authorityValidator.validateAndSnapshot(playerId, claims);
        if (result.issues().isEmpty()) {
            return;
        }

        throw new PaperItemRepresentationException(
                "Stored player_state contains " + result.issues().size()
                        + " incompatible managed representation(s) for player_id " + playerId
                        + " at state_version " + stateVersion + ": "
                        + summarize(result.issues())
        );
    }

    private static String summarize(List<ItemRepresentationIssue> issues) {
        String summary = issues.stream()
                .limit(MAX_REPORTED_ISSUES)
                .map(issue -> issue.source() + "=" + issue.code() + " (" + issue.detail() + ")")
                .collect(Collectors.joining("; "));
        if (issues.size() > MAX_REPORTED_ISSUES) {
            return summary + "; ... " + (issues.size() - MAX_REPORTED_ISSUES) + " more";
        }
        return summary;
    }

    @FunctionalInterface
    interface StoredClaimExtractor {
        List<ItemRepresentationClaim> extract(byte[] payload);
    }
}

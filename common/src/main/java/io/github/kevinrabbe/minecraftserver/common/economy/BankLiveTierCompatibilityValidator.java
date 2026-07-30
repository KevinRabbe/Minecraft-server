package io.github.kevinrabbe.minecraftserver.common.economy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** Startup compatibility gate for Bank Manager configuration against current protected-account state. */
public final class BankLiveTierCompatibilityValidator {
    private BankLiveTierCompatibilityValidator() { }

    public static void validate(DataSource dataSource, BankTierCatalog tiers) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(tiers, "tiers");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT tier, MAX(balance_minor) AS max_balance_minor
                     FROM bank_accounts
                     GROUP BY tier
                     ORDER BY tier
                     """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                int tier = rows.getInt("tier");
                long maxBalanceMinor = rows.getLong("max_balance_minor");
                BankTierDefinition definition = tiers.require(tier);
                if (maxBalanceMinor > definition.capacityMinor()) {
                    throw new BankManagerException(
                            "Bank tier " + tier + " capacity " + definition.capacityMinor()
                                    + " is below existing protected balance " + maxBalanceMinor
                    );
                }
            }
        }
    }
}

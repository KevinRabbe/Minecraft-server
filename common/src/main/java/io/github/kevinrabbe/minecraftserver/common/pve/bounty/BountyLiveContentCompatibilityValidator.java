package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Startup compatibility gate for non-terminal Bounty contracts.
 *
 * <p>Historical terminal contracts are self-contained evidence and do not require their old content definitions to
 * remain loaded forever. Live contracts do: hunt eligibility, summon identity, materialization and settlement still
 * resolve their frozen {@code (family, tier, content_version)} identity. A deployment that omits one of those exact
 * definitions must therefore fail before gameplay starts instead of leaving only the affected players partially
 * broken.</p>
 */
public final class BountyLiveContentCompatibilityValidator {
    private BountyLiveContentCompatibilityValidator() { }

    public static void validate(DataSource dataSource, BountyTierCatalog catalog) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(catalog, "catalog");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT DISTINCT family_id, tier, content_version
                     FROM bounty_contracts
                     WHERE status IN ('ACTIVE_HUNT', 'SUMMON_READY', 'SUMMONED')
                     ORDER BY family_id, tier, content_version
                     """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                BountyFamilyId familyId = new BountyFamilyId(rows.getString("family_id"));
                int tier = rows.getInt("tier");
                int contentVersion = rows.getInt("content_version");
                try {
                    catalog.require(familyId, tier, contentVersion);
                } catch (BountyException exception) {
                    throw new BountyException(
                            "Live bounty contract requires unavailable content "
                                    + familyId.value() + "/" + tier + "@" + contentVersion,
                            exception
                    );
                }
            }
        }
    }
}

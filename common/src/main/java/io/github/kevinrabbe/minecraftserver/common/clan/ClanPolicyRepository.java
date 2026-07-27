package io.github.kevinrabbe.minecraftserver.common.clan;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** Trusted shared balance-policy access for clan mechanics. Player commands do not receive this mutation surface. */
public final class ClanPolicyRepository {
    public static final int MIN_MEMBER_CAP = 1;
    public static final int MAX_MEMBER_CAP = 10000;

    private final DataSource dataSource;

    public ClanPolicyRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public ClanPolicySnapshot load() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT member_cap, updated_at
                     FROM clan_policy
                     WHERE singleton = TRUE
                     """);
             ResultSet row = statement.executeQuery()) {
            if (!row.next()) {
                throw new SQLException("Clan policy row is missing");
            }
            return read(row);
        }
    }

    /** Trusted configuration mutation. This is deliberately not exposed through the ordinary Paper clan command. */
    public ClanPolicySnapshot configureMemberCap(int memberCap) throws SQLException {
        requireMemberCap(memberCap);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE clan_policy
                     SET member_cap = ?
                     WHERE singleton = TRUE
                     RETURNING member_cap, updated_at
                     """)) {
            statement.setInt(1, memberCap);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("Clan policy row is missing");
                }
                return read(row);
            }
        }
    }

    private static ClanPolicySnapshot read(ResultSet row) throws SQLException {
        return new ClanPolicySnapshot(
                row.getInt("member_cap"),
                row.getTimestamp("updated_at").toInstant()
        );
    }

    private static void requireMemberCap(int memberCap) {
        if (memberCap < MIN_MEMBER_CAP || memberCap > MAX_MEMBER_CAP) {
            throw new IllegalArgumentException(
                    "memberCap must be between " + MIN_MEMBER_CAP + " and " + MAX_MEMBER_CAP
            );
        }
    }
}

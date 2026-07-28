package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read-only discovery of abandoned bounty summons; mutation remains owned by {@link BountyRepository}. */
public final class BountySummonRecoveryRepository {
    private static final Duration MAX_READY_GRACE = Duration.ofMinutes(10);
    private static final int MAX_LIMIT = 100;

    private final DataSource dataSource;

    public BountySummonRecoveryRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Returns summon IDs that may be reclaimed and failed by a runtime controller.
     *
     * <p>Fresh READY rows are deliberately excluded so the normal prepare -> claim sequence cannot race a recovery
     * worker on another backend. ACTIVE rows become recoverable only after their authoritative lease expires.</p>
     */
    public List<UUID> listRecoverable(Duration readyGrace, int limit) throws SQLException {
        Duration grace = Objects.requireNonNull(readyGrace, "readyGrace");
        if (grace.isNegative() || grace.compareTo(MAX_READY_GRACE) > 0) {
            throw new IllegalArgumentException("readyGrace must be >= 0 and <= 10 minutes");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT summon_id
                     FROM bounty_summons
                     WHERE (status = 'READY'
                                AND created_at <= NOW() - (? * INTERVAL '1 millisecond'))
                        OR (status = 'ACTIVE'
                                AND lease_expires_at IS NOT NULL
                                AND lease_expires_at <= NOW())
                     ORDER BY created_at ASC, summon_id ASC
                     LIMIT ?
                     """)) {
            statement.setLong(1, grace.toMillis());
            statement.setInt(2, limit);
            try (ResultSet row = statement.executeQuery()) {
                ArrayList<UUID> result = new ArrayList<>();
                while (row.next()) {
                    result.add(row.getObject("summon_id", UUID.class));
                }
                return List.copyOf(result);
            }
        }
    }
}

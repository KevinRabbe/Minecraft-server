package io.github.kevinrabbe.minecraftserver.common.economy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Read-only bounded projection of complete OPEN crafting-commission terms. */
public final class CraftingCommissionQueryRepository {
    private static final int MAX_BROWSE_LIMIT = 100;

    private final DataSource dataSource;

    public CraftingCommissionQueryRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<CraftingCommissionBrowseEntry> listOpen(int limit) throws SQLException {
        if (limit < 1 || limit > MAX_BROWSE_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_BROWSE_LIMIT);
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT c.commission_id,
                            c.requester_player_id,
                            c.recipe_id,
                            c.recipe_version,
                            c.payment_minor,
                            c.created_at,
                            m.commodity_definition_id,
                            m.quantity
                     FROM (
                         SELECT commission_id,
                                requester_player_id,
                                recipe_id,
                                recipe_version,
                                payment_minor,
                                created_at
                         FROM crafting_commissions
                         WHERE status = 'OPEN'
                         ORDER BY created_at ASC, commission_id ASC
                         LIMIT ?
                     ) c
                     LEFT JOIN crafting_commission_materials m
                       ON m.commission_id = c.commission_id
                     ORDER BY c.created_at ASC, c.commission_id ASC, m.commodity_definition_id ASC
                     """)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                LinkedHashMap<UUID, MutableEntry> entries = new LinkedHashMap<>();
                while (rows.next()) {
                    UUID commissionId = rows.getObject("commission_id", UUID.class);
                    MutableEntry entry = entries.computeIfAbsent(commissionId, ignored -> new MutableEntry(
                            commissionId,
                            requireUuid(rows, "requester_player_id"),
                            requireString(rows, "recipe_id"),
                            requireNonNegativeInt(rows, "recipe_version"),
                            requireNonNegativeLong(rows, "payment_minor"),
                            requireInstant(rows, "created_at")
                    ));
                    String material = rows.getString("commodity_definition_id");
                    if (material != null) {
                        long quantity = rows.getLong("quantity");
                        if (quantity <= 0 || entry.materials.putIfAbsent(material, quantity) != null) {
                            throw new CraftingCommissionException(
                                    "Invalid persisted crafting commission material projection: " + commissionId
                            );
                        }
                    }
                }

                ArrayList<CraftingCommissionBrowseEntry> result = new ArrayList<>(entries.size());
                for (MutableEntry entry : entries.values()) {
                    if (entry.materials.isEmpty()) {
                        throw new CraftingCommissionException(
                                "OPEN crafting commission has no material escrow: " + entry.commissionId
                        );
                    }
                    result.add(new CraftingCommissionBrowseEntry(
                            entry.commissionId,
                            entry.requesterPlayerId,
                            entry.recipeId,
                            entry.recipeVersion,
                            entry.materials,
                            entry.paymentMinor,
                            entry.createdAt
                    ));
                }
                return List.copyOf(result);
            }
        }
    }

    private static UUID requireUuid(ResultSet rows, String field) {
        try {
            UUID value = rows.getObject(field, UUID.class);
            if (value == null) throw new CraftingCommissionException("Missing commission browse field: " + field);
            return value;
        } catch (SQLException exception) {
            throw new CraftingCommissionException("Could not read commission browse field: " + field, exception);
        }
    }

    private static String requireString(ResultSet rows, String field) {
        try {
            String value = rows.getString(field);
            if (value == null || value.isBlank()) {
                throw new CraftingCommissionException("Missing commission browse field: " + field);
            }
            return value;
        } catch (SQLException exception) {
            throw new CraftingCommissionException("Could not read commission browse field: " + field, exception);
        }
    }

    private static int requireNonNegativeInt(ResultSet rows, String field) {
        try {
            int value = rows.getInt(field);
            if (value < 0) throw new CraftingCommissionException("Invalid commission browse field: " + field);
            return value;
        } catch (SQLException exception) {
            throw new CraftingCommissionException("Could not read commission browse field: " + field, exception);
        }
    }

    private static long requireNonNegativeLong(ResultSet rows, String field) {
        try {
            long value = rows.getLong(field);
            if (value < 0) throw new CraftingCommissionException("Invalid commission browse field: " + field);
            return value;
        } catch (SQLException exception) {
            throw new CraftingCommissionException("Could not read commission browse field: " + field, exception);
        }
    }

    private static Instant requireInstant(ResultSet rows, String field) {
        try {
            var value = rows.getTimestamp(field);
            if (value == null) throw new CraftingCommissionException("Missing commission browse field: " + field);
            return value.toInstant();
        } catch (SQLException exception) {
            throw new CraftingCommissionException("Could not read commission browse field: " + field, exception);
        }
    }

    private static final class MutableEntry {
        private final UUID commissionId;
        private final UUID requesterPlayerId;
        private final String recipeId;
        private final int recipeVersion;
        private final long paymentMinor;
        private final Instant createdAt;
        private final Map<String, Long> materials = new LinkedHashMap<>();

        private MutableEntry(
                UUID commissionId,
                UUID requesterPlayerId,
                String recipeId,
                int recipeVersion,
                long paymentMinor,
                Instant createdAt
        ) {
            this.commissionId = commissionId;
            this.requesterPlayerId = requesterPlayerId;
            this.recipeId = recipeId;
            this.recipeVersion = recipeVersion;
            this.paymentMinor = paymentMinor;
            this.createdAt = createdAt;
        }
    }
}

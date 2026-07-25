package io.github.kevinrabbe.minecraftserver.common.economy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded read-only active-listing projection. This repository never owns Auction House state transitions. */
public final class AuctionHouseQueryRepository {
    private static final int MAX_BROWSE_LIMIT = 100;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Integer>> ROLL_MAP = new TypeReference<>() { };

    private final DataSource dataSource;

    public AuctionHouseQueryRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<AuctionBrowseListing> listActive(int limit) throws SQLException {
        if (limit < 1 || limit > MAX_BROWSE_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_BROWSE_LIMIT);
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT l.listing_id,
                            l.seller_player_id,
                            l.item_instance_id,
                            l.price_minor,
                            l.created_at,
                            i.definition_id,
                            i.roll_state::TEXT AS roll_state
                     FROM auction_listings l
                     JOIN item_instances i ON i.item_instance_id = l.item_instance_id
                     WHERE l.status = 'ACTIVE'
                     ORDER BY l.created_at DESC, l.listing_id
                     LIMIT ?
                     """)) {
            statement.setInt(1, limit);
            try (ResultSet row = statement.executeQuery()) {
                java.util.ArrayList<AuctionBrowseListing> results = new java.util.ArrayList<>();
                while (row.next()) {
                    results.add(new AuctionBrowseListing(
                            row.getObject("listing_id", java.util.UUID.class),
                            row.getObject("seller_player_id", java.util.UUID.class),
                            row.getObject("item_instance_id", java.util.UUID.class),
                            row.getString("definition_id"),
                            row.getLong("price_minor"),
                            parseRollState(row.getString("roll_state")),
                            row.getTimestamp("created_at").toInstant()
                    ));
                }
                return List.copyOf(results);
            }
        }
    }

    private static Map<String, Integer> parseRollState(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Integer> parsed = JSON.readValue(json, ROLL_MAP);
            return parsed == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(parsed));
        } catch (JsonProcessingException exception) {
            throw new AuctionHouseException("Invalid persisted Auction House roll_state", exception);
        }
    }
}

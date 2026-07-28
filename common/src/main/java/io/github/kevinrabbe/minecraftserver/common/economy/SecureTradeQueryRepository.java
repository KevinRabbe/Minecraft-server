package io.github.kevinrabbe.minecraftserver.common.economy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Read-only, bounded, transactionally consistent secure-trade offer projection. */
public final class SecureTradeQueryRepository {
    private static final int MAX_COMMODITY_ROWS = 100;
    private static final int MAX_UNIQUE_ROWS = 100;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Integer>> ROLL_MAP = new TypeReference<>() { };

    private final DataSource dataSource;

    public SecureTradeQueryRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public SecureTradeOfferView loadOffer(UUID tradeId) throws SQLException {
        Objects.requireNonNull(tradeId, "tradeId");
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                SecureTradeSnapshot trade = readTrade(connection, tradeId);
                Map<UUID, Long> coins = readCoins(connection, trade);
                List<SecureTradeCommodityOffer> commodities = readCommodities(connection, trade);
                List<SecureTradeUniqueOffer> uniqueItems = readUniqueItems(connection, trade);
                SecureTradeOfferView result = new SecureTradeOfferView(trade, coins, commodities, uniqueItems);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static SecureTradeSnapshot readTrade(Connection connection, UUID tradeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_a_id,
                       player_b_id,
                       status,
                       revision,
                       player_a_confirmed_revision,
                       player_b_confirmed_revision,
                       created_at,
                       updated_at,
                       settled_at
                FROM secure_trades
                WHERE trade_id = ?
                """)) {
            statement.setObject(1, tradeId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SecureTradeException("Unknown secure trade: " + tradeId);
                }
                Timestamp settledAt = row.getTimestamp("settled_at");
                return new SecureTradeSnapshot(
                        tradeId,
                        row.getObject("player_a_id", UUID.class),
                        row.getObject("player_b_id", UUID.class),
                        SecureTradeStatus.valueOf(row.getString("status")),
                        row.getLong("revision"),
                        row.getObject("player_a_confirmed_revision", Long.class),
                        row.getObject("player_b_confirmed_revision", Long.class),
                        row.getTimestamp("created_at").toInstant(),
                        row.getTimestamp("updated_at").toInstant(),
                        settledAt == null ? null : settledAt.toInstant()
                );
            }
        }
    }

    private static Map<UUID, Long> readCoins(Connection connection, SecureTradeSnapshot trade) throws SQLException {
        LinkedHashMap<UUID, Long> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner_player_id, amount_minor
                FROM secure_trade_coin_escrow
                WHERE trade_id = ?
                ORDER BY owner_player_id
                """)) {
            statement.setObject(1, trade.tradeId());
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    UUID owner = row.getObject("owner_player_id", UUID.class);
                    long amount = row.getLong("amount_minor");
                    if (!trade.participant(owner) || amount <= 0 || result.put(owner, amount) != null) {
                        throw new SecureTradeException("invalid persisted secure-trade Coin offer");
                    }
                }
            }
        }
        return Map.copyOf(result);
    }

    private static List<SecureTradeCommodityOffer> readCommodities(
            Connection connection,
            SecureTradeSnapshot trade
    ) throws SQLException {
        ArrayList<SecureTradeCommodityOffer> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner_player_id, commodity_definition_id, quantity
                FROM secure_trade_commodity_escrow
                WHERE trade_id = ?
                ORDER BY owner_player_id, commodity_definition_id
                LIMIT ?
                """)) {
            statement.setObject(1, trade.tradeId());
            statement.setInt(2, MAX_COMMODITY_ROWS + 1);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    if (result.size() == MAX_COMMODITY_ROWS) {
                        throw new SecureTradeException(
                                "secure trade has too many commodity rows to display safely"
                        );
                    }
                    result.add(new SecureTradeCommodityOffer(
                            row.getObject("owner_player_id", UUID.class),
                            row.getString("commodity_definition_id"),
                            row.getLong("quantity")
                    ));
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<SecureTradeUniqueOffer> readUniqueItems(
            Connection connection,
            SecureTradeSnapshot trade
    ) throws SQLException {
        ArrayList<SecureTradeUniqueOffer> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT u.owner_player_id,
                       u.item_instance_id,
                       u.escrow_item_version,
                       i.definition_id,
                       i.roll_state::TEXT AS roll_state,
                       i.location_kind,
                       i.location_id,
                       i.state_version
                FROM secure_trade_unique_items u
                JOIN item_instances i ON i.item_instance_id = u.item_instance_id
                WHERE u.trade_id = ?
                ORDER BY u.owner_player_id, u.item_instance_id
                LIMIT ?
                """)) {
            statement.setObject(1, trade.tradeId());
            statement.setInt(2, MAX_UNIQUE_ROWS + 1);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    if (result.size() == MAX_UNIQUE_ROWS) {
                        throw new SecureTradeException(
                                "secure trade has too many unique items to display safely"
                        );
                    }
                    UUID itemInstanceId = row.getObject("item_instance_id", UUID.class);
                    long escrowVersion = row.getLong("escrow_item_version");
                    if (!"TRADE_ESCROW".equals(row.getString("location_kind"))
                            || !trade.tradeId().equals(row.getObject("location_id", UUID.class))
                            || escrowVersion != row.getLong("state_version")) {
                        throw new SecureTradeException(
                                "secure-trade unique offer does not match authoritative item custody: " + itemInstanceId
                        );
                    }
                    result.add(new SecureTradeUniqueOffer(
                            row.getObject("owner_player_id", UUID.class),
                            itemInstanceId,
                            escrowVersion,
                            row.getString("definition_id"),
                            parseRollState(row.getString("roll_state"))
                    ));
                }
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, Integer> parseRollState(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Integer> parsed = JSON.readValue(json, ROLL_MAP);
            return parsed == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(parsed));
        } catch (JsonProcessingException exception) {
            throw new SecureTradeException("Invalid persisted secure-trade roll_state", exception);
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}

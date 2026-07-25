package io.github.kevinrabbe.minecraftserver.common.economy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Terminal settlement/cancellation authority for secure direct trades. */
public final class SecureTradeResolutionRepository {
    private static final String SETTLE_OPERATION = "SECURE_TRADE_SETTLE";
    private static final String CANCEL_OPERATION = "SECURE_TRADE_CANCEL";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;

    public SecureTradeResolutionRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Settles a LOCKED trade. Both confirmations already bind the exact current revision. */
    public SecureTradeResolutionResult settle(
            UUID operationId,
            UUID tradeId,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(tradeId, "tradeId");
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), SETTLE_OPERATION, operationId);
                    requireUuid(data, "trade_id", tradeId, operationId);
                    requireString(data, "reason", normalizedReason, operationId);
                    SecureTradeResolutionResult result = resolutionFrom(data);
                    connection.commit();
                    return result;
                }

                SecureTradeSnapshot trade = readTrade(connection, tradeId, true);
                if (trade.status() != SecureTradeStatus.LOCKED) {
                    throw new SecureTradeException("only LOCKED secure trades may settle: " + tradeId);
                }
                requireCurrentConfirmations(trade);

                Map<UUID, CoinWalletSnapshot> wallets = lockParticipantWallets(connection, trade);
                long aCoins = readCoinEscrow(connection, tradeId, trade.playerAId());
                long bCoins = readCoinEscrow(connection, tradeId, trade.playerBId());
                int ledgerLine = 0;
                if (aCoins > 0) {
                    creditWallet(connection, wallets, trade.playerBId(), aCoins);
                    insertLedger(
                            connection,
                            operationId,
                            ledgerLine++,
                            trade.playerBId(),
                            CoinCurrency.LEDGER_ASSET_TYPE,
                            CoinCurrency.LEDGER_ASSET_ID,
                            aCoins,
                            normalizedReason
                    );
                }
                if (bCoins > 0) {
                    creditWallet(connection, wallets, trade.playerAId(), bCoins);
                    insertLedger(
                            connection,
                            operationId,
                            ledgerLine++,
                            trade.playerAId(),
                            CoinCurrency.LEDGER_ASSET_TYPE,
                            CoinCurrency.LEDGER_ASSET_ID,
                            bCoins,
                            normalizedReason
                    );
                }

                List<SecureTradeDeliverySnapshot> deliveries = new ArrayList<>();
                int deliveryOrdinal = 0;
                for (CommodityEscrow commodity : readCommodityEscrow(connection, tradeId)) {
                    UUID recipient = trade.otherParticipant(commodity.ownerPlayerId());
                    UUID deliveryId = deterministicUuid(operationId, "commodity-delivery", deliveryOrdinal);
                    UUID sourceOperationId = deterministicUuid(operationId, "commodity-source", deliveryOrdinal);
                    insertCommodityDelivery(
                            connection,
                            deliveryId,
                            recipient,
                            commodity.definitionId(),
                            commodity.quantity(),
                            sourceOperationId
                    );
                    Instant createdAt = insertTradeCommodityDeliveryEvidence(
                            connection,
                            tradeId,
                            deliveryId,
                            commodity.ownerPlayerId(),
                            recipient,
                            commodity.definitionId(),
                            commodity.quantity()
                    );
                    insertLedger(
                            connection,
                            operationId,
                            ledgerLine++,
                            recipient,
                            "COMMODITY",
                            commodity.definitionId(),
                            commodity.quantity(),
                            normalizedReason
                    );
                    deliveries.add(new SecureTradeDeliverySnapshot(
                            tradeId,
                            deliveryId,
                            SecureTradeDeliveryKind.COMMODITY,
                            commodity.ownerPlayerId(),
                            recipient,
                            null,
                            commodity.definitionId(),
                            commodity.quantity(),
                            createdAt
                    ));
                    deliveryOrdinal++;
                }

                for (UniqueEscrow item : readUniqueEscrow(connection, tradeId)) {
                    UUID recipient = trade.otherParticipant(item.ownerPlayerId());
                    UUID deliveryId = deterministicUuid(operationId, "unique-delivery", deliveryOrdinal);
                    UUID issueOperationId = deterministicUuid(operationId, "unique-issue", deliveryOrdinal);
                    long nextItemVersion = moveTradeItemToPendingDelivery(
                            connection,
                            tradeId,
                            item,
                            deliveryId,
                            recipient,
                            issueOperationId,
                            normalizedReason
                    );
                    insertLedger(
                            connection,
                            operationId,
                            ledgerLine++,
                            recipient,
                            "ITEM_INSTANCE",
                            item.itemInstanceId().toString(),
                            1,
                            normalizedReason
                    );
                    Instant createdAt = insertTradeUniqueDeliveryEvidence(
                            connection,
                            tradeId,
                            deliveryId,
                            item.ownerPlayerId(),
                            recipient,
                            item.itemInstanceId()
                    );
                    deliveries.add(new SecureTradeDeliverySnapshot(
                            tradeId,
                            deliveryId,
                            SecureTradeDeliveryKind.UNIQUE_ITEM,
                            item.ownerPlayerId(),
                            recipient,
                            item.itemInstanceId(),
                            null,
                            null,
                            createdAt
                    ));
                    requireItemVersion(connection, item.itemInstanceId(), nextItemVersion);
                    deliveryOrdinal++;
                }

                markSettled(connection, tradeId, operationId);
                SecureTradeSnapshot settled = readTrade(connection, tradeId, false);
                Map<UUID, Long> balances = walletBalances(wallets);
                SecureTradeResolutionResult result = new SecureTradeResolutionResult(
                        settled,
                        balances,
                        deliveries
                );
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("trade_id", tradeId.toString());
                data.put("reason", normalizedReason);
                data.put("trade", tradeMap(settled));
                data.put("wallet_balances_minor", walletBalanceMap(balances));
                data.put("deliveries", deliveries.stream().map(SecureTradeResolutionRepository::deliveryMap).toList());
                insertProcessed(connection, operationId, SETTLE_OPERATION, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Cancels an OPEN trade and returns every escrowed asset through ordinary durable custody paths. */
    public SecureTradeResolutionResult cancel(
            UUID operationId,
            UUID tradeId,
            UUID cancellingPlayerId,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(cancellingPlayerId, "cancellingPlayerId");
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), CANCEL_OPERATION, operationId);
                    requireUuid(data, "trade_id", tradeId, operationId);
                    requireUuid(data, "cancelling_player_id", cancellingPlayerId, operationId);
                    requireString(data, "reason", normalizedReason, operationId);
                    SecureTradeResolutionResult result = resolutionFrom(data);
                    connection.commit();
                    return result;
                }

                SecureTradeSnapshot trade = readTrade(connection, tradeId, true);
                if (!trade.participant(cancellingPlayerId)) {
                    throw new SecureTradeException("only a trade participant may cancel: " + cancellingPlayerId);
                }
                if (trade.status() != SecureTradeStatus.OPEN) {
                    throw new SecureTradeException("only OPEN secure trades may cancel: " + tradeId);
                }

                Map<UUID, CoinWalletSnapshot> wallets = lockParticipantWallets(connection, trade);
                int ledgerLine = 0;
                for (UUID owner : List.of(trade.playerAId(), trade.playerBId())) {
                    long coins = readCoinEscrow(connection, tradeId, owner);
                    if (coins > 0) {
                        creditWallet(connection, wallets, owner, coins);
                        insertLedger(
                                connection,
                                operationId,
                                ledgerLine++,
                                owner,
                                CoinCurrency.LEDGER_ASSET_TYPE,
                                CoinCurrency.LEDGER_ASSET_ID,
                                coins,
                                normalizedReason
                        );
                    }
                }

                List<SecureTradeDeliverySnapshot> deliveries = new ArrayList<>();
                int deliveryOrdinal = 0;
                for (CommodityEscrow commodity : readCommodityEscrow(connection, tradeId)) {
                    UUID deliveryId = deterministicUuid(operationId, "commodity-return-delivery", deliveryOrdinal);
                    UUID sourceOperationId = deterministicUuid(operationId, "commodity-return-source", deliveryOrdinal);
                    insertCommodityDelivery(
                            connection,
                            deliveryId,
                            commodity.ownerPlayerId(),
                            commodity.definitionId(),
                            commodity.quantity(),
                            sourceOperationId
                    );
                    Instant createdAt = insertTradeCommodityDeliveryEvidence(
                            connection,
                            tradeId,
                            deliveryId,
                            commodity.ownerPlayerId(),
                            commodity.ownerPlayerId(),
                            commodity.definitionId(),
                            commodity.quantity()
                    );
                    insertLedger(
                            connection,
                            operationId,
                            ledgerLine++,
                            commodity.ownerPlayerId(),
                            "COMMODITY",
                            commodity.definitionId(),
                            commodity.quantity(),
                            normalizedReason
                    );
                    deliveries.add(new SecureTradeDeliverySnapshot(
                            tradeId,
                            deliveryId,
                            SecureTradeDeliveryKind.COMMODITY,
                            commodity.ownerPlayerId(),
                            commodity.ownerPlayerId(),
                            null,
                            commodity.definitionId(),
                            commodity.quantity(),
                            createdAt
                    ));
                    deliveryOrdinal++;
                }

                for (UniqueEscrow item : readUniqueEscrow(connection, tradeId)) {
                    UUID deliveryId = deterministicUuid(operationId, "unique-return-delivery", deliveryOrdinal);
                    UUID issueOperationId = deterministicUuid(operationId, "unique-return-issue", deliveryOrdinal);
                    long nextItemVersion = moveTradeItemToPendingDelivery(
                            connection,
                            tradeId,
                            item,
                            deliveryId,
                            item.ownerPlayerId(),
                            issueOperationId,
                            normalizedReason
                    );
                    insertLedger(
                            connection,
                            operationId,
                            ledgerLine++,
                            item.ownerPlayerId(),
                            "ITEM_INSTANCE",
                            item.itemInstanceId().toString(),
                            1,
                            normalizedReason
                    );
                    Instant createdAt = insertTradeUniqueDeliveryEvidence(
                            connection,
                            tradeId,
                            deliveryId,
                            item.ownerPlayerId(),
                            item.ownerPlayerId(),
                            item.itemInstanceId()
                    );
                    deliveries.add(new SecureTradeDeliverySnapshot(
                            tradeId,
                            deliveryId,
                            SecureTradeDeliveryKind.UNIQUE_ITEM,
                            item.ownerPlayerId(),
                            item.ownerPlayerId(),
                            item.itemInstanceId(),
                            null,
                            null,
                            createdAt
                    ));
                    requireItemVersion(connection, item.itemInstanceId(), nextItemVersion);
                    deliveryOrdinal++;
                }

                markCancelled(connection, tradeId, operationId);
                SecureTradeSnapshot cancelled = readTrade(connection, tradeId, false);
                Map<UUID, Long> balances = walletBalances(wallets);
                SecureTradeResolutionResult result = new SecureTradeResolutionResult(
                        cancelled,
                        balances,
                        deliveries
                );
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("trade_id", tradeId.toString());
                data.put("cancelling_player_id", cancellingPlayerId.toString());
                data.put("reason", normalizedReason);
                data.put("trade", tradeMap(cancelled));
                data.put("wallet_balances_minor", walletBalanceMap(balances));
                data.put("deliveries", deliveries.stream().map(SecureTradeResolutionRepository::deliveryMap).toList());
                insertProcessed(connection, operationId, CANCEL_OPERATION, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static SecureTradeSnapshot readTrade(Connection connection, UUID tradeId, boolean forUpdate)
            throws SQLException {
        String sql = """
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
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
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

    private static void requireCurrentConfirmations(SecureTradeSnapshot trade) {
        Long revision = trade.revision();
        if (!revision.equals(trade.playerAConfirmedRevision())
                || !revision.equals(trade.playerBConfirmedRevision())) {
            throw new SecureTradeException("locked secure trade does not have matching current confirmations");
        }
    }

    private static Map<UUID, CoinWalletSnapshot> lockParticipantWallets(
            Connection connection,
            SecureTradeSnapshot trade
    ) throws SQLException {
        List<UUID> ids = new ArrayList<>(List.of(trade.playerAId(), trade.playerBId()));
        ids.sort(Comparator.comparing(UUID::toString));
        HashMap<UUID, CoinWalletSnapshot> result = new HashMap<>();
        for (UUID playerId : ids) {
            result.put(playerId, readWallet(connection, playerId, true));
        }
        return result;
    }

    private static CoinWalletSnapshot readWallet(Connection connection, UUID playerId, boolean forUpdate)
            throws SQLException {
        String sql = """
                SELECT balance_minor, state_version
                FROM wallets
                WHERE player_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SecureTradeException("Wallet does not exist for player_id " + playerId);
                }
                return new CoinWalletSnapshot(
                        playerId,
                        row.getLong("balance_minor"),
                        row.getLong("state_version")
                );
            }
        }
    }

    private static void creditWallet(
            Connection connection,
            Map<UUID, CoinWalletSnapshot> wallets,
            UUID playerId,
            long amountMinor
    ) throws SQLException {
        CoinWalletSnapshot current = wallets.get(playerId);
        if (current == null) {
            throw new SecureTradeException("missing locked participant wallet: " + playerId);
        }
        long nextBalance;
        long nextVersion;
        try {
            nextBalance = Math.addExact(current.balanceMinor(), amountMinor);
            nextVersion = Math.addExact(current.stateVersion(), 1L);
        } catch (ArithmeticException exception) {
            throw new SecureTradeException("wallet overflow during secure-trade resolution", exception);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE wallets
                SET balance_minor = ?, state_version = ?, updated_at = NOW()
                WHERE player_id = ? AND state_version = ?
                """)) {
            statement.setLong(1, nextBalance);
            statement.setLong(2, nextVersion);
            statement.setObject(3, playerId);
            statement.setLong(4, current.stateVersion());
            if (statement.executeUpdate() != 1) {
                throw new SecureTradeException("wallet changed concurrently during secure-trade resolution");
            }
        }
        wallets.put(playerId, new CoinWalletSnapshot(playerId, nextBalance, nextVersion));
    }

    private static Map<UUID, Long> walletBalances(Map<UUID, CoinWalletSnapshot> wallets) {
        LinkedHashMap<UUID, Long> result = new LinkedHashMap<>();
        wallets.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().balanceMinor()));
        return Map.copyOf(result);
    }

    private static long readCoinEscrow(Connection connection, UUID tradeId, UUID ownerPlayerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT amount_minor
                FROM secure_trade_coin_escrow
                WHERE trade_id = ? AND owner_player_id = ?
                """)) {
            statement.setObject(1, tradeId);
            statement.setObject(2, ownerPlayerId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getLong("amount_minor") : 0L;
            }
        }
    }

    private static List<CommodityEscrow> readCommodityEscrow(Connection connection, UUID tradeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner_player_id, commodity_definition_id, quantity
                FROM secure_trade_commodity_escrow
                WHERE trade_id = ?
                ORDER BY owner_player_id ASC, commodity_definition_id ASC
                """)) {
            statement.setObject(1, tradeId);
            try (ResultSet rows = statement.executeQuery()) {
                List<CommodityEscrow> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new CommodityEscrow(
                            rows.getObject("owner_player_id", UUID.class),
                            rows.getString("commodity_definition_id"),
                            rows.getLong("quantity")
                    ));
                }
                return List.copyOf(result);
            }
        }
    }

    private static List<UniqueEscrow> readUniqueEscrow(Connection connection, UUID tradeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner_player_id, item_instance_id, escrow_item_version
                FROM secure_trade_unique_items
                WHERE trade_id = ?
                ORDER BY item_instance_id ASC
                """)) {
            statement.setObject(1, tradeId);
            try (ResultSet rows = statement.executeQuery()) {
                List<UniqueEscrow> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new UniqueEscrow(
                            rows.getObject("owner_player_id", UUID.class),
                            rows.getObject("item_instance_id", UUID.class),
                            rows.getLong("escrow_item_version")
                    ));
                }
                return List.copyOf(result);
            }
        }
    }

    private static void insertCommodityDelivery(
            Connection connection,
            UUID deliveryId,
            UUID recipientPlayerId,
            String definitionId,
            long quantity,
            UUID sourceOperationId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO pending_commodity_deliveries(
                    delivery_id,
                    player_id,
                    commodity_definition_id,
                    quantity,
                    source_operation_id,
                    status
                ) VALUES (?, ?, ?, ?, ?, 'PENDING')
                """)) {
            statement.setObject(1, deliveryId);
            statement.setObject(2, recipientPlayerId);
            statement.setString(3, definitionId);
            statement.setLong(4, quantity);
            statement.setObject(5, sourceOperationId);
            statement.executeUpdate();
        }
    }

    private static long moveTradeItemToPendingDelivery(
            Connection connection,
            UUID tradeId,
            UniqueEscrow item,
            UUID deliveryId,
            UUID recipientPlayerId,
            UUID issueOperationId,
            String reason
    ) throws SQLException {
        LockedItem current = lockItem(connection, item.itemInstanceId());
        if (!"TRADE_ESCROW".equals(current.locationKind())
                || !tradeId.equals(current.locationId())
                || current.stateVersion() != item.escrowItemVersion()) {
            throw new SecureTradeException("secure-trade unique-item custody is inconsistent: " + item.itemInstanceId());
        }
        long nextVersion;
        try {
            nextVersion = Math.addExact(current.stateVersion(), 1L);
        } catch (ArithmeticException exception) {
            throw new SecureTradeException("unique-item state_version overflow", exception);
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO pending_unique_deliveries(
                    delivery_id,
                    recipient_player_id,
                    item_instance_id,
                    status,
                    issue_operation_id,
                    issue_reason
                ) VALUES (?, ?, ?, 'PENDING', ?, ?)
                """)) {
            statement.setObject(1, deliveryId);
            statement.setObject(2, recipientPlayerId);
            statement.setObject(3, item.itemInstanceId());
            statement.setObject(4, issueOperationId);
            statement.setString(5, reason);
            statement.executeUpdate();
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE item_instances
                SET location_kind = 'PENDING_DELIVERY',
                    location_id = ?,
                    state_version = ?,
                    updated_at = NOW()
                WHERE item_instance_id = ?
                  AND location_kind = 'TRADE_ESCROW'
                  AND location_id = ?
                  AND state_version = ?
                """)) {
            statement.setObject(1, deliveryId);
            statement.setLong(2, nextVersion);
            statement.setObject(3, item.itemInstanceId());
            statement.setObject(4, tradeId);
            statement.setLong(5, current.stateVersion());
            if (statement.executeUpdate() != 1) {
                throw new SecureTradeException("unique-item custody changed during secure-trade resolution");
            }
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO item_provenance(
                    item_instance_id,
                    sequence_no,
                    operation_id,
                    event_type,
                    from_location_kind,
                    from_location_id,
                    to_location_kind,
                    to_location_id,
                    reason,
                    actor_player_id
                ) VALUES (?, ?, ?, 'MOVED', 'TRADE_ESCROW', ?, 'PENDING_DELIVERY', ?, ?, NULL)
                """)) {
            statement.setObject(1, item.itemInstanceId());
            statement.setLong(2, nextVersion);
            statement.setObject(3, issueOperationId);
            statement.setObject(4, tradeId);
            statement.setObject(5, deliveryId);
            statement.setString(6, reason);
            statement.executeUpdate();
        }
        return nextVersion;
    }

    private static LockedItem lockItem(Connection connection, UUID itemInstanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT location_kind, location_id, state_version
                FROM item_instances
                WHERE item_instance_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, itemInstanceId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SecureTradeException("Unknown secure-trade item: " + itemInstanceId);
                }
                return new LockedItem(
                        row.getString("location_kind"),
                        row.getObject("location_id", UUID.class),
                        row.getLong("state_version")
                );
            }
        }
    }

    private static void requireItemVersion(Connection connection, UUID itemInstanceId, long expectedVersion)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT state_version
                FROM item_instances
                WHERE item_instance_id = ?
                """)) {
            statement.setObject(1, itemInstanceId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || row.getLong("state_version") != expectedVersion) {
                    throw new SecureTradeException("resolved trade item version is inconsistent: " + itemInstanceId);
                }
            }
        }
    }

    private static Instant insertTradeCommodityDeliveryEvidence(
            Connection connection,
            UUID tradeId,
            UUID deliveryId,
            UUID sourceOwner,
            UUID recipient,
            String definitionId,
            long quantity
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO secure_trade_deliveries(
                    trade_id,
                    delivery_id,
                    delivery_kind,
                    source_owner_player_id,
                    recipient_player_id,
                    commodity_definition_id,
                    quantity
                ) VALUES (?, ?, 'COMMODITY', ?, ?, ?, ?)
                RETURNING created_at
                """)) {
            statement.setObject(1, tradeId);
            statement.setObject(2, deliveryId);
            statement.setObject(3, sourceOwner);
            statement.setObject(4, recipient);
            statement.setString(5, definitionId);
            statement.setLong(6, quantity);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getTimestamp("created_at").toInstant();
            }
        }
    }

    private static Instant insertTradeUniqueDeliveryEvidence(
            Connection connection,
            UUID tradeId,
            UUID deliveryId,
            UUID sourceOwner,
            UUID recipient,
            UUID itemInstanceId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO secure_trade_deliveries(
                    trade_id,
                    delivery_id,
                    delivery_kind,
                    source_owner_player_id,
                    recipient_player_id,
                    item_instance_id
                ) VALUES (?, ?, 'UNIQUE_ITEM', ?, ?, ?)
                RETURNING created_at
                """)) {
            statement.setObject(1, tradeId);
            statement.setObject(2, deliveryId);
            statement.setObject(3, sourceOwner);
            statement.setObject(4, recipient);
            statement.setObject(5, itemInstanceId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getTimestamp("created_at").toInstant();
            }
        }
    }

    private static void insertLedger(
            Connection connection,
            UUID operationId,
            int lineNo,
            UUID playerId,
            String assetType,
            String assetId,
            long amount,
            String reason
    ) throws SQLException {
        if (amount <= 0) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO economic_ledger(
                    operation_id, line_no, player_id, asset_type, asset_id, amount, direction, reason
                ) VALUES (?, ?, ?, ?, ?, ?, 'CREDIT', ?)
                """)) {
            statement.setObject(1, operationId);
            statement.setInt(2, lineNo);
            statement.setObject(3, playerId);
            statement.setString(4, assetType);
            statement.setString(5, assetId);
            statement.setLong(6, amount);
            statement.setString(7, reason);
            statement.executeUpdate();
        }
    }

    private static void markSettled(Connection connection, UUID tradeId, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE secure_trades
                SET status = 'SETTLED',
                    settle_operation_id = ?,
                    settled_at = NOW(),
                    updated_at = NOW()
                WHERE trade_id = ?
                  AND status = 'LOCKED'
                  AND player_a_confirmed_revision = revision
                  AND player_b_confirmed_revision = revision
                """)) {
            statement.setObject(1, operationId);
            statement.setObject(2, tradeId);
            if (statement.executeUpdate() != 1) {
                throw new SecureTradeException("secure trade changed concurrently during settlement");
            }
        }
    }

    private static void markCancelled(Connection connection, UUID tradeId, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE secure_trades
                SET status = 'CANCELLED',
                    cancel_operation_id = ?,
                    settled_at = NOW(),
                    updated_at = NOW()
                WHERE trade_id = ? AND status = 'OPEN'
                """)) {
            statement.setObject(1, operationId);
            statement.setObject(2, tradeId);
            if (statement.executeUpdate() != 1) {
                throw new SecureTradeException("secure trade changed concurrently during cancellation");
            }
        }
    }

    private static UUID deterministicUuid(UUID operationId, String purpose, int ordinal) {
        return UUID.nameUUIDFromBytes(
                ("minecraft-server:secure-trade:" + operationId + ":" + purpose + ":" + ordinal)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    private static Optional<ProcessedOperation> findProcessed(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type, result::text AS result_json
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ProcessedOperation(
                        row.getString("operation_type"),
                        readJsonMap(row.getString("result_json"))
                ));
            }
        }
    }

    private static void insertProcessed(
            Connection connection,
            UUID operationId,
            String operationType,
            Map<String, Object> result
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, ?::jsonb)
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, operationType);
            statement.setString(3, writeJson(result));
            statement.executeUpdate();
        }
    }

    private static Map<String, Object> requireType(
            ProcessedOperation operation,
            String expectedType,
            UUID operationId
    ) {
        if (!expectedType.equals(operation.operationType())) {
            throw new SecureTradeException(
                    "operation_id " + operationId + " already belongs to operation type " + operation.operationType()
            );
        }
        return operation.result();
    }

    private static Map<String, Object> tradeMap(SecureTradeSnapshot trade) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("trade_id", trade.tradeId().toString());
        value.put("player_a_id", trade.playerAId().toString());
        value.put("player_b_id", trade.playerBId().toString());
        value.put("status", trade.status().name());
        value.put("revision", trade.revision());
        value.put("player_a_confirmed_revision", trade.playerAConfirmedRevision());
        value.put("player_b_confirmed_revision", trade.playerBConfirmedRevision());
        value.put("created_at", trade.createdAt().toString());
        value.put("updated_at", trade.updatedAt().toString());
        value.put("settled_at", trade.settledAt() == null ? null : trade.settledAt().toString());
        return value;
    }

    private static SecureTradeSnapshot tradeFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "trade");
        return new SecureTradeSnapshot(
                uuidValue(value, "trade_id"),
                uuidValue(value, "player_a_id"),
                uuidValue(value, "player_b_id"),
                SecureTradeStatus.valueOf(stringValue(value, "status")),
                longValue(value, "revision"),
                nullableLong(value, "player_a_confirmed_revision"),
                nullableLong(value, "player_b_confirmed_revision"),
                Instant.parse(stringValue(value, "created_at")),
                Instant.parse(stringValue(value, "updated_at")),
                nullableInstant(value, "settled_at")
        );
    }

    private static Map<String, Object> walletBalanceMap(Map<UUID, Long> balances) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        balances.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
                .forEach(entry -> result.put(entry.getKey().toString(), entry.getValue()));
        return result;
    }

    private static Map<String, Object> deliveryMap(SecureTradeDeliverySnapshot delivery) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("trade_id", delivery.tradeId().toString());
        value.put("delivery_id", delivery.deliveryId().toString());
        value.put("kind", delivery.kind().name());
        value.put("source_owner_player_id", delivery.sourceOwnerPlayerId().toString());
        value.put("recipient_player_id", delivery.recipientPlayerId().toString());
        value.put("item_instance_id", delivery.itemInstanceId() == null ? null : delivery.itemInstanceId().toString());
        value.put("commodity_definition_id", delivery.commodityDefinitionId());
        value.put("quantity", delivery.quantity());
        value.put("created_at", delivery.createdAt().toString());
        return value;
    }

    private static SecureTradeResolutionResult resolutionFrom(Map<String, Object> data) {
        SecureTradeSnapshot trade = tradeFrom(data.get("trade"));
        Map<String, Object> rawBalances = objectMap(data.get("wallet_balances_minor"), "wallet_balances_minor");
        LinkedHashMap<UUID, Long> balances = new LinkedHashMap<>();
        rawBalances.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (!(entry.getValue() instanceof Number number)) {
                        throw new SecureTradeException("wallet balance result is not numeric");
                    }
                    balances.put(UUID.fromString(entry.getKey()), number.longValue());
                });

        Object rawDeliveries = data.get("deliveries");
        if (!(rawDeliveries instanceof List<?> list)) {
            throw new SecureTradeException("secure-trade resolution deliveries are not a list");
        }
        List<SecureTradeDeliverySnapshot> deliveries = new ArrayList<>();
        for (Object raw : list) {
            Map<String, Object> value = objectMap(raw, "delivery");
            String itemId = nullableString(value, "item_instance_id");
            String commodityId = nullableString(value, "commodity_definition_id");
            Long quantity = nullableLong(value, "quantity");
            deliveries.add(new SecureTradeDeliverySnapshot(
                    uuidValue(value, "trade_id"),
                    uuidValue(value, "delivery_id"),
                    SecureTradeDeliveryKind.valueOf(stringValue(value, "kind")),
                    uuidValue(value, "source_owner_player_id"),
                    uuidValue(value, "recipient_player_id"),
                    itemId == null ? null : UUID.fromString(itemId),
                    commodityId,
                    quantity,
                    Instant.parse(stringValue(value, "created_at"))
            ));
        }
        return new SecureTradeResolutionResult(trade, balances, deliveries);
    }

    private static Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new SecureTradeException("Could not parse secure-trade resolution idempotency result", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new SecureTradeException("Could not serialize secure-trade resolution idempotency result", exception);
        }
    }

    private static Map<String, Object> objectMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new SecureTradeException("secure-trade resolution field is not an object: " + field);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    private static String stringValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) {
            throw new SecureTradeException("secure-trade resolution result is missing field: " + field);
        }
        return Objects.toString(raw);
    }

    private static String nullableString(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        return raw == null ? null : Objects.toString(raw);
    }

    private static long longValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof Number number)) {
            throw new SecureTradeException("secure-trade resolution field is not numeric: " + field);
        }
        return number.longValue();
    }

    private static Long nullableLong(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number number)) {
            throw new SecureTradeException("secure-trade resolution field is not numeric: " + field);
        }
        return number.longValue();
    }

    private static UUID uuidValue(Map<String, Object> value, String field) {
        return UUID.fromString(stringValue(value, field));
    }

    private static Instant nullableInstant(Map<String, Object> value, String field) {
        String raw = nullableString(value, field);
        return raw == null ? null : Instant.parse(raw);
    }

    private static void requireUuid(Map<String, Object> data, String field, UUID expected, UUID operationId) {
        if (!uuidValue(data, field).equals(expected)) {
            throw new SecureTradeException("operation_id reused with a different secure-trade request: " + operationId);
        }
    }

    private static void requireString(Map<String, Object> data, String field, String expected, UUID operationId) {
        if (!stringValue(data, field).equals(expected)) {
            throw new SecureTradeException("operation_id reused with a different secure-trade request: " + operationId);
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        String normalized = reason.trim();
        if (!REASON_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("reason must be a stable lowercase identifier: " + normalized);
        }
        return normalized;
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record CommodityEscrow(UUID ownerPlayerId, String definitionId, long quantity) {
    }

    private record UniqueEscrow(UUID ownerPlayerId, UUID itemInstanceId, long escrowItemVersion) {
    }

    private record LockedItem(String locationKind, UUID locationId, long stateVersion) {
    }

    private record ProcessedOperation(String operationType, Map<String, Object> result) {
        private ProcessedOperation {
            operationType = Objects.requireNonNull(operationType, "operationType");
            result = Map.copyOf(Objects.requireNonNull(result, "result"));
        }
    }
}

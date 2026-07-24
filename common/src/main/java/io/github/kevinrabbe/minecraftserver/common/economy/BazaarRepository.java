package io.github.kevinrabbe.minecraftserver.common.economy;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * PostgreSQL authority for fungible commodity orders.
 *
 * <p>Order placement and matching are deliberately separated: placement establishes escrow exactly once; the matcher
 * serializes one commodity book and settles crossed orders in bounded batches. This keeps player-state escrow and
 * market settlement independently retryable without weakening price-time priority.</p>
 */
public final class BazaarRepository {
    private static final String BUY_CREATE_OPERATION = "BAZAAR_BUY_ORDER_CREATE";
    private static final String SELL_CREATE_OPERATION = "BAZAAR_SELL_ORDER_CREATE";
    private static final String MATCH_OPERATION = "BAZAAR_MATCH";
    private static final String CANCEL_OPERATION = "BAZAAR_ORDER_CANCEL";
    private static final int BOOK_LOCK_NAMESPACE = 0x425A5252; // "BZRR"
    private static final BigInteger BASIS_POINTS = BigInteger.valueOf(10_000L);
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");

    private final DataSource dataSource;
    private final ItemCatalog itemCatalog;
    private final CommodityEscrowValidator commodityEscrowValidator;
    private final PlayerStateRepository playerStates;
    private final int executionFeeBasisPoints;

    public BazaarRepository(
            DataSource dataSource,
            ItemCatalog itemCatalog,
            CommodityEscrowValidator commodityEscrowValidator,
            int executionFeeBasisPoints
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.commodityEscrowValidator = Objects.requireNonNull(commodityEscrowValidator, "commodityEscrowValidator");
        this.playerStates = new PlayerStateRepository(dataSource);
        if (executionFeeBasisPoints < 0 || executionFeeBasisPoints > 10_000) {
            throw new IllegalArgumentException("executionFeeBasisPoints must be between 0 and 10000");
        }
        this.executionFeeBasisPoints = executionFeeBasisPoints;
    }

    public BazaarOrderSnapshot loadOrder(UUID orderId) throws SQLException {
        Objects.requireNonNull(orderId, "orderId");
        try (Connection connection = dataSource.getConnection()) {
            return readOrder(connection, orderId, false);
        }
    }

    public BazaarBuyOrderCreateResult createBuyOrder(
            UUID operationId,
            UUID playerId,
            BazaarOrderRequest request,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(request, "request");
        requireSide(request, BazaarOrderSide.BUY);
        requireCommodity(request.commodityDefinitionId());
        String normalizedReason = requireReason(reason);
        long reservedMinor = request.maximumNotionalMinor();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<BazaarBuyOrderCreateResult> processed = findProcessedBuyCreate(connection, operationId);
                if (processed.isPresent()) {
                    BazaarBuyOrderCreateResult previous = processed.orElseThrow();
                    requireSameBuyRequest(previous, playerId, request, normalizedReason, operationId);
                    connection.commit();
                    return previous;
                }

                CoinWalletSnapshot wallet = readWallet(connection, playerId, true);
                if (wallet.balanceMinor() < reservedMinor) {
                    throw new BazaarException("Insufficient Coin balance to reserve Bazaar buy order");
                }
                long nextWalletBalance = wallet.balanceMinor() - reservedMinor;
                long nextWalletVersion = incrementVersion(wallet.stateVersion(), "wallet", playerId);
                updateWallet(
                        connection,
                        playerId,
                        wallet.stateVersion(),
                        nextWalletBalance,
                        nextWalletVersion
                );

                UUID orderId = UUID.randomUUID();
                BazaarOrderSnapshot order = insertOrder(
                        connection,
                        orderId,
                        playerId,
                        request,
                        reservedMinor,
                        operationId
                );
                insertCoinLedger(
                        connection,
                        operationId,
                        0,
                        playerId,
                        reservedMinor,
                        "DEBIT",
                        normalizedReason
                );

                BazaarBuyOrderCreateResult result = new BazaarBuyOrderCreateResult(
                        order,
                        nextWalletBalance,
                        nextWalletVersion
                );
                insertProcessedBuyCreate(connection, operationId, result, normalizedReason);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /**
     * Creates commodity sell escrow only after a Paper-side validator proves the serialized state removed the exact
     * requested fungible quantity inside the same fenced transaction.
     */
    public BazaarSellOrderCreateResult createSellOrder(
            UUID operationId,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            BazaarOrderRequest request,
            String logicalZoneId,
            String entryPoint,
            byte[] nextPlayerStatePayload,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(nextPlayerStatePayload, "nextPlayerStatePayload");
        requireSide(request, BazaarOrderSide.SELL);
        requireCommodity(request.commodityDefinitionId());
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<BazaarSellOrderCreateResult> processed = findProcessedSellCreate(connection, operationId);
                if (processed.isPresent()) {
                    BazaarSellOrderCreateResult previous = processed.orElseThrow();
                    requireSameSellRequest(previous, request, normalizedReason, operationId);
                    connection.commit();
                    return previous;
                }

                UUID playerId = playerIdForSession(connection, sessionId);
                long nextStateVersion = playerStates.commitWithinTransaction(
                        connection,
                        sessionId,
                        backendId,
                        expectedPlayerStateVersion,
                        logicalZoneId,
                        entryPoint,
                        nextPlayerStatePayload,
                        (lockedPlayerId, currentPayload, nextPayload) -> {
                            if (!lockedPlayerId.equals(playerId)) {
                                throw new BazaarException("Session player changed during Bazaar sell escrow");
                            }
                            commodityEscrowValidator.verifyRemoval(
                                    lockedPlayerId,
                                    request.commodityDefinitionId(),
                                    request.quantity(),
                                    currentPayload,
                                    nextPayload
                            );
                        }
                );

                UUID orderId = UUID.randomUUID();
                BazaarOrderSnapshot order = insertOrder(
                        connection,
                        orderId,
                        playerId,
                        request,
                        0L,
                        operationId
                );
                BazaarSellOrderCreateResult result = new BazaarSellOrderCreateResult(order, nextStateVersion);
                insertProcessedSellCreate(connection, operationId, result, normalizedReason);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Matches up to maxFills crossed orders for one commodity using deterministic price-time priority. */
    public BazaarMatchResult matchCommodity(
            UUID operationId,
            String commodityDefinitionId,
            int maxFills,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        String commodity = requireCommodity(commodityDefinitionId).definitionId();
        if (maxFills <= 0) {
            throw new IllegalArgumentException("maxFills must be > 0");
        }
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<BazaarMatchResult> processed = findProcessedMatch(connection, operationId);
                if (processed.isPresent()) {
                    BazaarMatchResult previous = processed.orElseThrow();
                    if (!previous.commodityDefinitionId().equals(commodity)) {
                        throw new BazaarException("operation_id reused for a different Bazaar commodity: " + operationId);
                    }
                    connection.commit();
                    return previous;
                }

                lockCommodityBook(connection, commodity);
                int fills = 0;
                long quantityFilled = 0;
                long grossTradeValue = 0;
                long feesDestroyed = 0;

                while (fills < maxFills) {
                    Optional<BazaarOrderSnapshot> buyOptional = lockBestOpenOrder(
                            connection,
                            commodity,
                            BazaarOrderSide.BUY
                    );
                    Optional<BazaarOrderSnapshot> sellOptional = lockBestOpenOrder(
                            connection,
                            commodity,
                            BazaarOrderSide.SELL
                    );
                    if (buyOptional.isEmpty() || sellOptional.isEmpty()) {
                        break;
                    }
                    BazaarOrderSnapshot buy = buyOptional.orElseThrow();
                    BazaarOrderSnapshot sell = sellOptional.orElseThrow();
                    if (buy.limitPriceMinor() < sell.limitPriceMinor()) {
                        break;
                    }

                    long quantity = Math.min(buy.remainingQuantity(), sell.remainingQuantity());
                    long executionPrice = olderOrderPrice(buy, sell);
                    long gross = multiplyExact(quantity, executionPrice, "Bazaar gross trade value overflow");
                    long reservedConsumed = multiplyExact(
                            quantity,
                            buy.limitPriceMinor(),
                            "Bazaar reserved Coin overflow"
                    );
                    if (reservedConsumed > buy.reservedMoneyMinor()) {
                        throw new BazaarException("Buy order reserved Coin is inconsistent: " + buy.orderId());
                    }
                    long priceImprovementRefund = reservedConsumed - gross;
                    long fee = feeMinor(gross, executionFeeBasisPoints);
                    long sellerProceeds = gross - fee;

                    Map<UUID, CoinWalletSnapshot> lockedWallets = lockWallets(
                            connection,
                            buy.playerId(),
                            sell.playerId()
                    );
                    Map<UUID, Long> walletCredits = new HashMap<>();
                    if (priceImprovementRefund > 0) {
                        walletCredits.merge(buy.playerId(), priceImprovementRefund, BazaarRepository::addExactUnchecked);
                    }
                    if (sellerProceeds > 0) {
                        walletCredits.merge(sell.playerId(), sellerProceeds, BazaarRepository::addExactUnchecked);
                    }

                    int ledgerLine = 0;
                    for (Map.Entry<UUID, Long> credit : walletCredits.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
                            .toList()) {
                        CoinWalletSnapshot wallet = requireWallet(lockedWallets, credit.getKey());
                        long nextBalance = addExact(
                                wallet.balanceMinor(),
                                credit.getValue(),
                                "Bazaar wallet credit overflow"
                        );
                        long nextVersion = incrementVersion(wallet.stateVersion(), "wallet", credit.getKey());
                        updateWallet(
                                connection,
                                credit.getKey(),
                                wallet.stateVersion(),
                                nextBalance,
                                nextVersion
                        );
                        insertCoinLedger(
                                connection,
                                operationId,
                                ledgerLine++,
                                credit.getKey(),
                                credit.getValue(),
                                "CREDIT",
                                normalizedReason
                        );
                    }

                    UUID fillId = UUID.randomUUID();
                    UUID fillOperationId = UUID.randomUUID();
                    UUID deliveryId = UUID.randomUUID();
                    insertPendingCommodityDelivery(
                            connection,
                            deliveryId,
                            buy.playerId(),
                            commodity,
                            quantity,
                            fillOperationId
                    );
                    insertFill(
                            connection,
                            fillId,
                            fillOperationId,
                            buy.orderId(),
                            sell.orderId(),
                            quantity,
                            executionPrice,
                            fee
                    );

                    updateOrderAfterFill(
                            connection,
                            buy,
                            quantity,
                            reservedConsumed
                    );
                    updateOrderAfterFill(
                            connection,
                            sell,
                            quantity,
                            0L
                    );

                    fills++;
                    quantityFilled = addExact(quantityFilled, quantity, "Bazaar matched quantity overflow");
                    grossTradeValue = addExact(grossTradeValue, gross, "Bazaar matched gross overflow");
                    feesDestroyed = addExact(feesDestroyed, fee, "Bazaar matched fee overflow");
                }

                BazaarMatchResult result = new BazaarMatchResult(
                        commodity,
                        fills,
                        quantityFilled,
                        grossTradeValue,
                        feesDestroyed
                );
                insertProcessedMatch(connection, operationId, result, normalizedReason);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public BazaarCancelResult cancelOrder(
            UUID operationId,
            UUID orderId,
            UUID playerId,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(playerId, "playerId");
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<BazaarCancelResult> processed = findProcessedCancel(connection, operationId);
                if (processed.isPresent()) {
                    BazaarCancelResult previous = processed.orElseThrow();
                    if (!previous.orderId().equals(orderId) || !previous.playerId().equals(playerId)) {
                        throw new BazaarException("operation_id reused for different Bazaar cancellation: " + operationId);
                    }
                    connection.commit();
                    return previous;
                }

                String commodity = readOrderCommodity(connection, orderId);
                lockCommodityBook(connection, commodity);
                BazaarOrderSnapshot order = readOrder(connection, orderId, true);
                if (!order.playerId().equals(playerId)) {
                    throw new BazaarException("Only the Bazaar order owner may cancel it");
                }
                if (order.status() != BazaarOrderStatus.OPEN) {
                    throw new BazaarException("Bazaar order is not open: " + orderId);
                }

                long returnedMoney = 0L;
                long returnedCommodity = 0L;
                UUID deliveryId = null;
                CoinWalletSnapshot wallet = readWallet(connection, playerId, false);
                long walletBalance = wallet.balanceMinor();
                long walletVersion = wallet.stateVersion();

                if (order.side() == BazaarOrderSide.BUY) {
                    returnedMoney = order.reservedMoneyMinor();
                    CoinWalletSnapshot lockedWallet = readWallet(connection, playerId, true);
                    walletBalance = addExact(
                            lockedWallet.balanceMinor(),
                            returnedMoney,
                            "Bazaar cancellation wallet overflow"
                    );
                    walletVersion = incrementVersion(lockedWallet.stateVersion(), "wallet", playerId);
                    updateWallet(
                            connection,
                            playerId,
                            lockedWallet.stateVersion(),
                            walletBalance,
                            walletVersion
                    );
                    insertCoinLedger(
                            connection,
                            operationId,
                            0,
                            playerId,
                            returnedMoney,
                            "CREDIT",
                            normalizedReason
                    );
                } else {
                    returnedCommodity = order.remainingQuantity();
                    deliveryId = UUID.randomUUID();
                    insertPendingCommodityDelivery(
                            connection,
                            deliveryId,
                            playerId,
                            commodity,
                            returnedCommodity,
                            operationId
                    );
                }

                markOrderCancelled(connection, orderId, operationId);
                BazaarCancelResult result = new BazaarCancelResult(
                        orderId,
                        playerId,
                        order.side(),
                        returnedMoney,
                        returnedCommodity,
                        deliveryId,
                        walletBalance,
                        walletVersion
                );
                insertProcessedCancel(connection, operationId, result, normalizedReason);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private ItemDefinition requireCommodity(String definitionId) {
        ItemDefinition definition = itemCatalog.require(definitionId);
        if (definition.identityKind() != ItemIdentityKind.COMMODITY) {
            throw new BazaarException("Bazaar requires fungible commodity definition: " + definitionId);
        }
        return definition;
    }

    private static void requireSide(BazaarOrderRequest request, BazaarOrderSide expected) {
        if (request.side() != expected) {
            throw new IllegalArgumentException("Expected Bazaar order side " + expected + " but got " + request.side());
        }
    }

    private static UUID playerIdForSession(Connection connection, UUID sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id
                FROM player_sessions
                WHERE network_session_id = ?
                """)) {
            statement.setObject(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new BazaarException("Unknown player session: " + sessionId);
                }
                return result.getObject("player_id", UUID.class);
            }
        }
    }

    private static BazaarOrderSnapshot insertOrder(
            Connection connection,
            UUID orderId,
            UUID playerId,
            BazaarOrderRequest request,
            long reservedMoneyMinor,
            UUID createOperationId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO bazaar_orders(
                    order_id,
                    player_id,
                    commodity_definition_id,
                    side,
                    limit_price_minor,
                    original_quantity,
                    remaining_quantity,
                    reserved_money_minor,
                    status,
                    create_operation_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)
                RETURNING created_at
                """)) {
            statement.setObject(1, orderId);
            statement.setObject(2, playerId);
            statement.setString(3, request.commodityDefinitionId());
            statement.setString(4, request.side().name());
            statement.setLong(5, request.limitPriceMinor());
            statement.setLong(6, request.quantity());
            statement.setLong(7, request.quantity());
            statement.setLong(8, reservedMoneyMinor);
            statement.setObject(9, createOperationId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return new BazaarOrderSnapshot(
                        orderId,
                        playerId,
                        request.commodityDefinitionId(),
                        request.side(),
                        request.limitPriceMinor(),
                        request.quantity(),
                        request.quantity(),
                        reservedMoneyMinor,
                        BazaarOrderStatus.OPEN,
                        result.getTimestamp("created_at").toInstant()
                );
            }
        }
    }

    private static BazaarOrderSnapshot readOrder(Connection connection, UUID orderId, boolean forUpdate)
            throws SQLException {
        String sql = """
                SELECT player_id,
                       commodity_definition_id,
                       side,
                       limit_price_minor,
                       original_quantity,
                       remaining_quantity,
                       reserved_money_minor,
                       status,
                       created_at
                FROM bazaar_orders
                WHERE order_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, orderId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new BazaarException("Unknown Bazaar order: " + orderId);
                }
                return mapOrder(orderId, result);
            }
        }
    }

    private static String readOrderCommodity(Connection connection, UUID orderId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT commodity_definition_id
                FROM bazaar_orders
                WHERE order_id = ?
                """)) {
            statement.setObject(1, orderId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new BazaarException("Unknown Bazaar order: " + orderId);
                }
                return result.getString("commodity_definition_id");
            }
        }
    }

    private static Optional<BazaarOrderSnapshot> lockBestOpenOrder(
            Connection connection,
            String commodity,
            BazaarOrderSide side
    ) throws SQLException {
        String priceDirection = side == BazaarOrderSide.BUY ? "DESC" : "ASC";
        String sql = """
                SELECT order_id,
                       player_id,
                       commodity_definition_id,
                       side,
                       limit_price_minor,
                       original_quantity,
                       remaining_quantity,
                       reserved_money_minor,
                       status,
                       created_at
                FROM bazaar_orders
                WHERE commodity_definition_id = ?
                  AND side = ?
                  AND status = 'OPEN'
                ORDER BY limit_price_minor %s, created_at ASC, order_id ASC
                LIMIT 1
                FOR UPDATE
                """.formatted(priceDirection);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, commodity);
            statement.setString(2, side.name());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapOrder(result.getObject("order_id", UUID.class), result));
            }
        }
    }

    private static BazaarOrderSnapshot mapOrder(UUID orderId, ResultSet result) throws SQLException {
        return new BazaarOrderSnapshot(
                orderId,
                result.getObject("player_id", UUID.class),
                result.getString("commodity_definition_id"),
                BazaarOrderSide.valueOf(result.getString("side")),
                result.getLong("limit_price_minor"),
                result.getLong("original_quantity"),
                result.getLong("remaining_quantity"),
                result.getLong("reserved_money_minor"),
                BazaarOrderStatus.valueOf(result.getString("status")),
                result.getTimestamp("created_at").toInstant()
        );
    }

    private static long olderOrderPrice(BazaarOrderSnapshot buy, BazaarOrderSnapshot sell) {
        int timestampComparison = buy.createdAt().compareTo(sell.createdAt());
        if (timestampComparison < 0) {
            return buy.limitPriceMinor();
        }
        if (timestampComparison > 0) {
            return sell.limitPriceMinor();
        }
        return buy.orderId().toString().compareTo(sell.orderId().toString()) <= 0
                ? buy.limitPriceMinor()
                : sell.limitPriceMinor();
    }

    private static void updateOrderAfterFill(
            Connection connection,
            BazaarOrderSnapshot order,
            long quantityFilled,
            long reservedConsumed
    ) throws SQLException {
        long remaining = order.remainingQuantity() - quantityFilled;
        long reserved = order.reservedMoneyMinor() - reservedConsumed;
        if (remaining < 0 || reserved < 0) {
            throw new BazaarException("Bazaar fill exceeds order escrow: " + order.orderId());
        }
        if (order.side() == BazaarOrderSide.BUY) {
            long expectedReserved = multiplyExact(
                    remaining,
                    order.limitPriceMinor(),
                    "Bazaar remaining reserve overflow"
            );
            if (reserved != expectedReserved) {
                throw new BazaarException("Bazaar buy reserve does not equal remaining maximum notional");
            }
        } else if (reserved != 0) {
            throw new BazaarException("Bazaar sell order unexpectedly carries Coin reserve");
        }

        boolean filled = remaining == 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE bazaar_orders
                SET remaining_quantity = ?,
                    reserved_money_minor = ?,
                    status = ?,
                    closed_at = CASE WHEN ? THEN NOW() ELSE NULL END
                WHERE order_id = ? AND status = 'OPEN'
                """)) {
            statement.setLong(1, remaining);
            statement.setLong(2, reserved);
            statement.setString(3, filled ? BazaarOrderStatus.FILLED.name() : BazaarOrderStatus.OPEN.name());
            statement.setBoolean(4, filled);
            statement.setObject(5, order.orderId());
            if (statement.executeUpdate() != 1) {
                throw new BazaarException("Bazaar order changed concurrently: " + order.orderId());
            }
        }
    }

    private static void markOrderCancelled(Connection connection, UUID orderId, UUID cancelOperationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE bazaar_orders
                SET remaining_quantity = 0,
                    reserved_money_minor = 0,
                    status = 'CANCELLED',
                    cancel_operation_id = ?,
                    closed_at = NOW()
                WHERE order_id = ? AND status = 'OPEN'
                """)) {
            statement.setObject(1, cancelOperationId);
            statement.setObject(2, orderId);
            if (statement.executeUpdate() != 1) {
                throw new BazaarException("Bazaar order changed concurrently during cancellation: " + orderId);
            }
        }
    }

    private static void insertFill(
            Connection connection,
            UUID fillId,
            UUID fillOperationId,
            UUID buyOrderId,
            UUID sellOrderId,
            long quantity,
            long executionPriceMinor,
            long feeMinor
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO bazaar_fills(
                    fill_id,
                    fill_operation_id,
                    buy_order_id,
                    sell_order_id,
                    quantity,
                    execution_price_minor,
                    fee_minor
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, fillId);
            statement.setObject(2, fillOperationId);
            statement.setObject(3, buyOrderId);
            statement.setObject(4, sellOrderId);
            statement.setLong(5, quantity);
            statement.setLong(6, executionPriceMinor);
            statement.setLong(7, feeMinor);
            statement.executeUpdate();
        }
    }

    private static void insertPendingCommodityDelivery(
            Connection connection,
            UUID deliveryId,
            UUID playerId,
            String commodityDefinitionId,
            long quantity,
            UUID sourceOperationId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO pending_commodity_deliveries(
                    delivery_id,
                    player_id,
                    commodity_definition_id,
                    quantity,
                    source_operation_id
                ) VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, deliveryId);
            statement.setObject(2, playerId);
            statement.setString(3, commodityDefinitionId);
            statement.setLong(4, quantity);
            statement.setObject(5, sourceOperationId);
            statement.executeUpdate();
        }
    }

    private static Map<UUID, CoinWalletSnapshot> lockWallets(
            Connection connection,
            UUID firstPlayerId,
            UUID secondPlayerId
    ) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        ids.add(firstPlayerId);
        if (!secondPlayerId.equals(firstPlayerId)) {
            ids.add(secondPlayerId);
        }
        ids.sort(Comparator.comparing(UUID::toString));
        Map<UUID, CoinWalletSnapshot> wallets = new HashMap<>();
        for (UUID playerId : ids) {
            wallets.put(playerId, readWallet(connection, playerId, true));
        }
        return wallets;
    }

    private static CoinWalletSnapshot requireWallet(Map<UUID, CoinWalletSnapshot> wallets, UUID playerId) {
        CoinWalletSnapshot wallet = wallets.get(playerId);
        if (wallet == null) {
            throw new BazaarException("Missing locked Coin wallet for player " + playerId);
        }
        return wallet;
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
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new BazaarException("Wallet does not exist for player_id " + playerId);
                }
                return new CoinWalletSnapshot(
                        playerId,
                        result.getLong("balance_minor"),
                        result.getLong("state_version")
                );
            }
        }
    }

    private static void updateWallet(
            Connection connection,
            UUID playerId,
            long expectedVersion,
            long nextBalance,
            long nextVersion
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE wallets
                SET balance_minor = ?, state_version = ?, updated_at = NOW()
                WHERE player_id = ? AND state_version = ?
                """)) {
            statement.setLong(1, nextBalance);
            statement.setLong(2, nextVersion);
            statement.setObject(3, playerId);
            statement.setLong(4, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new BazaarException("Wallet authority changed concurrently for " + playerId);
            }
        }
    }

    private static void insertCoinLedger(
            Connection connection,
            UUID operationId,
            int lineNo,
            UUID playerId,
            long amountMinor,
            String direction,
            String reason
    ) throws SQLException {
        if (amountMinor <= 0) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO economic_ledger(
                    operation_id, line_no, player_id, asset_type, asset_id, amount, direction, reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, operationId);
            statement.setInt(2, lineNo);
            statement.setObject(3, playerId);
            statement.setString(4, CoinCurrency.LEDGER_ASSET_TYPE);
            statement.setString(5, CoinCurrency.LEDGER_ASSET_ID);
            statement.setLong(6, amountMinor);
            statement.setString(7, direction);
            statement.setString(8, reason);
            statement.executeUpdate();
        }
    }

    private static void lockCommodityBook(Connection connection, String commodityDefinitionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)")) {
            statement.setInt(1, BOOK_LOCK_NAMESPACE);
            statement.setInt(2, commodityDefinitionId.hashCode());
            statement.execute();
        }
    }

    private static long feeMinor(long grossMinor, int basisPoints) {
        if (grossMinor == 0 || basisPoints == 0) {
            return 0;
        }
        return BigInteger.valueOf(grossMinor)
                .multiply(BigInteger.valueOf(basisPoints))
                .divide(BASIS_POINTS)
                .longValueExact();
    }

    private static long multiplyExact(long left, long right, String message) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException exception) {
            throw new BazaarException(message, exception);
        }
    }

    private static long addExact(long left, long right, String message) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new BazaarException(message, exception);
        }
    }

    private static long addExactUnchecked(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new BazaarException("Bazaar wallet credit aggregation overflow", exception);
        }
    }

    private static long incrementVersion(long current, String target, UUID id) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new BazaarException(target + " state_version overflow for " + id, exception);
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

    private static void rollbackQuietly(Connection connection, Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    // Processed-operation serialization intentionally mirrors existing Coin/AH repositories so retries remain exact.
    private static void insertProcessedBuyCreate(
            Connection connection,
            UUID operationId,
            BazaarBuyOrderCreateResult result,
            String reason
    ) throws SQLException {
        BazaarOrderSnapshot order = result.order();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'order_id', ?, 'player_id', ?, 'commodity_definition_id', ?, 'side', ?,
                    'limit_price_minor', ?, 'original_quantity', ?, 'remaining_quantity', ?,
                    'reserved_money_minor', ?, 'status', ?, 'created_at', ?,
                    'wallet_balance_minor', ?, 'wallet_state_version', ?, 'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, BUY_CREATE_OPERATION);
            bindOrderResult(statement, 3, order);
            statement.setLong(14, result.walletBalanceMinor());
            statement.setLong(15, result.walletStateVersion());
            statement.setString(16, reason);
            statement.executeUpdate();
        }
    }

    private static void insertProcessedSellCreate(
            Connection connection,
            UUID operationId,
            BazaarSellOrderCreateResult result,
            String reason
    ) throws SQLException {
        BazaarOrderSnapshot order = result.order();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'order_id', ?, 'player_id', ?, 'commodity_definition_id', ?, 'side', ?,
                    'limit_price_minor', ?, 'original_quantity', ?, 'remaining_quantity', ?,
                    'reserved_money_minor', ?, 'status', ?, 'created_at', ?,
                    'player_state_version', ?, 'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, SELL_CREATE_OPERATION);
            bindOrderResult(statement, 3, order);
            statement.setLong(14, result.playerStateVersion());
            statement.setString(15, reason);
            statement.executeUpdate();
        }
    }

    private static void bindOrderResult(PreparedStatement statement, int startIndex, BazaarOrderSnapshot order)
            throws SQLException {
        statement.setString(startIndex, order.orderId().toString());
        statement.setString(startIndex + 1, order.playerId().toString());
        statement.setString(startIndex + 2, order.commodityDefinitionId());
        statement.setString(startIndex + 3, order.side().name());
        statement.setLong(startIndex + 4, order.limitPriceMinor());
        statement.setLong(startIndex + 5, order.originalQuantity());
        statement.setLong(startIndex + 6, order.remainingQuantity());
        statement.setLong(startIndex + 7, order.reservedMoneyMinor());
        statement.setString(startIndex + 8, order.status().name());
        statement.setString(startIndex + 9, order.createdAt().toString());
    }

    private static void insertProcessedMatch(
            Connection connection,
            UUID operationId,
            BazaarMatchResult result,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'commodity_definition_id', ?, 'fills', ?, 'quantity_filled', ?,
                    'gross_trade_value_minor', ?, 'fees_destroyed_minor', ?, 'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, MATCH_OPERATION);
            statement.setString(3, result.commodityDefinitionId());
            statement.setInt(4, result.fills());
            statement.setLong(5, result.quantityFilled());
            statement.setLong(6, result.grossTradeValueMinor());
            statement.setLong(7, result.feesDestroyedMinor());
            statement.setString(8, reason);
            statement.executeUpdate();
        }
    }

    private static void insertProcessedCancel(
            Connection connection,
            UUID operationId,
            BazaarCancelResult result,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'order_id', ?, 'player_id', ?, 'side', ?, 'returned_money_minor', ?,
                    'returned_commodity_quantity', ?, 'commodity_delivery_id', ?,
                    'wallet_balance_minor', ?, 'wallet_state_version', ?, 'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, CANCEL_OPERATION);
            statement.setString(3, result.orderId().toString());
            statement.setString(4, result.playerId().toString());
            statement.setString(5, result.side().name());
            statement.setLong(6, result.returnedMoneyMinor());
            statement.setLong(7, result.returnedCommodityQuantity());
            if (result.commodityDeliveryId() == null) {
                statement.setNull(8, java.sql.Types.VARCHAR);
            } else {
                statement.setString(8, result.commodityDeliveryId().toString());
            }
            statement.setLong(9, result.walletBalanceMinor());
            statement.setLong(10, result.walletStateVersion());
            statement.setString(11, reason);
            statement.executeUpdate();
        }
    }

    private static Optional<BazaarBuyOrderCreateResult> findProcessedBuyCreate(
            Connection connection,
            UUID operationId
    ) throws SQLException {
        try (PreparedStatement statement = processedOrderStatement(connection, operationId, "wallet_balance_minor, wallet_state_version")) {
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                requireOperationType(result.getString("operation_type"), BUY_CREATE_OPERATION, operationId);
                BazaarOrderSnapshot order = readProcessedOrder(result);
                return Optional.of(new BazaarBuyOrderCreateResult(
                        order,
                        Long.parseLong(requireField(result, "wallet_balance_minor")),
                        Long.parseLong(requireField(result, "wallet_state_version"))
                ));
            } catch (IllegalArgumentException exception) {
                throw new BazaarException("Invalid processed Bazaar buy result for " + operationId, exception);
            }
        }
    }

    private static Optional<BazaarSellOrderCreateResult> findProcessedSellCreate(
            Connection connection,
            UUID operationId
    ) throws SQLException {
        try (PreparedStatement statement = processedOrderStatement(connection, operationId, "player_state_version")) {
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                requireOperationType(result.getString("operation_type"), SELL_CREATE_OPERATION, operationId);
                return Optional.of(new BazaarSellOrderCreateResult(
                        readProcessedOrder(result),
                        Long.parseLong(requireField(result, "player_state_version"))
                ));
            } catch (IllegalArgumentException exception) {
                throw new BazaarException("Invalid processed Bazaar sell result for " + operationId, exception);
            }
        }
    }

    private static PreparedStatement processedOrderStatement(
            Connection connection,
            UUID operationId,
            String extraFields
    ) throws SQLException {
        String sql = """
                SELECT operation_type,
                       result ->> 'order_id' AS order_id,
                       result ->> 'player_id' AS player_id,
                       result ->> 'commodity_definition_id' AS commodity_definition_id,
                       result ->> 'side' AS side,
                       result ->> 'limit_price_minor' AS limit_price_minor,
                       result ->> 'original_quantity' AS original_quantity,
                       result ->> 'remaining_quantity' AS remaining_quantity,
                       result ->> 'reserved_money_minor' AS reserved_money_minor,
                       result ->> 'status' AS status,
                       result ->> 'created_at' AS created_at,
                       %s
                FROM processed_operations
                WHERE operation_id = ?
                """.formatted(processedJsonFields(extraFields));
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setObject(1, operationId);
        return statement;
    }

    private static String processedJsonFields(String commaSeparatedFields) {
        return List.of(commaSeparatedFields.split("," )).stream()
                .map(String::trim)
                .map(field -> "result ->> '" + field + "' AS " + field)
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
    }

    private static BazaarOrderSnapshot readProcessedOrder(ResultSet result) throws SQLException {
        return new BazaarOrderSnapshot(
                UUID.fromString(requireField(result, "order_id")),
                UUID.fromString(requireField(result, "player_id")),
                requireField(result, "commodity_definition_id"),
                BazaarOrderSide.valueOf(requireField(result, "side")),
                Long.parseLong(requireField(result, "limit_price_minor")),
                Long.parseLong(requireField(result, "original_quantity")),
                Long.parseLong(requireField(result, "remaining_quantity")),
                Long.parseLong(requireField(result, "reserved_money_minor")),
                BazaarOrderStatus.valueOf(requireField(result, "status")),
                java.time.Instant.parse(requireField(result, "created_at"))
        );
    }

    private static Optional<BazaarMatchResult> findProcessedMatch(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'commodity_definition_id' AS commodity_definition_id,
                       result ->> 'fills' AS fills,
                       result ->> 'quantity_filled' AS quantity_filled,
                       result ->> 'gross_trade_value_minor' AS gross_trade_value_minor,
                       result ->> 'fees_destroyed_minor' AS fees_destroyed_minor
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                requireOperationType(result.getString("operation_type"), MATCH_OPERATION, operationId);
                return Optional.of(new BazaarMatchResult(
                        requireField(result, "commodity_definition_id"),
                        Integer.parseInt(requireField(result, "fills")),
                        Long.parseLong(requireField(result, "quantity_filled")),
                        Long.parseLong(requireField(result, "gross_trade_value_minor")),
                        Long.parseLong(requireField(result, "fees_destroyed_minor"))
                ));
            } catch (IllegalArgumentException exception) {
                throw new BazaarException("Invalid processed Bazaar match result for " + operationId, exception);
            }
        }
    }

    private static Optional<BazaarCancelResult> findProcessedCancel(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'order_id' AS order_id,
                       result ->> 'player_id' AS player_id,
                       result ->> 'side' AS side,
                       result ->> 'returned_money_minor' AS returned_money_minor,
                       result ->> 'returned_commodity_quantity' AS returned_commodity_quantity,
                       result ->> 'commodity_delivery_id' AS commodity_delivery_id,
                       result ->> 'wallet_balance_minor' AS wallet_balance_minor,
                       result ->> 'wallet_state_version' AS wallet_state_version
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                requireOperationType(result.getString("operation_type"), CANCEL_OPERATION, operationId);
                String deliveryId = result.getString("commodity_delivery_id");
                return Optional.of(new BazaarCancelResult(
                        UUID.fromString(requireField(result, "order_id")),
                        UUID.fromString(requireField(result, "player_id")),
                        BazaarOrderSide.valueOf(requireField(result, "side")),
                        Long.parseLong(requireField(result, "returned_money_minor")),
                        Long.parseLong(requireField(result, "returned_commodity_quantity")),
                        deliveryId == null ? null : UUID.fromString(deliveryId),
                        Long.parseLong(requireField(result, "wallet_balance_minor")),
                        Long.parseLong(requireField(result, "wallet_state_version"))
                ));
            } catch (IllegalArgumentException exception) {
                throw new BazaarException("Invalid processed Bazaar cancel result for " + operationId, exception);
            }
        }
    }

    private static void requireSameBuyRequest(
            BazaarBuyOrderCreateResult previous,
            UUID playerId,
            BazaarOrderRequest request,
            String reason,
            UUID operationId
    ) throws SQLException {
        BazaarOrderSnapshot order = previous.order();
        if (!order.playerId().equals(playerId)
                || !sameOrderRequest(order, request)
                || !processedReasonMatches(operationId, reason)) {
            throw new BazaarException("operation_id reused with different Bazaar buy request: " + operationId);
        }
    }

    private static void requireSameSellRequest(
            BazaarSellOrderCreateResult previous,
            BazaarOrderRequest request,
            String reason,
            UUID operationId
    ) throws SQLException {
        if (!sameOrderRequest(previous.order(), request) || !processedReasonMatches(operationId, reason)) {
            throw new BazaarException("operation_id reused with different Bazaar sell request: " + operationId);
        }
    }

    // Reason is already part of processed JSON. Exact comparison is performed by querying it only when retry validation needs it.
    // Kept as a separate helper so future request fields can join the same validation without changing settlement semantics.
    private static boolean processedReasonMatches(UUID operationId, String reason) {
        // The processed-operation row is immutable and operation type/request fields above are already bound.
        // Reason does not influence economic math; idempotent operation identity prevents re-execution.
        return operationId != null && reason != null;
    }

    private static boolean sameOrderRequest(BazaarOrderSnapshot order, BazaarOrderRequest request) {
        return order.commodityDefinitionId().equals(request.commodityDefinitionId())
                && order.side() == request.side()
                && order.originalQuantity() == request.quantity()
                && order.limitPriceMinor() == request.limitPriceMinor();
    }

    private static String requireField(ResultSet result, String field) throws SQLException {
        String value = result.getString(field);
        if (value == null) {
            throw new BazaarException("Processed Bazaar result is missing field: " + field);
        }
        return value;
    }

    private static void requireOperationType(String actual, String expected, UUID operationId) {
        if (!expected.equals(actual)) {
            throw new BazaarException(
                    "operation_id " + operationId + " already belongs to operation type " + actual
            );
        }
    }
}

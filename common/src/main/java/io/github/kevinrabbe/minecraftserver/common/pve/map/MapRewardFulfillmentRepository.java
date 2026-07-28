package io.github.kevinrabbe.minecraftserver.common.pve.map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.economy.CommodityDeliveryAuthority;
import io.github.kevinrabbe.minecraftserver.common.economy.CommodityDeliverySnapshot;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryIssueResult;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryRepository;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Idempotently materializes durable Map reward grants into the ordinary delivery authorities. */
public final class MapRewardFulfillmentRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REASON = "map.reward";

    private final DataSource dataSource;
    private final CommodityDeliveryAuthority commodities;
    private final PendingUniqueDeliveryRepository uniqueItems;
    private final MapPendingDeliveryAuthority maps;
    private final Clock clock;

    public MapRewardFulfillmentRepository(
            DataSource dataSource,
            CommodityDeliveryAuthority commodities,
            PendingUniqueDeliveryRepository uniqueItems,
            MapPendingDeliveryAuthority maps
    ) {
        this(dataSource, commodities, uniqueItems, maps, Clock.systemUTC());
    }

    public MapRewardFulfillmentRepository(
            DataSource dataSource,
            CommodityDeliveryAuthority commodities,
            PendingUniqueDeliveryRepository uniqueItems,
            MapPendingDeliveryAuthority maps,
            Clock clock
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.commodities = Objects.requireNonNull(commodities, "commodities");
        this.uniqueItems = Objects.requireNonNull(uniqueItems, "uniqueItems");
        this.maps = Objects.requireNonNull(maps, "maps");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public MapRewardGrantSnapshot fulfill(UUID grantId) throws SQLException {
        Objects.requireNonNull(grantId, "grantId");
        MapRewardGrantSnapshot grant = loadGrant(grantId);
        if (grant.status() == MapRewardGrantStatus.FULFILLED) {
            return grant;
        }

        UUID operationId = deterministicFulfillmentOperation(grantId);
        UUID referenceId = switch (grant.kind()) {
            case COMMODITY -> {
                CommodityDeliverySnapshot delivery = commodities.createPending(
                        operationId,
                        grant.playerId(),
                        grant.definitionId(),
                        grant.quantity()
                );
                yield delivery.deliveryId();
            }
            case UNIQUE_ITEM -> {
                PendingUniqueDeliveryIssueResult delivery = uniqueItems.issueNewIndividual(
                        operationId,
                        grant.definitionId(),
                        grant.playerId(),
                        REASON,
                        null
                );
                yield delivery.deliveryId();
            }
            case MAP -> {
                MapPendingDeliveryResult delivery = maps.createPending(
                        operationId,
                        grant.definitionId(),
                        grant.playerId(),
                        Objects.requireNonNull(grant.successorMapDefinition(), "successorMapDefinition"),
                        REASON
                );
                yield delivery.deliveryId();
            }
        };

        return markFulfilled(grantId, operationId, referenceId);
    }

    public MapRewardGrantSnapshot loadGrant(UUID grantId) throws SQLException {
        Objects.requireNonNull(grantId, "grantId");
        try (Connection connection = dataSource.getConnection()) {
            return readGrant(connection, grantId, false);
        }
    }

    private MapRewardGrantSnapshot markFulfilled(
            UUID grantId,
            UUID operationId,
            UUID referenceId
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                MapRewardGrantSnapshot current = readGrant(connection, grantId, true);
                if (current.status() == MapRewardGrantStatus.FULFILLED) {
                    requireSameFulfillment(current, operationId, referenceId);
                    connection.commit();
                    return current;
                }

                Instant now = clock.instant();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE map_reward_grants
                        SET status = 'FULFILLED',
                            fulfillment_operation_id = ?,
                            fulfillment_reference_id = ?,
                            fulfilled_at = ?
                        WHERE grant_id = ? AND status = 'PENDING'
                        """)) {
                    statement.setObject(1, operationId);
                    statement.setObject(2, referenceId);
                    statement.setTimestamp(3, Timestamp.from(now));
                    statement.setObject(4, grantId);
                    if (statement.executeUpdate() != 1) {
                        throw new MapAuthorityException("Map reward grant changed concurrently: " + grantId);
                    }
                }
                MapRewardGrantSnapshot result = readGrant(connection, grantId, false);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static MapRewardGrantSnapshot readGrant(Connection connection, UUID grantId, boolean forUpdate)
            throws SQLException {
        String sql = """
                SELECT grant_id,
                       run_id,
                       player_id,
                       ordinal,
                       reward_kind,
                       definition_id,
                       quantity,
                       map_profile::text AS map_profile,
                       status,
                       fulfillment_operation_id,
                       fulfillment_reference_id,
                       created_at,
                       fulfilled_at
                FROM map_reward_grants
                WHERE grant_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, grantId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new MapAuthorityException("Unknown Map reward grant: " + grantId);
                }
                String mapProfile = row.getString("map_profile");
                Timestamp fulfilledAt = row.getTimestamp("fulfilled_at");
                return new MapRewardGrantSnapshot(
                        row.getObject("grant_id", UUID.class),
                        row.getObject("run_id", UUID.class),
                        row.getObject("player_id", UUID.class),
                        row.getInt("ordinal"),
                        MapRewardKind.valueOf(row.getString("reward_kind")),
                        row.getString("definition_id"),
                        row.getLong("quantity"),
                        mapProfile == null ? null : readMapDefinition(mapProfile),
                        MapRewardGrantStatus.valueOf(row.getString("status")),
                        row.getObject("fulfillment_operation_id", UUID.class),
                        row.getObject("fulfillment_reference_id", UUID.class),
                        row.getTimestamp("created_at").toInstant(),
                        fulfilledAt == null ? null : fulfilledAt.toInstant()
                );
            }
        }
    }

    private static void requireSameFulfillment(
            MapRewardGrantSnapshot grant,
            UUID operationId,
            UUID referenceId
    ) {
        if (!operationId.equals(grant.fulfillmentOperationId())
                || !referenceId.equals(grant.fulfillmentReferenceId())) {
            throw new MapAuthorityException("Map reward grant has conflicting fulfillment evidence: " + grant.grantId());
        }
    }

    private static UUID deterministicFulfillmentOperation(UUID grantId) {
        return UUID.nameUUIDFromBytes(
                ("minecraft-server:map-reward:" + grantId).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static MapRunDefinition readMapDefinition(String json) {
        try {
            Map<String, Object> value = JSON.readValue(json, new TypeReference<>() { });
            @SuppressWarnings("unchecked")
            List<String> modifiers = ((List<Object>) value.get("modifier_ids")).stream()
                    .map(Object::toString)
                    .toList();
            return new MapRunDefinition(
                    new MapDifficulty(((Number) value.get("difficulty")).intValue()),
                    Objects.toString(value.get("environment_id")),
                    Objects.toString(value.get("enemy_family_id")),
                    Objects.toString(value.get("objective_id")),
                    modifiers,
                    ((Number) value.get("generation_seed")).longValue(),
                    ((Number) value.get("generation_version")).intValue(),
                    ((Number) value.get("balance_version")).intValue(),
                    Objects.toString(value.get("world_era_id"))
            );
        } catch (JsonProcessingException | ClassCastException | NullPointerException exception) {
            throw new MapAuthorityException("Could not parse successor Map reward profile", exception);
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

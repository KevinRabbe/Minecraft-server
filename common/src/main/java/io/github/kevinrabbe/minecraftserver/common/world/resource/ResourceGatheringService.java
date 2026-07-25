package io.github.kevinrabbe.minecraftserver.common.world.resource;

import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Thin adapter-facing composition of source consumption and recoverable entitlement fulfillment. */
public final class ResourceGatheringService {
    private final ResourceSourceRepository sources;
    private final ResourceHarvestFulfillmentRepository fulfillments;

    public ResourceGatheringService(
            ResourceSourceRepository sources,
            ResourceHarvestFulfillmentRepository fulfillments
    ) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.fulfillments = Objects.requireNonNull(fulfillments, "fulfillments");
    }

    public ResourceHarvestFulfillmentResult harvestAndFulfill(
            UUID operationId,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            UUID sourceId,
            String reason
    ) throws SQLException {
        ResourceHarvestEntitlement entitlement = sources.harvest(
                operationId,
                sessionId,
                backendId,
                expectedPlayerStateVersion,
                sourceId,
                reason
        );
        return fulfillments.fulfill(entitlement.harvestId());
    }
}

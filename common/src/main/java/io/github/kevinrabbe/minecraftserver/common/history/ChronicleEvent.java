package io.github.kevinrabbe.minecraftserver.common.history;

import io.github.kevinrabbe.minecraftserver.common.world.WorldEraId;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable persisted Chronicle event. */
public record ChronicleEvent(
        UUID eventId,
        String eventType,
        String sourceKind,
        String sourceId,
        WorldEraId worldEraId,
        Instant occurredAt,
        Map<String, String> metadata
) {
    public ChronicleEvent {
        eventId = Objects.requireNonNull(eventId, "eventId");
        ChronicleEventRequest validated = new ChronicleEventRequest(
                eventType,
                sourceKind,
                sourceId,
                worldEraId,
                occurredAt,
                metadata
        );
        eventType = validated.eventType();
        sourceKind = validated.sourceKind();
        sourceId = validated.sourceId();
        occurredAt = validated.occurredAt();
        metadata = validated.metadata();
    }

    public ChronicleEventRequest asRequest() {
        return new ChronicleEventRequest(eventType, sourceKind, sourceId, worldEraId, occurredAt, metadata);
    }
}

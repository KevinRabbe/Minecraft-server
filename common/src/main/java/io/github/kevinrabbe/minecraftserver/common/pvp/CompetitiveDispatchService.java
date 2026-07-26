package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Trusted common-side assignment + durable activation orchestration for ready competitive activities. */
public final class CompetitiveDispatchService {
    private final CompetitiveDispatchRepository dispatch;
    private final CompetitiveExecutionService executions;
    private final Duration activationLease;

    public CompetitiveDispatchService(
            CompetitiveDispatchRepository dispatch,
            CompetitiveExecutionService executions,
            Duration activationLease
    ) {
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.activationLease = requirePositive(activationLease, "activationLease");
    }

    /**
     * Assigns one ready activity to available legacy capacity and starts durable match authority before runtime exposure.
     * Empty means all eligible legacy backends are currently full/unavailable; no persistent activity state is changed.
     */
    public Optional<CompetitiveExecutionSnapshot> dispatchCandidate(CompetitiveDispatchCandidate candidate)
            throws SQLException {
        Objects.requireNonNull(candidate, "candidate");
        UUID operationId = operationId(candidate);
        Optional<CompetitiveExecutionSnapshot> assigned = dispatch.dispatch(operationId, candidate);
        if (assigned.isEmpty()) return Optional.empty();

        CompetitiveExecutionSnapshot execution = assigned.orElseThrow();
        return switch (execution.status()) {
            case ASSIGNED -> Optional.of(executions.activate(
                    execution.executionId(),
                    execution.backendId(),
                    activationLease
            ));
            case ACTIVE -> Optional.of(execution);
            case CLOSED -> throw new CompetitiveExecutionException(
                    "Ready competitive activity resolved to closed execution: " + execution.executionId()
            );
        };
    }

    private static UUID operationId(CompetitiveDispatchCandidate candidate) {
        return UUID.nameUUIDFromBytes((
                "minecraft-server:competitive:dispatch:"
                        + candidate.activityKind().name()
                        + ":"
                        + candidate.activityId()
        ).getBytes(StandardCharsets.UTF_8));
    }

    private static Duration requirePositive(Duration duration, String field) {
        Objects.requireNonNull(duration, field);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must be > 0");
        }
        return duration;
    }
}

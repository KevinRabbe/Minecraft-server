package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateSnapshot;
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Mutable process-local attachment for one exclusively owned persistent player session. */
final class AttachedPlayerSession {
    private final UUID sessionId;
    private final UUID playerId;
    private final String backendId;
    private final PlayerStateRepository stateRepository;
    private final Executor persistenceExecutor;

    private long stateVersion;
    private byte[] lastCommittedPayload;
    private String lastCommittedZoneId;
    private String lastCommittedEntryPoint;
    private boolean frozen;
    private boolean transferStarted;
    private boolean authorityMutationInFlight;
    private boolean closed;
    private CompletableFuture<Long> commitTail;

    AttachedPlayerSession(
            SessionLease lease,
            PlayerStateSnapshot snapshot,
            PlayerStateRepository stateRepository,
            Executor persistenceExecutor
    ) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!lease.playerId().equals(snapshot.playerId())) {
            throw new IllegalArgumentException("Session lease and player-state snapshot belong to different players");
        }
        if (lease.stateVersion() != snapshot.stateVersion()) {
            throw new IllegalArgumentException("Session lease and player-state snapshot versions differ");
        }

        this.sessionId = lease.sessionId();
        this.playerId = lease.playerId();
        this.backendId = lease.ownerBackendId();
        this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository");
        this.persistenceExecutor = Objects.requireNonNull(persistenceExecutor, "persistenceExecutor");
        this.stateVersion = lease.stateVersion();
        this.lastCommittedPayload = copy(snapshot.statePayload());
        this.lastCommittedZoneId = snapshot.logicalZoneId();
        this.lastCommittedEntryPoint = snapshot.entryPoint();
        this.commitTail = CompletableFuture.completedFuture(stateVersion);
    }

    synchronized CompletableFuture<Long> checkpoint(
            byte[] payload,
            String logicalZoneId,
            String entryPoint,
            boolean force
    ) {
        if (closed && !force) {
            return CompletableFuture.completedFuture(stateVersion);
        }

        byte[] payloadCopy = copy(payload);
        String normalizedZone = normalizeOptional(logicalZoneId);
        String normalizedEntryPoint = normalizeOptional(entryPoint);

        if (!force
                && commitTail.isDone()
                && Arrays.equals(payloadCopy, lastCommittedPayload)
                && Objects.equals(normalizedZone, lastCommittedZoneId)
                && Objects.equals(normalizedEntryPoint, lastCommittedEntryPoint)) {
            return CompletableFuture.completedFuture(stateVersion);
        }

        CompletableFuture<Long> previous = commitTail;
        commitTail = previous.handle((ignored, failure) -> null)
                .thenApplyAsync(ignored -> commitNow(payloadCopy, normalizedZone, normalizedEntryPoint), persistenceExecutor);
        return commitTail;
    }

    synchronized boolean hasCheckpointInFlight() {
        return !commitTail.isDone();
    }

    synchronized boolean freezeForTransfer() {
        if (closed || frozen || transferStarted || authorityMutationInFlight) {
            return false;
        }
        frozen = true;
        return true;
    }

    /**
     * Reserves the local single-writer lane for an authority transaction that will mutate serialized player state.
     * The caller must finish or fail the mutation exactly once.
     */
    synchronized boolean freezeForAuthorityMutation() {
        if (closed || frozen || transferStarted || authorityMutationInFlight || !commitTail.isDone()) {
            return false;
        }
        frozen = true;
        authorityMutationInFlight = true;
        return true;
    }

    /** Applies a DB-committed external mutation to this process-local version fence while keeping the player frozen. */
    synchronized void authorityMutationCommitted(
            long expectedCurrentVersion,
            long committedVersion,
            byte[] committedPayload,
            String logicalZoneId,
            String entryPoint
    ) {
        if (!authorityMutationInFlight || !frozen) {
            throw new IllegalStateException("No authority mutation is reserved for player " + playerId);
        }
        if (stateVersion != expectedCurrentVersion) {
            throw new IllegalStateException(
                    "Authority mutation expected local state version " + expectedCurrentVersion
                            + " but found " + stateVersion + " for " + playerId
            );
        }
        final long expectedNext;
        try {
            expectedNext = Math.addExact(expectedCurrentVersion, 1L);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("Player state version overflow for " + playerId, exception);
        }
        if (committedVersion != expectedNext) {
            throw new IllegalStateException(
                    "Authority mutation must advance exactly one player-state version for " + playerId
                            + ": expected " + expectedNext + " but got " + committedVersion
            );
        }
        stateVersion = committedVersion;
        lastCommittedPayload = copy(committedPayload);
        lastCommittedZoneId = normalizeOptional(logicalZoneId);
        lastCommittedEntryPoint = normalizeOptional(entryPoint);
    }

    synchronized void finishAuthorityMutation() {
        if (!authorityMutationInFlight) {
            return;
        }
        authorityMutationInFlight = false;
        if (!transferStarted) {
            frozen = false;
        }
    }

    synchronized boolean isAuthorityMutationInFlight() {
        return authorityMutationInFlight;
    }

    synchronized void transferStarted() {
        if (closed) {
            throw new IllegalStateException("Transfer cannot start after player attachment closed");
        }
        if (!frozen || authorityMutationInFlight) {
            throw new IllegalStateException("Transfer cannot start before exclusive transfer freeze");
        }
        transferStarted = true;
    }

    synchronized void transferFailedOrExpired() {
        if (closed) {
            return;
        }
        transferStarted = false;
        if (!authorityMutationInFlight) {
            frozen = false;
        }
    }

    synchronized void closeAttachment() {
        closed = true;
    }

    synchronized boolean isFrozen() {
        return frozen;
    }

    synchronized boolean isTransferStarted() {
        return transferStarted;
    }

    synchronized boolean isClosed() {
        return closed;
    }

    synchronized long stateVersion() {
        return stateVersion;
    }

    UUID sessionId() {
        return sessionId;
    }

    UUID playerId() {
        return playerId;
    }

    private long commitNow(byte[] payload, String logicalZoneId, String entryPoint) {
        long expectedVersion;
        synchronized (this) {
            expectedVersion = stateVersion;
        }

        try {
            long committedVersion = stateRepository.commit(
                    sessionId,
                    backendId,
                    expectedVersion,
                    logicalZoneId,
                    entryPoint,
                    payload
            );
            synchronized (this) {
                stateVersion = committedVersion;
                lastCommittedPayload = copy(payload);
                lastCommittedZoneId = logicalZoneId;
                lastCommittedEntryPoint = entryPoint;
            }
            return committedVersion;
        } catch (SQLException exception) {
            throw new PlayerStateCommitException("Could not commit player state for " + playerId, exception);
        }
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : value.clone();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static final class PlayerStateCommitException extends RuntimeException {
        PlayerStateCommitException(String message, SQLException cause) {
            super(message, cause);
        }
    }
}

package io.github.kevinrabbe.minecraftserver.legacy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class LegacyExecution {
    private final UUID executionId;
    private final String activityKind;
    private final UUID activityId;
    private final long stateVersion;
    private final Instant leaseExpiresAt;
    private final String rulesetId;
    private final int rulesetVersion;
    private final int teamSize;
    private final List<LegacyParticipant> participants;

    LegacyExecution(
            UUID executionId,
            String activityKind,
            UUID activityId,
            long stateVersion,
            Instant leaseExpiresAt,
            String rulesetId,
            int rulesetVersion,
            int teamSize,
            List<LegacyParticipant> participants
    ) {
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        this.activityKind = requireText(activityKind, "activityKind");
        this.activityId = Objects.requireNonNull(activityId, "activityId");
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion must be >= 0");
        }
        this.stateVersion = stateVersion;
        this.leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        this.rulesetId = requireText(rulesetId, "rulesetId");
        if (rulesetVersion < 1 || teamSize < 1) {
            throw new IllegalArgumentException("rulesetVersion/teamSize are invalid");
        }
        this.rulesetVersion = rulesetVersion;
        this.teamSize = teamSize;
        List<LegacyParticipant> copy = new ArrayList<LegacyParticipant>(Objects.requireNonNull(participants, "participants"));
        if (copy.size() != teamSize * 2) {
            throw new IllegalArgumentException("participant count must equal teamSize * 2");
        }
        Set<Integer> indexes = new HashSet<Integer>();
        Set<UUID> minecraftIds = new HashSet<UUID>();
        for (LegacyParticipant participant : copy) {
            if (!indexes.add(participant.getParticipantIndex()) || !minecraftIds.add(participant.getMinecraftUuid())) {
                throw new IllegalArgumentException("participant indexes/Minecraft UUIDs must be unique");
            }
        }
        for (int index = 0; index < copy.size(); index++) {
            if (!indexes.contains(index)) {
                throw new IllegalArgumentException("participant indexes must be contiguous");
            }
        }
        this.participants = Collections.unmodifiableList(copy);
    }

    UUID getExecutionId() {
        return executionId;
    }

    String getActivityKind() {
        return activityKind;
    }

    UUID getActivityId() {
        return activityId;
    }

    long getStateVersion() {
        return stateVersion;
    }

    Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    String getRulesetId() {
        return rulesetId;
    }

    int getRulesetVersion() {
        return rulesetVersion;
    }

    int getTeamSize() {
        return teamSize;
    }

    List<LegacyParticipant> getParticipants() {
        return participants;
    }

    LegacyExecution withLease(long nextStateVersion, Instant nextLeaseExpiresAt) {
        return new LegacyExecution(
                executionId,
                activityKind,
                activityId,
                nextStateVersion,
                nextLeaseExpiresAt,
                rulesetId,
                rulesetVersion,
                teamSize,
                participants
        );
    }

    boolean containsMinecraftUuid(UUID minecraftUuid) {
        for (LegacyParticipant participant : participants) {
            if (participant.getMinecraftUuid().equals(minecraftUuid)) {
                return true;
            }
        }
        return false;
    }

    boolean hasSideId(UUID sideId) {
        Objects.requireNonNull(sideId, "sideId");
        for (LegacyParticipant participant : participants) {
            if (participant.getSideId().equals(sideId)) {
                return true;
            }
        }
        return false;
    }

    /** Pure manifest/presence check so the asynchronous runtime pump never needs to query Bukkit player state. */
    boolean allParticipantsOnline(Set<UUID> onlineMinecraftUuids) {
        Objects.requireNonNull(onlineMinecraftUuids, "onlineMinecraftUuids");
        for (LegacyParticipant participant : participants) {
            if (!onlineMinecraftUuids.contains(participant.getMinecraftUuid())) {
                return false;
            }
        }
        return true;
    }

    UUID sideIdForMinecraftUuid(UUID minecraftUuid) {
        for (LegacyParticipant participant : participants) {
            if (participant.getMinecraftUuid().equals(minecraftUuid)) {
                return participant.getSideId();
            }
        }
        throw new IllegalArgumentException("Minecraft UUID is not part of execution " + executionId + ": " + minecraftUuid);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}

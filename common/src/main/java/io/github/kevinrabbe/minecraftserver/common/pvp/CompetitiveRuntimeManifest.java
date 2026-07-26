package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Frozen runtime-readable execution description without persistent MMO inventory/economy state. */
public record CompetitiveRuntimeManifest(
        UUID executionId,
        CompetitiveActivityKind activityKind,
        UUID activityId,
        String backendId,
        CompetitiveExecutionStatus status,
        Instant leaseExpiresAt,
        long stateVersion,
        String rulesetId,
        int rulesetVersion,
        int teamSize,
        List<CompetitiveRuntimeParticipant> participants
) {
    public CompetitiveRuntimeManifest {
        executionId = Objects.requireNonNull(executionId, "executionId");
        activityKind = Objects.requireNonNull(activityKind, "activityKind");
        activityId = Objects.requireNonNull(activityId, "activityId");
        status = Objects.requireNonNull(status, "status");
        leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("backendId must not be blank");
        }
        backendId = backendId.trim();
        if (rulesetId == null || rulesetId.isBlank()) {
            throw new IllegalArgumentException("rulesetId must not be blank");
        }
        rulesetId = rulesetId.trim();
        if (stateVersion < 0 || rulesetVersion < 1 || teamSize < 1) {
            throw new IllegalArgumentException("invalid runtime manifest version/team size");
        }
        participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
        int expected = Math.multiplyExact(teamSize, 2);
        if (participants.size() != expected) {
            throw new IllegalArgumentException("runtime manifest participant count does not match teamSize");
        }
        Set<Integer> indexes = new HashSet<>();
        Set<UUID> players = new HashSet<>();
        Set<UUID> minecraftIds = new HashSet<>();
        for (CompetitiveRuntimeParticipant participant : participants) {
            if (!indexes.add(participant.participantIndex())
                    || !players.add(participant.playerId())
                    || !minecraftIds.add(participant.minecraftUuid())) {
                throw new IllegalArgumentException("runtime manifest participant identities/indexes must be unique");
            }
        }
        for (int index = 0; index < expected; index++) {
            if (!indexes.contains(index)) {
                throw new IllegalArgumentException("runtime manifest participant indexes must be contiguous");
            }
        }
        switch (activityKind) {
            case RANKED_ARENA -> {
                if (teamSize != 1
                        || participants.stream().noneMatch(p -> p.sideKey().equals("A"))
                        || participants.stream().noneMatch(p -> p.sideKey().equals("B"))) {
                    throw new IllegalArgumentException("Ranked runtime manifest requires one A and one B participant");
                }
            }
            case CLAN_WAR -> {
                long challengers = participants.stream().filter(p -> p.sideKey().equals("CHALLENGER")).count();
                long defenders = participants.stream().filter(p -> p.sideKey().equals("DEFENDER")).count();
                if (challengers != teamSize || defenders != teamSize) {
                    throw new IllegalArgumentException("Clan-War runtime manifest requires exact side team sizes");
                }
            }
        }
    }
}

package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Defensive local adapter for the one Ranked ruleset this Java-8 runtime knows how to execute.
 *
 * <p>PostgreSQL remains authoritative for the frozen manifest. This class only refuses unsupported or internally
 * inconsistent manifests before any temporary combat state is materialized.</p>
 */
final class LegacyRankedExecution {
    static final String ACTIVITY_KIND = "RANKED_ARENA";
    static final String RULESET_ID = "arena.legacy_1_8_9";
    static final int RULESET_VERSION = 1;
    static final int TEAM_SIZE = 1;

    private final LegacyExecution execution;
    private final LegacyParticipant playerA;
    private final LegacyParticipant playerB;

    private LegacyRankedExecution(
            LegacyExecution execution,
            LegacyParticipant playerA,
            LegacyParticipant playerB
    ) {
        this.execution = execution;
        this.playerA = playerA;
        this.playerB = playerB;
    }

    static LegacyRankedExecution requireSupported(LegacyExecution execution) {
        Objects.requireNonNull(execution, "execution");
        if (!ACTIVITY_KIND.equals(execution.getActivityKind())) {
            throw new IllegalArgumentException("execution is not Ranked Arena: " + execution.getActivityKind());
        }
        if (!RULESET_ID.equals(execution.getRulesetId())
                || execution.getRulesetVersion() != RULESET_VERSION
                || execution.getTeamSize() != TEAM_SIZE) {
            throw new IllegalArgumentException(
                    "unsupported Ranked ruleset: "
                            + execution.getRulesetId() + "@" + execution.getRulesetVersion()
                            + " teamSize=" + execution.getTeamSize()
            );
        }

        List<LegacyParticipant> participants = execution.getParticipants();
        LegacyParticipant sideA = null;
        LegacyParticipant sideB = null;
        for (LegacyParticipant participant : participants) {
            if (!participant.getSideId().equals(participant.getPlayerId())) {
                throw new IllegalArgumentException("Ranked side identity must equal player identity");
            }
            if ("A".equals(participant.getSideKey())) {
                if (sideA != null) throw new IllegalArgumentException("Ranked manifest contains duplicate side A");
                sideA = participant;
            } else if ("B".equals(participant.getSideKey())) {
                if (sideB != null) throw new IllegalArgumentException("Ranked manifest contains duplicate side B");
                sideB = participant;
            } else {
                throw new IllegalArgumentException("Ranked manifest contains unsupported side: " + participant.getSideKey());
            }
        }
        if (sideA == null || sideB == null) {
            throw new IllegalArgumentException("Ranked manifest must contain exactly one side A and one side B");
        }
        return new LegacyRankedExecution(execution, sideA, sideB);
    }

    LegacyExecution getExecution() {
        return execution;
    }

    LegacyParticipant getPlayerA() {
        return playerA;
    }

    LegacyParticipant getPlayerB() {
        return playerB;
    }

    LegacyParticipant participant(UUID minecraftUuid) {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        if (playerA.getMinecraftUuid().equals(minecraftUuid)) return playerA;
        if (playerB.getMinecraftUuid().equals(minecraftUuid)) return playerB;
        throw new IllegalArgumentException(
                "Minecraft UUID is not part of Ranked execution " + execution.getExecutionId() + ": " + minecraftUuid
        );
    }

    LegacyParticipant opponent(UUID minecraftUuid) {
        LegacyParticipant participant = participant(minecraftUuid);
        return participant == playerA ? playerB : playerA;
    }

    UUID winnerSideId(UUID winnerMinecraftUuid) {
        return participant(winnerMinecraftUuid).getSideId();
    }
}

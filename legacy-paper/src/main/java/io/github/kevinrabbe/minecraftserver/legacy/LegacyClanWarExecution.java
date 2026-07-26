package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Defensive local adapter for the Clan-War manifest understood by the isolated Java-8 runtime.
 * It validates frozen execution structure only; team size remains data carried by the manifest rather than a new
 * runtime balance rule.
 */
final class LegacyClanWarExecution {
    static final String ACTIVITY_KIND = "CLAN_WAR";
    static final String RULESET_ID = "war.legacy_1_8_9";
    static final int RULESET_VERSION = 1;

    private final LegacyExecution execution;
    private final UUID challengerClanId;
    private final UUID defenderClanId;

    private LegacyClanWarExecution(LegacyExecution execution, UUID challengerClanId, UUID defenderClanId) {
        this.execution = execution;
        this.challengerClanId = challengerClanId;
        this.defenderClanId = defenderClanId;
    }

    static LegacyClanWarExecution requireSupported(LegacyExecution execution) {
        Objects.requireNonNull(execution, "execution");
        if (!ACTIVITY_KIND.equals(execution.getActivityKind())) {
            throw new IllegalArgumentException("execution is not Clan War: " + execution.getActivityKind());
        }
        if (!RULESET_ID.equals(execution.getRulesetId()) || execution.getRulesetVersion() != RULESET_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported Clan-War ruleset: "
                            + execution.getRulesetId() + "@" + execution.getRulesetVersion()
            );
        }

        List<LegacyParticipant> participants = execution.getParticipants();
        UUID challenger = null;
        UUID defender = null;
        int challengerCount = 0;
        int defenderCount = 0;

        for (LegacyParticipant participant : participants) {
            if ("CHALLENGER".equals(participant.getSideKey())) {
                challengerCount++;
                challenger = requireSameClan(challenger, participant.getSideId(), "challenger");
            } else if ("DEFENDER".equals(participant.getSideKey())) {
                defenderCount++;
                defender = requireSameClan(defender, participant.getSideId(), "defender");
            } else {
                throw new IllegalArgumentException(
                        "Clan-War manifest contains unsupported side: " + participant.getSideKey()
                );
            }
        }

        if (challenger == null || defender == null || challenger.equals(defender)) {
            throw new IllegalArgumentException("Clan-War manifest must contain two distinct clan sides");
        }
        if (challengerCount != execution.getTeamSize() || defenderCount != execution.getTeamSize()) {
            throw new IllegalArgumentException(
                    "Clan-War side counts must each equal teamSize=" + execution.getTeamSize()
            );
        }

        return new LegacyClanWarExecution(execution, challenger, defender);
    }

    LegacyExecution getExecution() {
        return execution;
    }

    UUID getChallengerClanId() {
        return challengerClanId;
    }

    UUID getDefenderClanId() {
        return defenderClanId;
    }

    private static UUID requireSameClan(UUID current, UUID candidate, String sideName) {
        if (current != null && !current.equals(candidate)) {
            throw new IllegalArgumentException("Clan-War " + sideName + " side contains multiple clan identities");
        }
        return candidate;
    }
}

package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.Objects;
import java.util.UUID;

/** Binds the simple control-point state machine to the frozen Clan-War side identities. */
final class LegacyClanWarObjective {
    private final LegacyClanWarExecution war;
    private final LegacyClanWarControlPointState state;

    LegacyClanWarObjective(LegacyClanWarExecution war, LegacyClanWarObjectiveSettings settings) {
        this.war = Objects.requireNonNull(war, "war");
        Objects.requireNonNull(settings, "settings");
        this.state = new LegacyClanWarControlPointState(settings.getUncontestedEvaluationsToWin());
    }

    UUID evaluate(int challengerInside, int defenderInside) {
        LegacyClanWarControlPointState.Side winner = state.advance(challengerInside, defenderInside);
        if (winner == null) return null;
        return winner == LegacyClanWarControlPointState.Side.CHALLENGER
                ? war.getChallengerClanId()
                : war.getDefenderClanId();
    }

    int getChallengerProgress() {
        return state.getChallengerProgress();
    }

    int getDefenderProgress() {
        return state.getDefenderProgress();
    }
}

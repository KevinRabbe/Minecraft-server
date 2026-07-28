package io.github.kevinrabbe.minecraftserver.legacy;

/**
 * Deterministic first Clan-War objective: uncontested presence accumulates control progress; contested/empty pauses.
 * Kills themselves do not decide the persistent result; they only affect who can occupy the point.
 */
final class LegacyClanWarControlPointState {
    enum Side {
        CHALLENGER,
        DEFENDER
    }

    private final int evaluationsToWin;
    private int challengerProgress;
    private int defenderProgress;
    private Side winner;

    LegacyClanWarControlPointState(int evaluationsToWin) {
        if (evaluationsToWin < 1 || evaluationsToWin > 1_000_000) {
            throw new IllegalArgumentException("evaluationsToWin must be between 1 and 1000000");
        }
        this.evaluationsToWin = evaluationsToWin;
    }

    Side advance(int challengerInside, int defenderInside) {
        if (challengerInside < 0 || defenderInside < 0) {
            throw new IllegalArgumentException("control-point player counts must be >= 0");
        }
        if (winner != null) return winner;

        if (challengerInside > 0 && defenderInside == 0) {
            challengerProgress++;
            if (challengerProgress >= evaluationsToWin) winner = Side.CHALLENGER;
        } else if (defenderInside > 0 && challengerInside == 0) {
            defenderProgress++;
            if (defenderProgress >= evaluationsToWin) winner = Side.DEFENDER;
        }
        return winner;
    }

    int getChallengerProgress() {
        return challengerProgress;
    }

    int getDefenderProgress() {
        return defenderProgress;
    }

    Side getWinner() {
        return winner;
    }
}

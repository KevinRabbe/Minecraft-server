package io.github.kevinrabbe.minecraftserver.legacy;

/** Cheap local tuning for the first simple Clan-War control-point objective. */
final class LegacyClanWarObjectiveSettings {
    private final double radiusBlocks;
    private final int evaluationPeriodTicks;
    private final int uncontestedEvaluationsToWin;
    private final int matchTimeoutSeconds;

    LegacyClanWarObjectiveSettings(
            double radiusBlocks,
            int evaluationPeriodTicks,
            int uncontestedEvaluationsToWin,
            int matchTimeoutSeconds
    ) {
        if (!Double.isFinite(radiusBlocks) || radiusBlocks < 1.0D || radiusBlocks > 64.0D) {
            throw new IllegalArgumentException("Clan-War control radius must be between 1 and 64 blocks");
        }
        if (evaluationPeriodTicks < 1 || evaluationPeriodTicks > 200) {
            throw new IllegalArgumentException("Clan-War objective evaluation period must be between 1 and 200 ticks");
        }
        if (uncontestedEvaluationsToWin < 1 || uncontestedEvaluationsToWin > 1_000_000) {
            throw new IllegalArgumentException(
                    "Clan-War uncontested evaluations to win must be between 1 and 1000000"
            );
        }
        if (matchTimeoutSeconds < 1 || matchTimeoutSeconds > 86_400) {
            throw new IllegalArgumentException("Clan-War match timeout must be between 1 and 86400 seconds");
        }
        this.radiusBlocks = radiusBlocks;
        this.evaluationPeriodTicks = evaluationPeriodTicks;
        this.uncontestedEvaluationsToWin = uncontestedEvaluationsToWin;
        this.matchTimeoutSeconds = matchTimeoutSeconds;
    }

    double getRadiusBlocks() {
        return radiusBlocks;
    }

    int getEvaluationPeriodTicks() {
        return evaluationPeriodTicks;
    }

    int getUncontestedEvaluationsToWin() {
        return uncontestedEvaluationsToWin;
    }

    int getMatchTimeoutSeconds() {
        return matchTimeoutSeconds;
    }
}

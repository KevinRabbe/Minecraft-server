package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/** Counts frozen Clan-War participants currently occupying the objective without assuming a team size. */
final class LegacyClanWarControlPointPresence {
    private LegacyClanWarControlPointPresence() { }

    static Counts count(LegacyClanWarExecution war, Predicate<UUID> isInside) {
        Objects.requireNonNull(war, "war");
        Objects.requireNonNull(isInside, "isInside");
        int challenger = 0;
        int defender = 0;
        for (LegacyParticipant participant : war.getExecution().getParticipants()) {
            if (!isInside.test(participant.getMinecraftUuid())) continue;
            if ("CHALLENGER".equals(participant.getSideKey())) {
                challenger++;
            } else if ("DEFENDER".equals(participant.getSideKey())) {
                defender++;
            } else {
                throw new IllegalArgumentException(
                        "Clan-War participant has unsupported side " + participant.getSideKey()
                );
            }
        }
        return new Counts(challenger, defender);
    }

    static final class Counts {
        private final int challenger;
        private final int defender;

        Counts(int challenger, int defender) {
            if (challenger < 0 || defender < 0) {
                throw new IllegalArgumentException("control-point counts must be >= 0");
            }
            this.challenger = challenger;
            this.defender = defender;
        }

        int getChallenger() {
            return challenger;
        }

        int getDefender() {
            return defender;
        }
    }
}

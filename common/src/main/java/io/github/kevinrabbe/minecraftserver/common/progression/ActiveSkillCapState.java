package io.github.kevinrabbe.minecraftserver.common.progression;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ActiveSkillCapState(
        SkillCapStage stage,
        long stateVersion,
        UUID sourceOperationId,
        Instant changedAt
) {
    public ActiveSkillCapState {
        stage = Objects.requireNonNull(stage, "stage");
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion must be >= 0");
        }
        changedAt = Objects.requireNonNull(changedAt, "changedAt");
        if (stage != SkillCapStage.LAUNCH && sourceOperationId == null) {
            throw new IllegalArgumentException("post-launch cap stage requires sourceOperationId");
        }
    }

    public int activeCap() {
        return stage.activeCap();
    }
}

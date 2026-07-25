package io.github.kevinrabbe.minecraftserver.common.pve.map;

import java.util.List;
import java.util.UUID;

/** Server-owned, versioned reward policy for one immutable completed Map run. */
public interface MapRewardResolver {
    int version();

    List<MapRewardDefinition> resolve(MapRunSnapshot completedRun, List<UUID> participantPlayerIds);
}

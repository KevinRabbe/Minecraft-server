package io.github.kevinrabbe.minecraftserver.common.clan;

import java.util.Objects;
import java.util.UUID;

/** Immutable result of one successful clan leave/removal operation. */
public record ClanMembershipRemovalResult(
        UUID clanId,
        UUID playerId,
        ClanRole formerRole
) {
    public ClanMembershipRemovalResult {
        clanId = Objects.requireNonNull(clanId, "clanId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        formerRole = Objects.requireNonNull(formerRole, "formerRole");
        if (formerRole == ClanRole.LEADER) {
            throw new IllegalArgumentException("leader membership cannot be removed directly");
        }
    }
}

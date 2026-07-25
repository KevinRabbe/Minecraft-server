package io.github.kevinrabbe.minecraftserver.common.clan;

import java.util.Objects;

/** Atomic leadership transfer result containing both resulting memberships. */
public record ClanLeadershipTransferResult(
        ClanMemberSnapshot newLeader,
        ClanMemberSnapshot formerLeader
) {
    public ClanLeadershipTransferResult {
        newLeader = Objects.requireNonNull(newLeader, "newLeader");
        formerLeader = Objects.requireNonNull(formerLeader, "formerLeader");
        if (!newLeader.clanId().equals(formerLeader.clanId())
                || newLeader.role() != ClanRole.LEADER
                || formerLeader.role() == ClanRole.LEADER) {
            throw new IllegalArgumentException("invalid leadership transfer result");
        }
    }
}

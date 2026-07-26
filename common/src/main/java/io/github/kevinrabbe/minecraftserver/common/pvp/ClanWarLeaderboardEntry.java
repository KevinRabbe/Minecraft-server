package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.util.Objects;
import java.util.UUID;

/** One row in the isolated 1.8.9 Clan Wars ladder. */
public record ClanWarLeaderboardEntry(
        int rank,
        UUID clanId,
        String clanName,
        String clanTag,
        int rating,
        long wins,
        long losses
) {
    public ClanWarLeaderboardEntry {
        if (rank < 1 || rating < 0 || wins < 0 || losses < 0) {
            throw new IllegalArgumentException("rank/rating/wins/losses must be nonnegative and rank must be positive");
        }
        clanId = Objects.requireNonNull(clanId, "clanId");
        if (clanName == null || clanName.isBlank() || clanTag == null || clanTag.isBlank()) {
            throw new IllegalArgumentException("clan name/tag must not be blank");
        }
        clanName = clanName.trim();
        clanTag = clanTag.trim();
    }
}

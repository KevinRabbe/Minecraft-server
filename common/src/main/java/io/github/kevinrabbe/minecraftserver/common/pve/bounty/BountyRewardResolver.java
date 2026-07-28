package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import java.util.Map;
import java.util.UUID;

/** Server-owned deterministic/RNG reward resolution for one successfully killed bounty boss. */
@FunctionalInterface
public interface BountyRewardResolver {
    /** Returns positive commodity quantities; the repository enforces the tier's configured material allowlist. */
    Map<String, Long> resolve(UUID contractId, BountyTierDefinition tierDefinition);
}

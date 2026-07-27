package io.github.kevinrabbe.minecraftserver.common.item;

import java.util.UUID;

/**
 * Adapter-owned proof that one exact carried unique-item representation advanced with its authoritative item version.
 * Implementations must be deterministic and must not mutate the supplied payload arrays.
 */
@FunctionalInterface
public interface PlayerItemUpgradeStateValidator {
    void verifyUpgrade(
            UUID playerId,
            UUID itemInstanceId,
            String definitionId,
            long fromAuthorityVersion,
            long toAuthorityVersion,
            int fromUpgradeLevel,
            int toUpgradeLevel,
            byte[] currentStatePayload,
            byte[] nextStatePayload
    );
}

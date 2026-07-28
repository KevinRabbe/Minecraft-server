package io.github.kevinrabbe.minecraftserver.velocity;

/**
 * Network-side protocol gate for the isolated legacy competitive category.
 * Minecraft 1.8 through 1.8.9 share protocol 47, so the proxy can enforce that protocol family while the player-facing
 * requirement remains the canonical 1.8.9 client.
 */
final class CompetitiveClientProtocolPolicy {
    static final int LEGACY_COMPETITIVE_PROTOCOL = 47;

    private CompetitiveClientProtocolPolicy() { }

    static boolean accepts(int protocol) {
        return protocol == LEGACY_COMPETITIVE_PROTOCOL;
    }
}

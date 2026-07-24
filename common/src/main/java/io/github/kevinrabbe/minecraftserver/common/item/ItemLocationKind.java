package io.github.kevinrabbe.minecraftserver.common.item;

/** Current authoritative locations for individual items. Add new custody types only with their owning system. */
public enum ItemLocationKind {
    PLAYER_INVENTORY,
    PENDING_DELIVERY,
    QUARANTINE,
    DESTROYED
}

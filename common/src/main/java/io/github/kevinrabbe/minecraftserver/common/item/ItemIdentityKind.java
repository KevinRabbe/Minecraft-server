package io.github.kevinrabbe.minecraftserver.common.item;

/**
 * Persistence identity model for an item definition.
 *
 * <p>COMMODITY values are fungible quantities. INDIVIDUAL values require one stable item_instance_id per live item.</p>
 */
public enum ItemIdentityKind {
    COMMODITY,
    INDIVIDUAL
}

package io.github.kevinrabbe.minecraftserver.common.economy;

/** Resolves and validates one stable fungible commodity definition identifier. */
@FunctionalInterface
public interface CommodityDefinitionResolver {
    /** Returns the canonical stable definition ID or throws when the definition is unknown/non-fungible. */
    String requireCommodity(String definitionId);
}

package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationAuthorityValidator;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationClaim;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationIssue;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationValidationResult;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import io.papermc.paper.datacomponent.item.ChargedProjectiles;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import io.papermc.paper.datacomponent.item.UseRemainder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Extracts custom identity claims from loaded player inventory and validates them against PostgreSQL authority. */
@SuppressWarnings("UnstableApiUsage")
final class PaperPlayerItemRepresentationValidator {
    private static final int MAX_NESTED_ITEM_DEPTH = 8;
    private static final int MAX_VISITED_ITEM_STACKS = 4_096;

    private final DataSource dataSource;
    private final PaperItemIdentityCodec identityCodec;
    private final ItemRepresentationAuthorityValidator authorityValidator;

    PaperPlayerItemRepresentationValidator(
            MinecraftServerPlugin plugin,
            DataSource dataSource,
            ItemCatalog itemCatalog
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        identityCodec = new PaperItemIdentityCodec(Objects.requireNonNull(plugin, "plugin"));
        authorityValidator = new ItemRepresentationAuthorityValidator(
                dataSource,
                Objects.requireNonNull(itemCatalog, "itemCatalog")
        );
    }

    List<ItemRepresentationIssue> validate(Player player) throws SQLException {
        return validateAndSnapshot(player).issues();
    }

    ItemRepresentationValidationResult validateAndSnapshot(Player player) throws SQLException {
        Objects.requireNonNull(player, "player");
        return validateAndSnapshot(player.getUniqueId(), collectClaims(player));
    }

    List<ItemRepresentationClaim> collectClaims(Player player) {
        Objects.requireNonNull(player, "player");
        ArrayList<ItemRepresentationClaim> claims = new ArrayList<>();
        TraversalBudget budget = new TraversalBudget();
        collect(player.getInventory().getStorageContents(), "storage", claims, budget);
        collect(player.getInventory().getArmorContents(), "armor", claims, budget);
        collect(player.getInventory().getExtraContents(), "extra", claims, budget);
        return List.copyOf(claims);
    }

    ItemRepresentationValidationResult validateAndSnapshot(
            UUID minecraftUuid,
            List<ItemRepresentationClaim> claims
    ) throws SQLException {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        Objects.requireNonNull(claims, "claims");
        if (claims.isEmpty()) {
            return new ItemRepresentationValidationResult(List.of(), java.util.Map.of());
        }
        UUID playerId = requirePlayerId(minecraftUuid);
        return authorityValidator.validateAndSnapshot(playerId, claims);
    }

    private UUID requirePlayerId(UUID minecraftUuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT player_id
                     FROM players
                     WHERE minecraft_uuid = ?
                     """)) {
            statement.setObject(1, minecraftUuid);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new PaperItemRepresentationException(
                            "Authenticated Minecraft UUID has no stable internal player identity"
                    );
                }
                return results.getObject("player_id", UUID.class);
            }
        }
    }

    private void collect(
            ItemStack[] contents,
            String section,
            List<ItemRepresentationClaim> claims,
            TraversalBudget budget
    ) {
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            collectStack(stack, section + '[' + slot + ']', claims, NestedMode.PLAYER_INVENTORY, 0, budget);
        }
    }

    private void collectStack(
            ItemStack stack,
            String source,
            List<ItemRepresentationClaim> claims,
            NestedMode mode,
            int depth,
            TraversalBudget budget
    ) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        if (depth > MAX_NESTED_ITEM_DEPTH) {
            throw new PaperItemRepresentationException(
                    "Nested item representation exceeds maximum depth at " + source
            );
        }
        budget.visit(source);

        var claim = identityCodec.readClaim(stack, source);
        if (claim.isPresent()) {
            if (mode == NestedMode.UNSUPPORTED_TRANSFORM) {
                throw new PaperItemRepresentationException(
                        "Managed item representation is nested inside an unsupported transform/consumption component at "
                                + source
                );
            }
            claims.add(claim.orElseThrow());
        }

        BundleContents bundle = stack.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null) {
            collectNested(
                    bundle.contents(),
                    source + ".bundle",
                    claims,
                    mode,
                    depth + 1,
                    budget
            );
        }

        ItemContainerContents container = stack.getData(DataComponentTypes.CONTAINER);
        if (container != null) {
            collectNested(
                    container.contents(),
                    source + ".container",
                    claims,
                    mode,
                    depth + 1,
                    budget
            );
        }

        ChargedProjectiles charged = stack.getData(DataComponentTypes.CHARGED_PROJECTILES);
        if (charged != null) {
            collectNested(
                    charged.projectiles(),
                    source + ".charged_projectiles",
                    claims,
                    NestedMode.UNSUPPORTED_TRANSFORM,
                    depth + 1,
                    budget
            );
        }

        UseRemainder useRemainder = stack.getData(DataComponentTypes.USE_REMAINDER);
        if (useRemainder != null) {
            collectStack(
                    useRemainder.transformInto(),
                    source + ".use_remainder",
                    claims,
                    NestedMode.UNSUPPORTED_TRANSFORM,
                    depth + 1,
                    budget
            );
        }
    }

    private void collectNested(
            List<ItemStack> contents,
            String source,
            List<ItemRepresentationClaim> claims,
            NestedMode mode,
            int depth,
            TraversalBudget budget
    ) {
        for (int index = 0; index < contents.size(); index++) {
            collectStack(contents.get(index), source + '[' + index + ']', claims, mode, depth, budget);
        }
    }

    private enum NestedMode {
        PLAYER_INVENTORY,
        UNSUPPORTED_TRANSFORM
    }

    private static final class TraversalBudget {
        private int visited;

        void visit(String source) {
            visited++;
            if (visited > MAX_VISITED_ITEM_STACKS) {
                throw new PaperItemRepresentationException(
                        "Nested item representation exceeds maximum stack count at " + source
                );
            }
        }
    }
}

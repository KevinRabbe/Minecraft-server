package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationClaim;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import io.papermc.paper.datacomponent.item.ChargedProjectiles;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import io.papermc.paper.datacomponent.item.UseRemainder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One bounded definition of "contains network-managed value" for Paper ItemStacks.
 *
 * <p>Supported bundle/container nesting remains coarse player-inventory custody. Transform/consumption components are
 * traversed too so custody gates cannot be bypassed by wrapping managed value inside an otherwise untagged outer item;
 * the authoritative player-inventory validator additionally rejects managed claims in those unsupported transform
 * locations.</p>
 */
@SuppressWarnings("UnstableApiUsage")
final class PaperManagedItemScanner {
    private static final int MAX_NESTED_ITEM_DEPTH = 8;
    private static final int MAX_VISITED_ITEM_STACKS = 4_096;

    private final PaperItemIdentityCodec identityCodec;

    PaperManagedItemScanner(MinecraftServerPlugin plugin) {
        this.identityCodec = new PaperItemIdentityCodec(Objects.requireNonNull(plugin, "plugin"));
    }

    List<ItemRepresentationClaim> collectPlayerInventoryClaims(PlayerInventory inventory) {
        Objects.requireNonNull(inventory, "inventory");
        return collectInventoryClaims(
                inventory.getStorageContents(),
                inventory.getArmorContents(),
                inventory.getExtraContents()
        );
    }

    List<ItemRepresentationClaim> collectStoredInventoryClaims(PaperPlayerStateCodec.InventoryState state) {
        Objects.requireNonNull(state, "state");
        return collectInventoryClaims(state.storage(), state.armor(), state.extra());
    }

    private List<ItemRepresentationClaim> collectInventoryClaims(
            ItemStack[] storage,
            ItemStack[] armor,
            ItemStack[] extra
    ) {
        ArrayList<ItemRepresentationClaim> claims = new ArrayList<>();
        TraversalBudget budget = new TraversalBudget();
        collectSection(storage, "storage", claims, budget);
        collectSection(armor, "armor", claims, budget);
        collectSection(extra, "extra", claims, budget);
        return List.copyOf(claims);
    }

    boolean containsManaged(ItemStack stack, String source) {
        return containsManaged(stack, requireSource(source), 0, new TraversalBudget());
    }

    /**
     * Detects managed value below the outer ItemStack while still validating the outer identity envelope itself.
     * Useful when direct use/placement of a top-level managed item is a separate product decision, but moving nested
     * managed value out of player-inventory custody is already forbidden.
     */
    boolean containsNestedManaged(ItemStack stack, String source) {
        String normalizedSource = requireSource(source);
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        TraversalBudget budget = new TraversalBudget();
        requireDepth(0, normalizedSource);
        budget.visit(normalizedSource);
        identityCodec.readClaim(stack, normalizedSource); // validate outer metadata without treating it as nested value
        return nestedComponentsContainManaged(stack, normalizedSource, 1, budget);
    }

    private void collectSection(
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
            collectClaims(stack, section + '[' + slot + ']', claims, NestedMode.PLAYER_INVENTORY, 0, budget);
        }
    }

    private void collectClaims(
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
        requireDepth(depth, source);
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
            collectNestedClaims(bundle.contents(), source + ".bundle", claims, mode, depth + 1, budget);
        }

        ItemContainerContents container = stack.getData(DataComponentTypes.CONTAINER);
        if (container != null) {
            collectNestedClaims(container.contents(), source + ".container", claims, mode, depth + 1, budget);
        }

        ChargedProjectiles charged = stack.getData(DataComponentTypes.CHARGED_PROJECTILES);
        if (charged != null) {
            collectNestedClaims(
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
            collectClaims(
                    useRemainder.transformInto(),
                    source + ".use_remainder",
                    claims,
                    NestedMode.UNSUPPORTED_TRANSFORM,
                    depth + 1,
                    budget
            );
        }
    }

    private void collectNestedClaims(
            List<ItemStack> contents,
            String source,
            List<ItemRepresentationClaim> claims,
            NestedMode mode,
            int depth,
            TraversalBudget budget
    ) {
        for (int index = 0; index < contents.size(); index++) {
            collectClaims(contents.get(index), source + '[' + index + ']', claims, mode, depth, budget);
        }
    }

    private boolean containsManaged(
            ItemStack stack,
            String source,
            int depth,
            TraversalBudget budget
    ) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        requireDepth(depth, source);
        budget.visit(source);

        if (identityCodec.readClaim(stack, source).isPresent()) {
            return true;
        }

        return nestedComponentsContainManaged(stack, source, depth + 1, budget);
    }

    private boolean nestedComponentsContainManaged(
            ItemStack stack,
            String source,
            int childDepth,
            TraversalBudget budget
    ) {
        BundleContents bundle = stack.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null && containsManaged(bundle.contents(), source + ".bundle", childDepth, budget)) {
            return true;
        }

        ItemContainerContents container = stack.getData(DataComponentTypes.CONTAINER);
        if (container != null && containsManaged(container.contents(), source + ".container", childDepth, budget)) {
            return true;
        }

        ChargedProjectiles charged = stack.getData(DataComponentTypes.CHARGED_PROJECTILES);
        if (charged != null
                && containsManaged(charged.projectiles(), source + ".charged_projectiles", childDepth, budget)) {
            return true;
        }

        UseRemainder useRemainder = stack.getData(DataComponentTypes.USE_REMAINDER);
        return useRemainder != null
                && containsManaged(useRemainder.transformInto(), source + ".use_remainder", childDepth, budget);
    }

    private boolean containsManaged(
            List<ItemStack> contents,
            String source,
            int depth,
            TraversalBudget budget
    ) {
        for (int index = 0; index < contents.size(); index++) {
            if (containsManaged(contents.get(index), source + '[' + index + ']', depth, budget)) {
                return true;
            }
        }
        return false;
    }

    private static void requireDepth(int depth, String source) {
        if (depth > MAX_NESTED_ITEM_DEPTH) {
            throw new PaperItemRepresentationException(
                    "Nested item representation exceeds maximum depth at " + source
            );
        }
    }

    private static String requireSource(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        return source.trim();
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

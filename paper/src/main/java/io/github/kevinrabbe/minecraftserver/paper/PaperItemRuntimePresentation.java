package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.IntrinsicRollResolver;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationClaim;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRuntimeStatSnapshot;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Central owner of derived presentation for managed individualized ItemStack representations and market inspection. */
final class PaperItemRuntimePresentation {
    private final ItemCatalog itemCatalog;
    private final PaperItemIdentityCodec identityCodec;

    PaperItemRuntimePresentation(MinecraftServerPlugin plugin, ItemCatalog itemCatalog) {
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.identityCodec = new PaperItemIdentityCodec(Objects.requireNonNull(plugin, "plugin"));
    }

    void refresh(Player player, Map<UUID, ItemRuntimeStatSnapshot> snapshots) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(snapshots, "snapshots");
        refreshSection(player.getInventory().getStorageContents(), "storage", snapshots);
        refreshSection(player.getInventory().getArmorContents(), "armor", snapshots);
        refreshSection(player.getInventory().getExtraContents(), "extra", snapshots);
    }

    private void refreshSection(
            ItemStack[] contents,
            String section,
            Map<UUID, ItemRuntimeStatSnapshot> snapshots
    ) {
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.isEmpty()) continue;
            ItemRepresentationClaim claim = identityCodec.readClaim(stack, section + "[" + slot + "]").orElse(null);
            if (claim == null || claim.itemInstanceId() == null || claim.authorityVersion() == null) continue;

            ItemRuntimeStatSnapshot snapshot = snapshots.get(claim.itemInstanceId());
            if (snapshot == null
                    || snapshot.stateVersion() != claim.authorityVersion()
                    || !snapshot.definitionId().equals(claim.definitionId())) {
                continue;
            }

            ItemDefinition definition = itemCatalog.require(snapshot.definitionId());
            List<String> descriptions = describe(definition, snapshot);
            boolean edited = stack.editMeta(meta -> meta.lore(
                    descriptions.isEmpty()
                            ? null
                            : descriptions.stream().map(Component::text).toList()
            ));
            if (!edited) {
                throw new PaperItemRepresentationException(
                        "Could not apply managed item presentation for " + snapshot.itemInstanceId()
                );
            }
        }
    }

    static List<String> describe(ItemDefinition definition, ItemRuntimeStatSnapshot snapshot) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!definition.definitionId().equals(snapshot.definitionId())) {
            throw new IllegalArgumentException("snapshot definition does not match item definition");
        }

        List<String> rollLines = describeRolls(definition, snapshot.normalizedRollQualityBasisPoints());
        Map<String, Integer> currentMultipliers = IntrinsicRollResolver.resolveMultipliers(
                definition.rollProfile(),
                snapshot.normalizedRollQualityBasisPoints()
        );
        if (!currentMultipliers.equals(snapshot.intrinsicMultipliersBasisPoints())) {
            throw new IllegalArgumentException("snapshot intrinsic multipliers do not match current item definition");
        }

        ArrayList<String> lines = new ArrayList<>(rollLines);
        if (definition.category() == ItemCategory.EQUIPMENT || snapshot.upgradeState().level() > 0) {
            lines.add("Upgrade: +" + snapshot.upgradeState().level());
        }
        return List.copyOf(lines);
    }

    static List<String> describeRolls(
            ItemDefinition definition,
            Map<String, Integer> normalizedRollQualityBasisPoints
    ) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(normalizedRollQualityBasisPoints, "normalizedRollQualityBasisPoints");
        Map<String, Integer> multipliers = IntrinsicRollResolver.resolveMultipliers(
                definition.rollProfile(),
                normalizedRollQualityBasisPoints
        );

        return normalizedRollQualityBasisPoints.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> {
                    Integer multiplier = multipliers.get(entry.getKey());
                    if (multiplier == null) {
                        throw new IllegalArgumentException("missing intrinsic multiplier for " + entry.getKey());
                    }
                    return entry.getKey() + " roll: " + formatPercent(entry.getValue())
                            + " quality (" + formatPercent(multiplier) + " base)";
                })
                .toList();
    }

    private static String formatPercent(int basisPoints) {
        return String.format(Locale.ROOT, "%d.%02d%%", basisPoints / 100, basisPoints % 100);
    }
}

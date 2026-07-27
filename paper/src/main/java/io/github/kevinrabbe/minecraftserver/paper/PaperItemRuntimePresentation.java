package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.IntrinsicRollResolver;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRuntimeStatSnapshot;
import io.github.kevinrabbe.minecraftserver.common.item.UpgradeState;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Central owner of derived presentation for managed individualized ItemStack representations and market inspection. */
final class PaperItemRuntimePresentation {
    private final ItemCatalog itemCatalog;
    private final PaperManagedItemRuntimeResolver runtimeResolver;

    PaperItemRuntimePresentation(MinecraftServerPlugin plugin, ItemCatalog itemCatalog) {
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.runtimeResolver = new PaperManagedItemRuntimeResolver(
                Objects.requireNonNull(plugin, "plugin"),
                itemCatalog
        );
    }

    void refresh(Player player) {
        Objects.requireNonNull(player, "player");
        refreshSection(player, player.getInventory().getStorageContents(), "storage");
        refreshSection(player, player.getInventory().getArmorContents(), "armor");
        refreshSection(player, player.getInventory().getExtraContents(), "extra");
    }

    private void refreshSection(
            Player player,
            ItemStack[] contents,
            String section
    ) {
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.isEmpty()) continue;
            String source = section + "[" + slot + "]";
            ItemRuntimeStatSnapshot snapshot = runtimeResolver.find(player, stack, source).orElse(null);
            if (snapshot == null) continue;

            ItemDefinition definition = itemCatalog.require(snapshot.definitionId());
            List<String> descriptions = describe(definition, snapshot);
            if (descriptions.isEmpty()) {
                // This presenter owns rolled/upgrade gear lines only. Do not erase Map/artifact/other-system lore.
                continue;
            }
            boolean edited = stack.editMeta(meta -> meta.lore(
                    descriptions.stream().map(Component::text).toList()
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
        describeUpgrade(definition, snapshot.upgradeState().level()).ifPresent(lines::add);
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

    static Optional<String> describeUpgrade(ItemDefinition definition, int upgradeLevel) {
        Objects.requireNonNull(definition, "definition");
        UpgradeState upgradeState = new UpgradeState(upgradeLevel);
        if (definition.category() == ItemCategory.EQUIPMENT) {
            return Optional.of("Upgrade: +" + upgradeState.level());
        }
        if (upgradeState.level() != 0) {
            throw new IllegalArgumentException(
                    "non-equipment definition carries generic upgrade state: " + upgradeState.level()
            );
        }
        return Optional.empty();
    }

    private static String formatPercent(int basisPoints) {
        return String.format(Locale.ROOT, "%d.%02d%%", basisPoints / 100, basisPoints % 100);
    }
}

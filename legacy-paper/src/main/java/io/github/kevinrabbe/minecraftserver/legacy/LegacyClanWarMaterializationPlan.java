package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Complete pure-data preparation for one disposable Clan-War materialization.
 *
 * <p>This object proves that every frozen participant has a local spawn and inventory projection before any Bukkit
 * world/player mutation occurs. Persistent item UUIDs never enter this model.</p>
 */
final class LegacyClanWarMaterializationPlan {
    private final LegacyClanWarExecution war;
    private final LegacyClanWarRepresentationPlan representationPlan;
    private final LegacyClanWarInventoryProjection inventoryProjection;
    private final Map<UUID, LegacyClanWarSpawnLayout.SpawnPoint> spawnLayout;

    private LegacyClanWarMaterializationPlan(
            LegacyClanWarExecution war,
            LegacyClanWarRepresentationPlan representationPlan,
            LegacyClanWarInventoryProjection inventoryProjection,
            Map<UUID, LegacyClanWarSpawnLayout.SpawnPoint> spawnLayout
    ) {
        this.war = war;
        this.representationPlan = representationPlan;
        this.inventoryProjection = inventoryProjection;
        this.spawnLayout = spawnLayout;
    }

    static LegacyClanWarMaterializationPlan build(
            LegacyClanWarExecution war,
            LegacyClanWarLoadout loadout,
            LegacyClanWarRepresentationCatalog representationCatalog,
            LegacyClanWarArenaSettings arenaSettings
    ) {
        Objects.requireNonNull(war, "war");
        Objects.requireNonNull(loadout, "loadout");
        Objects.requireNonNull(representationCatalog, "representationCatalog");
        Objects.requireNonNull(arenaSettings, "arenaSettings");

        LegacyClanWarRepresentationPlan representations = LegacyClanWarRepresentationPlan.build(
                war,
                loadout,
                representationCatalog
        );
        LegacyClanWarInventoryProjection inventory = LegacyClanWarInventoryProjection.build(representations);
        Map<UUID, LegacyClanWarSpawnLayout.SpawnPoint> spawns = LegacyClanWarSpawnLayout.build(war, arenaSettings);

        LinkedHashSet<UUID> expectedPlayers = new LinkedHashSet<UUID>();
        for (LegacyParticipant participant : war.getExecution().getParticipants()) {
            expectedPlayers.add(participant.getMinecraftUuid());
        }
        Set<UUID> immutableExpected = Collections.unmodifiableSet(expectedPlayers);
        if (!inventory.getItemsByMinecraftUuid().keySet().equals(immutableExpected)) {
            throw new IllegalArgumentException("Clan-War inventory projection does not cover the exact frozen roster");
        }
        if (!spawns.keySet().equals(immutableExpected)) {
            throw new IllegalArgumentException("Clan-War spawn layout does not cover the exact frozen roster");
        }

        return new LegacyClanWarMaterializationPlan(war, representations, inventory, spawns);
    }

    LegacyClanWarExecution getWar() {
        return war;
    }

    LegacyClanWarRepresentationPlan getRepresentationPlan() {
        return representationPlan;
    }

    LegacyClanWarInventoryProjection getInventoryProjection() {
        return inventoryProjection;
    }

    Map<UUID, LegacyClanWarSpawnLayout.SpawnPoint> getSpawnLayout() {
        return spawnLayout;
    }
}

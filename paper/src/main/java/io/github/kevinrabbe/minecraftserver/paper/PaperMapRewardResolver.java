package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.pve.map.MapDifficulty;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRewardDefinition;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRewardResolver;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunDefinition;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Launch reward policy: each participant in a completed supported Map receives one bounded successor Map. */
final class PaperMapRewardResolver implements MapRewardResolver {
    private static final int VERSION = 1;

    private final PaperMapEncounterContentCatalog content;

    PaperMapRewardResolver(PaperMapEncounterContentCatalog content) {
        this.content = Objects.requireNonNull(content, "content");
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public List<MapRewardDefinition> resolve(MapRunSnapshot completedRun, List<UUID> participantPlayerIds) {
        Objects.requireNonNull(completedRun, "completedRun");
        Objects.requireNonNull(participantPlayerIds, "participantPlayerIds");
        if (completedRun.status() != MapRunStatus.COMPLETED) {
            throw new IllegalArgumentException("Map rewards may only resolve from a COMPLETED run");
        }
        PaperMapEncounterDefinition definition = content.require(completedRun.definition());
        MapRunDefinition current = completedRun.definition();
        MapRunDefinition successor = new MapRunDefinition(
                new MapDifficulty(definition.successorDifficulty(current.difficulty().value())),
                current.environmentId(),
                current.enemyFamilyId(),
                current.objectiveId(),
                current.modifierIds(),
                successorSeed(completedRun.runId(), current.generationSeed()),
                current.generationVersion(),
                current.balanceVersion(),
                current.worldEraId()
        );

        ArrayList<MapRewardDefinition> rewards = new ArrayList<>(participantPlayerIds.size());
        for (UUID playerId : participantPlayerIds) {
            rewards.add(MapRewardDefinition.map(
                    Objects.requireNonNull(playerId, "participantPlayerIds must not contain null"),
                    definition.rewardMapDefinitionId(),
                    successor
            ));
        }
        return List.copyOf(rewards);
    }

    private static long successorSeed(UUID runId, long generationSeed) {
        long value = generationSeed ^ runId.getMostSignificantBits() ^ Long.rotateLeft(runId.getLeastSignificantBits(), 29);
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53l;
        value ^= value >>> 33;
        return value;
    }
}

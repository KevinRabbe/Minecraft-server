package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemSkillRequirement;
import io.github.kevinrabbe.minecraftserver.common.item.ItemUseEligibility;
import io.github.kevinrabbe.minecraftserver.common.item.ItemUseRequirements;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressSnapshot;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionQueryRepository;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillXpAwardResult;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Bounded local projection used by Paper action/equip checks.
 *
 * <p>This cache is never progression authority. Restricted items fail closed when the player has no attached
 * snapshot. A fresh authoritative projection should be loaded when a persistent player session attaches, committed
 * XP results may advance an already-attached snapshot without another database read, and detach/transfer should
 * invalidate the snapshot. Skill progression is monotonic in the current V1 authority, so an older snapshot can
 * delay newly-earned permission but cannot grant permission early. Version checks below additionally prevent a
 * stale refresh from rolling a newer committed XP result backward.</p>
 */
final class PaperItemUseEligibilityCache {
    private final ItemCatalog itemCatalog;
    private final SkillProjectionLoader skillLoader;
    private final List<SkillId> relevantSkills;
    private final Set<SkillId> relevantSkillSet;
    private final int maxPlayers;
    private final Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();

    PaperItemUseEligibilityCache(
            ItemCatalog itemCatalog,
            SkillProgressionQueryRepository skills,
            int maxPlayers
    ) {
        this(itemCatalog, queryLoader(skills), maxPlayers);
    }

    PaperItemUseEligibilityCache(
            ItemCatalog itemCatalog,
            SkillProjectionLoader skillLoader,
            int maxPlayers
    ) {
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.skillLoader = Objects.requireNonNull(skillLoader, "skillLoader");
        if (maxPlayers < 1) {
            throw new IllegalArgumentException("maxPlayers must be >= 1");
        }
        this.maxPlayers = maxPlayers;

        TreeSet<SkillId> required = new TreeSet<>(Comparator.comparing(SkillId::value));
        for (ItemDefinition definition : itemCatalog.definitions()) {
            for (ItemSkillRequirement requirement : definition.useRequirements().skillRequirements()) {
                required.add(requirement.skillId());
            }
        }
        relevantSkills = List.copyOf(required);
        relevantSkillSet = Set.copyOf(required);
    }

    /** Loads every skill used by the current item catalog in one bounded authoritative projection. */
    void refresh(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        if (relevantSkills.isEmpty()) {
            return;
        }

        Map<SkillId, SkillProgressSnapshot> loaded = skillLoader.load(playerId, relevantSkills);
        LinkedHashMap<SkillId, LevelState> incoming = new LinkedHashMap<>();
        for (SkillId skillId : relevantSkills) {
            SkillProgressSnapshot snapshot = loaded.get(skillId);
            if (snapshot == null) {
                throw new IllegalStateException("Progression projection omitted required skill " + skillId);
            }
            if (!playerId.equals(snapshot.playerId()) || !skillId.equals(snapshot.skillId())) {
                throw new IllegalStateException("Progression projection returned mismatched player/skill identity");
            }
            incoming.put(skillId, new LevelState(snapshot.level(), snapshot.stateVersion()));
        }

        synchronized (snapshots) {
            PlayerSnapshot current = snapshots.get(playerId);
            if (current == null && snapshots.size() >= maxPlayers) {
                throw new IllegalStateException("Item-use eligibility cache is full");
            }

            try {
                snapshots.put(playerId, merge(current, incoming));
            } catch (RuntimeException exception) {
                snapshots.remove(playerId);
                throw exception;
            }
        }
    }

    /**
     * Returns empty for a restricted definition when no trustworthy attached snapshot exists. Unrestricted items do
     * not require a progression snapshot at all.
     */
    Optional<ItemUseEligibility> evaluate(UUID playerId, String definitionId) {
        Objects.requireNonNull(playerId, "playerId");
        ItemDefinition definition = itemCatalog.require(definitionId);
        ItemUseRequirements requirements = definition.useRequirements();
        if (requirements.unrestricted()) {
            return Optional.of(new ItemUseEligibility(definition.definitionId(), Map.of(), List.of()));
        }

        PlayerSnapshot snapshot;
        synchronized (snapshots) {
            snapshot = snapshots.get(playerId);
        }
        if (snapshot == null) {
            return Optional.empty();
        }

        LinkedHashMap<SkillId, Integer> levels = new LinkedHashMap<>();
        for (ItemSkillRequirement requirement : requirements.skillRequirements()) {
            LevelState state = snapshot.skills().get(requirement.skillId());
            if (state == null) {
                invalidate(playerId);
                return Optional.empty();
            }
            levels.put(requirement.skillId(), state.level());
        }

        Map<SkillId, Integer> currentLevels = Map.copyOf(levels);
        return Optional.of(new ItemUseEligibility(
                definition.definitionId(),
                currentLevels,
                requirements.unmet(currentLevels)
        ));
    }

    /** Advances an attached snapshot from a result that has already committed in progression authority. */
    void applyCommittedAward(SkillXpAwardResult award) {
        Objects.requireNonNull(award, "award");
        if (!relevantSkillSet.contains(award.skillId())) {
            return;
        }

        synchronized (snapshots) {
            PlayerSnapshot current = snapshots.get(award.playerId());
            if (current == null) {
                return;
            }
            LevelState previous = current.skills().get(award.skillId());
            if (previous == null) {
                snapshots.remove(award.playerId());
                return;
            }

            if (award.stateVersion() < previous.stateVersion()) {
                return;
            }
            if (award.stateVersion() == previous.stateVersion()) {
                if (award.newLevel() != previous.level()) {
                    snapshots.remove(award.playerId());
                }
                return;
            }
            if (award.newLevel() < previous.level()) {
                // A future non-monotonic progression mechanic must define a new cache contract; fail closed meanwhile.
                snapshots.remove(award.playerId());
                return;
            }

            LinkedHashMap<SkillId, LevelState> next = new LinkedHashMap<>(current.skills());
            next.put(award.skillId(), new LevelState(award.newLevel(), award.stateVersion()));
            snapshots.put(award.playerId(), new PlayerSnapshot(next));
        }
    }

    void invalidate(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (snapshots) {
            snapshots.remove(playerId);
        }
    }

    int cachedPlayerCount() {
        synchronized (snapshots) {
            return snapshots.size();
        }
    }

    private PlayerSnapshot merge(PlayerSnapshot current, Map<SkillId, LevelState> incoming) {
        if (current == null) {
            return new PlayerSnapshot(incoming);
        }

        LinkedHashMap<SkillId, LevelState> merged = new LinkedHashMap<>();
        for (SkillId skillId : relevantSkills) {
            LevelState previous = current.skills().get(skillId);
            LevelState loaded = incoming.get(skillId);
            if (previous == null || loaded == null) {
                throw new IllegalStateException("Incomplete item-use eligibility snapshot for " + skillId);
            }
            if (loaded.stateVersion() < previous.stateVersion()) {
                merged.put(skillId, previous);
                continue;
            }
            if (loaded.stateVersion() > previous.stateVersion() && loaded.level() < previous.level()) {
                throw new IllegalStateException("Non-monotonic skill progression detected for " + skillId);
            }
            if (loaded.stateVersion() == previous.stateVersion() && loaded.level() < previous.level()) {
                merged.put(skillId, previous);
                continue;
            }
            merged.put(skillId, loaded);
        }
        return new PlayerSnapshot(merged);
    }

    private static SkillProjectionLoader queryLoader(SkillProgressionQueryRepository skills) {
        SkillProgressionQueryRepository nonNull = Objects.requireNonNull(skills, "skills");
        return nonNull::load;
    }

    @FunctionalInterface
    interface SkillProjectionLoader {
        Map<SkillId, SkillProgressSnapshot> load(UUID playerId, List<SkillId> skillIds) throws SQLException;
    }

    private record PlayerSnapshot(Map<SkillId, LevelState> skills) {
        private PlayerSnapshot {
            skills = Map.copyOf(Objects.requireNonNull(skills, "skills"));
        }
    }

    private record LevelState(int level, long stateVersion) {
        private LevelState {
            if (level < 0 || stateVersion < 0) {
                throw new IllegalArgumentException("cached skill values must be nonnegative");
            }
        }
    }
}

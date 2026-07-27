package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemRuntimeStatSnapshot;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JVM-local hot-path cache of already validated item stat snapshots.
 *
 * <p>Entries are keyed by stable item identity rather than inventory slot. The cache is disposable runtime state and
 * never becomes item authority. Generation fencing prevents an older async refresh from replacing or invalidating a
 * newer snapshot.</p>
 */
final class PaperItemRuntimeStatCache {
    private static final ConcurrentHashMap<UUID, PlayerCache> PLAYERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, AtomicLong> GENERATIONS = new ConcurrentHashMap<>();

    private PaperItemRuntimeStatCache() { }

    static long beginRefresh(UUID minecraftUuid) {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        return GENERATIONS.computeIfAbsent(minecraftUuid, ignored -> new AtomicLong()).incrementAndGet();
    }

    static void replaceIfCurrent(
            UUID minecraftUuid,
            long generation,
            Map<UUID, ItemRuntimeStatSnapshot> snapshots
    ) {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        Objects.requireNonNull(snapshots, "snapshots");
        AtomicLong current = GENERATIONS.get(minecraftUuid);
        if (current == null || current.get() != generation) return;
        PLAYERS.put(minecraftUuid, new PlayerCache(generation, Map.copyOf(snapshots)));
    }

    static void replaceNow(UUID minecraftUuid, Map<UUID, ItemRuntimeStatSnapshot> snapshots) {
        long generation = beginRefresh(minecraftUuid);
        replaceIfCurrent(minecraftUuid, generation, snapshots);
    }

    static boolean invalidateIfCurrent(UUID minecraftUuid, long generation) {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        AtomicLong current = GENERATIONS.get(minecraftUuid);
        if (current == null || current.get() != generation) return false;
        PLAYERS.remove(minecraftUuid);
        return true;
    }

    static Optional<ItemRuntimeStatSnapshot> find(
            UUID minecraftUuid,
            UUID itemInstanceId,
            String definitionId,
            long authorityVersion
    ) {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        if (definitionId == null || definitionId.isBlank() || authorityVersion < 0) return Optional.empty();
        PlayerCache cache = PLAYERS.get(minecraftUuid);
        if (cache == null) return Optional.empty();
        ItemRuntimeStatSnapshot snapshot = cache.snapshots().get(itemInstanceId);
        if (snapshot == null
                || snapshot.stateVersion() != authorityVersion
                || !snapshot.definitionId().equals(definitionId)) {
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    static void clear(UUID minecraftUuid) {
        if (minecraftUuid == null) return;
        PLAYERS.remove(minecraftUuid);
        GENERATIONS.remove(minecraftUuid);
    }

    private record PlayerCache(
            long generation,
            Map<UUID, ItemRuntimeStatSnapshot> snapshots
    ) {
        private PlayerCache {
            if (generation < 1) throw new IllegalArgumentException("generation must be >= 1");
            snapshots = Map.copyOf(Objects.requireNonNull(snapshots, "snapshots"));
        }
    }
}

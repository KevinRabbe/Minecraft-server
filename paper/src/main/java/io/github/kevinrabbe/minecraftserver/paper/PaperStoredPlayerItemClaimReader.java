package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationClaim;
import io.github.kevinrabbe.minecraftserver.common.item.StoredPlayerItemClaimReader;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.List;

/** Service-loaded Paper decoder for managed claims inside durable player-state payloads. */
public final class PaperStoredPlayerItemClaimReader implements StoredPlayerItemClaimReader {
    private PaperPlayerStateCodec stateCodec;
    private PaperManagedItemScanner managedItems;

    public PaperStoredPlayerItemClaimReader() { }

    @Override
    public List<ItemRepresentationClaim> readClaims(byte[] statePayload) {
        ensureInitialized();
        return managedItems.collectStoredInventoryClaims(stateCodec.decodeState(statePayload));
    }

    private synchronized void ensureInitialized() {
        if (stateCodec != null) {
            return;
        }
        Plugin plugin = Bukkit.getPluginManager().getPlugin("MinecraftServer");
        if (!(plugin instanceof MinecraftServerPlugin minecraftServerPlugin)) {
            throw new PaperItemRepresentationException(
                    "MinecraftServer plugin instance is unavailable for stored item compatibility validation"
            );
        }
        stateCodec = new PaperPlayerStateCodec();
        managedItems = new PaperManagedItemScanner(minecraftServerPlugin);
    }
}

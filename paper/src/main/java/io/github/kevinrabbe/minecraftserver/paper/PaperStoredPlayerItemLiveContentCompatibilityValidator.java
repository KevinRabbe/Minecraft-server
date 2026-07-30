package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.StoredPlayerItemLiveContentCompatibilityValidator;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Objects;

/** Paper adapter for validating every durable serialized player inventory before backend registration. */
final class PaperStoredPlayerItemLiveContentCompatibilityValidator {
    private PaperStoredPlayerItemLiveContentCompatibilityValidator() { }

    static void validate(
            MinecraftServerPlugin plugin,
            DataSource dataSource,
            ItemCatalog itemCatalog
    ) throws SQLException {
        Objects.requireNonNull(plugin, "plugin");
        PaperPlayerStateCodec stateCodec = new PaperPlayerStateCodec();
        PaperManagedItemScanner managedItems = new PaperManagedItemScanner(plugin);
        StoredPlayerItemLiveContentCompatibilityValidator.validate(
                dataSource,
                itemCatalog,
                payload -> managedItems.collectStoredInventoryClaims(stateCodec.decodeState(payload))
        );
    }
}

package io.github.kevinrabbe.minecraftserver.common.economy;

import javax.sql.DataSource;
import java.util.Objects;

/** Creates durable pending commodity deliveries for already-earned game rewards. */
public final class CommodityDeliveryAuthority {
    private final DataSource dataSource;
    private final CommodityDefinitionResolver definitions;

    public CommodityDeliveryAuthority(DataSource dataSource, CommodityDefinitionResolver definitions) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
    }
}

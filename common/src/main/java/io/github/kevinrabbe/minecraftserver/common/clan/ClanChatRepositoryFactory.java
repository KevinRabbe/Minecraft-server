package io.github.kevinrabbe.minecraftserver.common.clan;

import java.util.Objects;

/** Keeps adapter code from receiving the raw clan persistence DataSource. */
public final class ClanChatRepositoryFactory {
    private ClanChatRepositoryFactory() { }

    public static ClanChatRepository from(ClanQueryRepository queries) {
        return new ClanChatRepository(Objects.requireNonNull(queries, "queries").dataSource());
    }
}

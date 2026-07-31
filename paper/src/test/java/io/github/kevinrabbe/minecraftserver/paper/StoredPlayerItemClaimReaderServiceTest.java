package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.StoredPlayerItemClaimReader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StoredPlayerItemClaimReaderServiceTest {
    @Test
    void paperArtifactRegistersExactlyOneStoredItemClaimReader() {
        List<ServiceLoader.Provider<StoredPlayerItemClaimReader>> providers = ServiceLoader.load(
                StoredPlayerItemClaimReader.class,
                StoredPlayerItemClaimReaderServiceTest.class.getClassLoader()
        ).stream().toList();

        assertEquals(1, providers.size());
        assertEquals(
                "io.github.kevinrabbe.minecraftserver.paper.PaperStoredPlayerItemClaimReader",
                providers.getFirst().type().getName()
        );
    }
}

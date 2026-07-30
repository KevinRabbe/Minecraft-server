package io.github.kevinrabbe.minecraftserver.common.item;

import java.util.List;

/** Decodes one versioned stored player-state payload into managed item representation claims. */
@FunctionalInterface
public interface StoredPlayerItemClaimReader {
    List<ItemRepresentationClaim> readClaims(byte[] statePayload);
}

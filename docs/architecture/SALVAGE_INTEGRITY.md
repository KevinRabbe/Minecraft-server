# Salvage Integrity Boundary

Status: **implemented and CI-qualified when the corresponding branch is green.**

Salvage is an irreversible multi-authority operation. The carried unique item is removed from serialized player state and destroyed in persistent item authority, while configured Coin and commodity returns are committed in the same operation.

`SalvageIntegrityVerifier` reconstructs that transaction read-only with bounded issue output.

It verifies that every immutable salvage record matches one exact `UNIQUE_ITEM_SALVAGE` processed request/result, including the historical session/backend, expected player/item versions, reason, and payload hash. The committed player-state result must be exactly one version after the expected state, while current session/player-state and wallet authority may be newer but cannot be behind the salvage result.

The verifier also checks the exact destroyed item identity, definition and version; its `DESTROYED` provenance from the player's inventory; the item debit, optional Coin credit and ordered commodity-credit ledger lines; the processed commodity-return list; each returned commodity's durable pending delivery; and the deterministic child source-operation identity assigned to each salvage commodity delivery.

Orphan `UNIQUE_ITEM_SALVAGE` processed results are reported rather than ignored.

Later legitimate activity does not invalidate historical salvage evidence. Player state and wallet state may advance, and a returned commodity delivery may later become `CLAIMED`. The generic commodity-delivery claim verifier remains responsible for that later consumption transition; salvage integrity proves the original issuance and accounting chain.

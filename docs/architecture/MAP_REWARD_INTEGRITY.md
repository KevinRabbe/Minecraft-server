# Map Reward Integrity Boundary

Status: **implemented and CI-qualified when the corresponding branch is green.**

The Map reward authority already separates three stages:

1. a completed `map_runs` row records one `reward_operation_id`;
2. `map_reward_settlements` freezes one resolver version and one or more participant-bound grants;
3. each grant is fulfilled through the ordinary commodity, unique-item, or Map pending-delivery authority.

`MapRewardIntegrityVerifier` reconstructs that chain read-only and with bounded output.

It verifies:

- a persisted reward settlement belongs to a `COMPLETED` Map run and uses the run's exact reward operation;
- every settlement contains at least one durable grant;
- grants belong to actual run participants, retain contiguous zero-based ordinals, share the settlement timestamp, and have the expected Map-profile shape;
- a fulfilled commodity grant resolves to the exact pending commodity delivery by stored operation, player, definition, and quantity;
- a fulfilled individualized-item grant resolves to the exact pending unique delivery and immutable item creation identity/reason;
- a fulfilled successor-Map grant additionally resolves to the exact immutable `map_item_profiles` definition snapshot;
- later legitimate delivery claim, trade, storage, quarantine, or other custody movement does not invalidate historical reward issuance because verification follows durable delivery/creation evidence rather than pinning current custody.

The generic commodity/unique delivery claim verifiers remain responsible for the later delivery-consumption transition. This verifier deliberately does not duplicate those claim checks.

A completed run that has not yet acquired `reward_operation_id`, or a still-`PENDING` reward grant waiting for fulfillment, is recoverable work rather than corruption and is not reported merely for being unfinished.

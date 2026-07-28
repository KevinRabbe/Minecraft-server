# Clan Chat

Status: **Canonical V1 architecture contract.** This document refines the `clan chat` launch function named by `CLANS_PVP_WAR.md` without changing clan membership authority.

## Goal

Clan chat is network-wide social messaging for current clan members on persistent-MMO Paper backends.

It is **not**:

- a second clan-membership authority;
- a persistent value/economy system;
- a permanent chat-history product;
- a client-selected destination where the sender can choose an arbitrary `ClanId`;
- a reason to introduce Redis or another service into V1 when PostgreSQL already provides the required shared boundary.

## Authority

The server derives the sender's clan from authoritative `clan_members` state at publish time.

A publish request contains:

- a server-generated `MessageId` for idempotency/replay safety;
- the authenticated `PlayerId` of the sender;
- the sender's current presentation name captured by the trusted Paper adapter;
- the bounded message body.

The sender does **not** provide `ClanId`.

Publishing fails if the player is not currently a clan member.

## Cross-backend transit

PostgreSQL holds a bounded, sequence-ordered transit log:

```text
clan_chat_messages
    sequence BIGINT identity
    message_id UUID unique
    clan_id UUID
    sender_player_id UUID
    sender_name
    body
    created_at
```

`sequence` is transport order/cursor state, not message identity. `message_id` is stable idempotency identity.

Every persistent-MMO Paper backend keeps one in-memory global sequence cursor:

1. on controller startup, initialize the cursor to the current maximum sequence so old transit rows are not replayed as history;
2. poll a bounded page after that cursor;
3. for each page row, PostgreSQL derives recipient Minecraft UUIDs from **current** `clan_members` plus **currently ACTIVE, unexpired** `player_sessions` owned by that backend;
4. deliver the message only to those locally online UUIDs;
5. advance through every scanned message even if that backend has no recipients for it.

This avoids per-clan polling, avoids a cursor getting stuck on messages for other clans/backends, and lets membership/session changes take effect without duplicating social authority in the Paper JVM.

## Delivery semantics

Clan chat is best-effort live social delivery:

- database publication is idempotent for the same `MessageId` and exact request;
- reusing a `MessageId` with a different sender/name/body is rejected;
- backend process restart intentionally does not replay old messages;
- a player who is offline, transferring, on another backend, or no longer a clan member is not a local recipient;
- duplicate visual delivery after an unusual local retry is acceptable only if transport uncertainty occurs before the backend advances its in-memory cursor; no value/state mutation depends on chat delivery;
- client disconnects do not create durable inbox obligations.

The chat command must never run database work on the Bukkit main thread.

## Bounds and retention

V1 bounds:

- message body: 1–256 Unicode characters after trimming;
- poll page: bounded by repository/API validation, maximum 500 messages;
- normal runtime poll uses a much smaller page (initial target 100);
- transit rows are retention-cleaned in bounded batches; V1 target retention is 24 hours;
- the Paper controller has at most one asynchronous poll in flight.

Retention exists to bound operational storage and support short transport delays/debugging. It does not promise player-visible chat history.

## Presentation

The Paper adapter may render a simple form such as:

```text
[Clan] SenderName: message
```

Formatting is presentation. It must not affect membership, routing, identity, or durable state.

## Failure behavior

- publish database failure: sender receives a failure message; no local fake broadcast;
- poll database failure: log and retry on the next bounded poll; do not disconnect players or mutate clan state;
- main-thread delivery after the player moved/offlined: skip that recipient;
- stale backend/session ownership: database recipient projection excludes it;
- retention cleanup failure: log and retry later; publication/delivery remains independent.

Clan chat failure must never block treasury/storage, player transfer, economy, or Clan-War settlement.

## Tests required before checkpoint

1. non-member publish fails;
2. member publish derives the correct clan without caller-supplied `ClanId`;
3. same `MessageId` + same request replays idempotently;
4. same `MessageId` + different request fails;
5. backend poll returns only current clan members with ACTIVE/unexpired sessions owned by that backend;
6. another backend receives its own recipients from the same message sequence;
7. removed members and expired/transferring/offline sessions are excluded;
8. polling advances across messages with zero recipients on that backend;
9. repository limits/body bounds are enforced;
10. bounded retention deletion removes only expired transit rows.

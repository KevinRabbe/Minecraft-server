# Master Roadmap

Status: **Canonical planning source.** Detailed implementation contracts live under `docs/architecture/`; this document defines dependency order, milestone intent, and acceptance boundaries.

## Development doctrine

- Build architecture and system correctness before content volume.
- Treat structural correctness as the dominant development cost; target roughly 95% systems/architecture and 5% tuning while the foundation is being built.
- Put balance values in data/configuration whenever practical. Exact damage, XP, rates, capacities, costs, reward multipliers, and progression curves must not block architecture work.
- Preserve settled decisions. Reopen a locked decision only when explicitly requested or when implementation exposes a direct contradiction.
- Build the smallest end-to-end slice that proves a system, then scale through data.
- Before Day 0, destructive resets and schema changes are acceptable in test environments. After Day 0, the canonical world is valuable persistent state.

## Architecture-first build order

Before feature milestones are treated as independent gameplay work, the repository must expose stable cross-cutting architecture for:

1. stable player and asset identity;
2. PostgreSQL durable authority and migrations;
3. single-writer player-state ownership and version fencing;
4. atomic/idempotent value movement, escrow, pending delivery, and append-only economic evidence;
5. commodity quantities versus unique-item identities;
6. data/config catalogs with strict startup validation;
7. zone/instance/backend abstractions and logical routing;
8. feature-access/world-era state;
9. generic progression/skill contracts with staged active caps;
10. generic PvE run/map/bounty contracts;
11. historical event/leaderboard records derived from authoritative outcomes;
12. verification, crash-recovery, concurrency, and adversarial test harnesses.

Existing architecture that already satisfies these requirements is retained rather than rewritten.

## Milestones

### A — Persistent authority foundation
Stable identity, player-state leases, versioned commits, transfer fencing, backend/zone registry, routing, migrations, recovery, and stale-write rejection.

**Proof:** reconnects, backend transfers, restarts, lease expiry, and stale writers cannot create conflicting persistent state.

### B — Value kernel
Commodity balances, unique-item identity/custody, Coin wallet authority, operation IDs, append-only ledgers, provenance, escrow, pending delivery, and invariant verification.

**Proof:** every valuable asset has exactly one authoritative location; retries and concurrent mutations cannot duplicate or lose value.

### C — Bazaar vertical slice
One fungible commodity first. Buy/sell orders, escrow, price-time matching, partial fills, cancel/fill races, fees, and settlement.

**Proof:** commodity and Coin conservation remain exact under concurrency, retries, disconnects, and crash injection.

### D — Auction House vertical slice
One individualized rolled item first. Fixed-price listing, escrow, purchase, cancellation, pending delivery, ownership transfer, and exact-item inspection.

**Proof:** one unique item can never have two owners or two successful buyers.

### E — Crafting and rolled gear
Recipes consume commodities and create fungible or individualized outputs. Rolled equipment stores persistent normalized quality while current absolute stats derive from versioned definitions.

**Proof:** one craft consumes inputs exactly once and creates exactly one output even across retries/crashes. Balance changes do not reroll historical items.

### F — Skill/progression framework
Generic XP/progression records, data-driven unlocks, meaningful-action XP sources, idempotent awards, and staged active caps.

Launch active cap: **50**. Later expansion: **75**. Much later progression era: **100**. XP does not accumulate invisibly beyond the active cap.

**Proof:** concurrency cannot lose/duplicate XP; cap transitions reopen progression without duplicating rewards.

### G — Starter-world vertical slice
Persistent City/starter region plus compact Wood/Mining/Farming/PvE activity spaces. Authorized resource generation connects live gameplay to persistent value.

**Proof:** gather -> persist -> transfer/trade -> reconnect survives instance replacement and restart.

### H — Portal instance kernel
Generic isolated PvE instance lifecycle: create, admit participants, start, complete/fail, reward once, close, clean up.

**Proof:** instance churn does not leak persistent authority or duplicate completion rewards.

### I — Map object system
Tradable unique Map items define difficulty, environment, enemy family, objective, modifiers, and deterministic generation data. Opening a Map consumes it exactly once and creates one run.

**Proof:** open/trade/AH races cannot duplicate a Map or create multiple runs.

### J — Difficulty progression
Difficulty is encounter strength, not a character-level requirement. Scaling is configurable and may define content beyond the currently achievable gear ceiling.

**Proof:** successful clears can generate nearby future Maps; balance curves can change without corrupting historical records.

### K — Map variety
V1 adds a small combinatorial pool of environments, enemy families, objectives, modifiers, and elite traits rather than large amounts of handcrafted disposable content.

Initial objective families: Extermination, Elite Hunt, Defense, Assault.

### L — PvE leaderboards
Server-authoritative solo/group clear records store difficulty, time, map configuration, participants, loadout context, balance version, and world era.

**Proof:** leaderboard state is derivable from immutable clear records; historical pre-power-jump records remain queryable.

### M — Bounty framework
Generic mob-category bounty families such as Spider, Zombie, and Golem. A player pays to unlock a bounty contract, completes category kill requirements, earns summon access, fights the boss, and receives category materials.

**Proof:** contract payment, kill progress, summon consumption, boss completion, and rewards are idempotent and crash-safe.

### N — Tiered bounty materials
Each family exposes material tiers, e.g. Web -> denser/higher-grade Web -> Enchanted Web -> rare high-tier components. Higher bounty tiers supply higher-grade inputs.

All bounty materials are Bazaar-tradable. Personal completion is not required merely to own/buy/craft with a material unless a separate use requirement explicitly exists.

### O — Bounty pouches
One dedicated pouch per bounty family stores that family's stackable materials. Capacity/QoL may improve with category progression. Pouch custody does not change market fungibility.

### P — Specialized gear
Equipment can specialize against bounty categories while general Map gear remains viable. Recipes cross-connect normal resources, district inputs, Map materials, and bounty materials.

Rolled low-to-high value spread is generally bounded around **10–30% depending on the item**. Near-perfect/perfect rolls are luxury optimization and can command extreme AH prices without being required for viability.

### Q — Upgrade and salvage
Upgrades preserve intrinsic roll quality and add invested progression separately. Salvage provides a controlled sink for unwanted individualized equipment.

### R — Clan core
Clan identity, membership, roles, permissions, treasury, shared storage, and auditability. Large clans can organize division of labor, but professions remain emergent rather than hard classes.

### S — World expansion voting
Players vote on which capability/theme becomes available next. The system guarantees valid voting and authoritative outcomes but does not steer which option should win.

Players are not given a canonical build blueprint for the physical district. The voted capability can become whatever players physically create around it.

### T — Feature/district integration
Winning world choices enable their configured resources, gear, skills, activities, or QoL. Most districts expand horizontally; selected milestones such as Nether and End can provide major vertical power jumps.

Difficulty levels themselves are not unlocked by Nether/End; stronger available gear raises the practical ceiling.

### U — Chronicle/history
Record actual world events: launch, major votes, feature unlocks, significant first clears, historical leaderboard eras, and other authoritative achievements. History describes what players actually did rather than authored lore pretending they did it.

### V — V1 content pass
Populate proven systems with a deliberately narrow launch set: roughly 25–30 meaningful equipment/items, a few bounty families, compact map content, starting skills/resources, initial expansion choices, recipes, and consumables.

### W — Economy simulation
Simulate large populations, crafting volume, Bazaar/AH trading, faucets/sinks, wealth concentration, specialization, and resource demand. Fix structural failures; tune values only enough to keep the loop plausible.

### X — Performance/scale
Stress zone routing, instance churn, entities, persistence, market matching, clan state, leaderboards, and concurrent player operations. Scale processes only from measured need.

### Y — Private alpha
Friends/family and trusted internal testers. Resets are disposable. Test normal usability, progression comprehension, and gross balance errors.

### Z — Adversarial closed beta
A small normal cohort plus dedicated breaker/red-team testers explicitly tasked with duplication, rollback, transaction, market, permission, persistence, and progression abuse. Selected creators may record under embargo; beta progress never becomes canonical Day-0 progress.

## Release candidate

Structural feature freeze. Only correctness fixes, exploit fixes, performance work, data/config tuning, and release packaging. A 10–20% tuning error is acceptable; known persistent-state corruption or economic duplication is not.

## Pre-launch campaign

Announce the canonical opening well in advance (the exact duration is a launch decision, with ~30 days as an example rather than a hard rule). Discord may already host clans, alliances, recruitment, specialization planning, strategy, and creator coverage.

No public preview world should undermine the canonical opening.

## Day 0 — canonical world opening

A public countdown ends and the persistent world begins. From that moment, world state, market history, votes, builds, records, and collective choices are canonical history.

## Post-launch development

1. Tune cheap values from real data without steering player choices.
2. Repair exploits/corruption/security/performance defects when necessary.
3. Add new items/content primarily inside the established V1 categories rather than inventing new progression systems.
4. Let expansion order emerge from player voting.
5. Raise the active skill cap to 75 with a later expansion and to 100 in a much later era.
6. Add Dungeons after Maps + Bounties are mature; Dungeons extend the existing resource/gear ecosystem rather than replacing it.

## Planning rule

A milestone may implement a documented mechanism or tune configuration. It must not silently create a new authority model, identity rule, transaction semantic, forced progression route, or developer-steered world outcome.
# System Overview

## Product architecture

The game is a persistent multiplayer layer built on Minecraft/Paper rather than a conventional open-ended survival server.

Players experience compact places, persistent specialization, player-driven markets, scalable PvE, clans/opt-in competition, player-directed world expansion, and durable server history. Minecraft provides the low-level interaction substrate; project code provides the persistent/network/game rules around it.

## Runtime planes

### Gameplay plane
Paper hosts live gameplay and one or more zone/encounter instances.

Examples:

- City/starter region
- Starter Woods/Mine/Farm instances
- ordinary PvE pockets
- Portal/Map run instances
- Bounty boss encounters
- ranked-PvP match instances
- clan-war instances

Moment-to-moment encounter entities/timers may be disposable. Valuable outcomes are promoted through persistent authority.

### Control plane
The smallest useful control plane tracks/routes runtime capacity:

- zone catalog
- backend registry
- instance registry/lifecycle
- zone router
- feature accessibility checks

V1 does not require a separate orchestration service. These responsibilities may live inside existing application modules until measured scale justifies separation.

### Persistent plane
PostgreSQL is durable authority for persistent network state and critical transactions:

- player identity/session ownership
- persistent inventory/equipment representation
- pocket Coin + protected Bank Manager state
- skills/progression and staged active caps
- unique item identity/provenance/roll quality
- crafting/economic evidence
- Bazaar/AH/trades/escrow/delivery
- Map item/run/clear source records
- Bounty contract/summon/reward progression
- clans/treasuries/shared storage/ratings/war settlement
- expansion votes/ballots
- feature states/world eras
- explicit community projects/history
- historical entitlements/Chronicle source records
- derived-data source records/watermarks

### Persistent world plane
Physical geography whose block state matters is stored/backed up as world data, principally the canonical City/player-built district/community-building space.

Physical geography is not the authority for market balances, vote counts, feature accessibility, Map/Bounty outcomes, or hidden ordinary-district completion thresholds.

## Core responsibility rule

**PostgreSQL remembers. Velocity decides where you go. Paper decides what happens while you are there.**

This is a responsibility summary, not permission for Paper to become durable authority for critical persistent state.

## Player-facing topology

Players interact with logical places/activities such as `Starter Woods`, `Mine`, `City`, `Map Portal`, `Arena`, or an unlocked district/capability.

They do not choose infrastructure IDs such as `paper-03` or `woods-07`.

Routing resolves:

`requested logical activity/zone -> suitable live instance -> backend hosting that instance`

## Scaling model

Gameplay zones are intentionally compact. Capacity scales horizontally by running additional equivalent instances only when that specific activity needs concurrency.

Portal/Map and match-style encounters are especially suitable for on-demand temporary instances. Bounty bosses may use dedicated encounter contexts or bounded live-zone encounters according to the final content implementation, but their persistent contract/result authority remains the same.

Total network population never directly determines the number of Mine/Woods/Map/etc. instances.

## Player-directed world progression

Global feature accessibility is persistent state separate from runtime infrastructure.

Players vote on which valid capability/theme becomes available next. Vote/feature state is PostgreSQL-authoritative.

Ordinary district physical form is player-created and has no developer-authored canonical blueprint/minimum block count.

Most ordinary expansions broaden QoL/specialization/resources/production/sidegrades. Selected milestones such as Nether/End may add major vertical gear power and therefore raise the practical Map difficulty ceiling.

## PvE backbone

### Maps
Individualized tradable Map items define scalable encounters from difficulty + configured environment/enemy/objective/modifier/generation properties.

Map difficulty measures encounter power, not access permission. The system may define difficulty beyond current achievable gear.

### Bounties
Mob-category families (e.g. Spider/Zombie/Golem) convert paid contracts + eligible category kills into boss attempts and tiered fungible family materials.

Bounty materials remain Bazaar-tradable; category pouches/specialized gear deepen specialization without forcing personal completion of every branch.

## Economy backbone

- pocket/spendable Coins may carry ordinary PvE death risk;
- Bank Manager protects deposited Coins and may provide configured small interest/capacity progression;
- Bazaar handles fungible commodities;
- Auction House handles individualized rolled equipment/Maps;
- crafting converts cross-system materials into useful/rolled gear;
- perfect rolls are luxury optimization, not required progression.

## Competitive/social backbone

- clans organize emergent division of labor using treasury/shared storage/roles;
- ranked 1v1 PvP uses standardized temporary state;
- clan war uses explicit economic custody/settlement;
- competitive/social prestige does not grant mandatory power;
- clan influence over world direction occurs through ordinary player campaigning/voting, not hidden ballot weights.

## Initial deployment

Development and first playtests run on the user's Windows PC.

The architecture supports multiple Paper backends but does not require them immediately. One backend may host several zone instances. Additional processes/machines are added only for measured load, isolation, restart, or operational reasons.

## Architecture references

Key detailed contracts:

- `AUTHORITY_MODEL.md`
- `TRANSACTIONS_AND_ANTI_DUPE.md`
- `ITEMS_AND_INVENTORY.md`
- `ECONOMY.md`
- `SKILLS_GATHERING_AND_MODIFIERS.md`
- `PVE_MAPS_AND_BOUNTIES.md`
- `CLANS_PVP_WAR.md`
- `WORLD_VOTING_AND_HISTORY.md`
- `COMMUNITY_PROJECTS.md`
- `DATA_MODEL.md`
- `CONFIGURATION.md`
- `FAILURE_RECOVERY.md`

## Non-goals

Do not introduce these without an observed requirement:

- Kubernetes
- service mesh
- Kafka/message-broker architecture
- Redis cluster
- per-system microservices
- one Paper process per gameplay area by default
- massive open-world terrain as a capacity mechanism
- a new authority model for each future content family
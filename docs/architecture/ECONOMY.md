# Economy

## Core rule

**Players determine item value. The server does not.**

The server provides market infrastructure, controlled faucets/sinks/bootstrap supply, conversion/recipe rules, and auditability. It does not maintain hidden target prices.

## Currency

Coins are stored with integer/fixed-point authority. Never use floating point for authoritative money.

The currency system distinguishes spendable/pocket money from protected bank custody.

## Pocket / spendable balance

Pocket/spendable Coins are immediately usable for purchases, transfers, contracts, crafting fees, and other configured operations.

Ordinary PvE death may destroy a configurable portion of pocket money. The death-loss mechanism is an explicit Coin sink and must be atomic/idempotent with the authoritative death outcome.

Exact loss percentages/curves are balance configuration.

## Bank Manager

The Bank Manager provides protected Coin custody.

Locked semantics:

- deposited Coins are protected from ordinary PvE pocket-loss rules;
- bank deposits/withdrawals are authoritative transactions rather than client-side counters;
- bank progression may increase protected capacity;
- bank progression may provide small daily interest;
- interest is an explicit measurable Coin faucet;
- capacity, upgrade costs, and interest rates are configuration, not architecture;
- a current top-end example around 0.3% daily is a tuning example only, not a locked rate.

Interest credit must have a stable eligibility period/key so retries, reconnects, or multiple backends cannot credit the same period twice.

### Bank integrity/recovery evidence

The Bank Manager keeps mutable current state, but recovery/integrity must be able to explain that state from durable evidence rather than trusting the row blindly.

The bounded Bank integrity pass therefore verifies:

- every processed deposit, withdrawal, tier upgrade, and interest result has the expected frozen result shape;
- Bank `state_version` advances exactly once for every committed Bank operation and the per-player history remains contiguous;
- current protected balance matches the latest Bank operation result;
- current tier matches the latest committed tier upgrade, or tier 0 when no upgrade exists;
- current last-interest period matches the latest committed interest operation;
- deposit/withdraw, upgrade-cost, and interest operations retain the exact append-only Coin ledger evidence required by their frozen results;
- with the loaded tier catalog, current tier must still exist and current protected balance must not exceed that tier's configured capacity.

This does not freeze today's tier capacities, costs, or interest rates into architecture. Those remain versioned/tunable content. The verifier reconciles persisted state to the loaded catalog and immutable operation history; it does not invent economic targets.

## Faucets and sinks

Every source/destruction path should have an explicit reason/category so server-wide monetary flow can be measured.

Examples of faucets:

- configured gameplay rewards;
- configured NPC sales where the server intentionally buys value;
- bank interest.

Examples of sinks:

- PvE pocket-money death loss;
- Bank Manager upgrades;
- bounty contract fees;
- Bazaar/Auction/direct-trade fees where configured;
- crafting/upgrade/salvage/service fees where configured.

Player-to-player trading moves Coins; it does not create them.

## Bazaar

Default market for fungible/stackable commodities.

Examples include:

- ores/logs/crops/refined resources;
- ordinary consumable ingredients;
- Map materials;
- every configured bounty-family material tier;
- Fishing/district resources once those features exist.

Supports:

- place buy order;
- place sell order;
- instant buy against cheapest sells through the same matching engine;
- instant sell against highest buys through the same matching engine;
- partial fills;
- offline fill/settlement;
- cancellation;
- explicit fees/sinks;
- spread/volume/history read models.

Matching is deterministic price-time priority.

Every order escrows the value required to fulfill its remaining quantity. A player cannot simultaneously spend/sell the same reserved Coin/commodity elsewhere.

Bounty materials remain fully Bazaar-tradable. The market connects specialists to players who do not personally farm that bounty family.

### Bazaar integrity/recovery evidence

Bazaar order rows are mutable operational state while fills and processed-operation results are durable evidence. Recovery must be able to detect a restored order book whose current escrow/remainder no longer agrees with that evidence.

The bounded Bazaar integrity pass therefore verifies:

- every BUY/SELL order retains its matching creation operation and frozen identity/initial-order evidence;
- BUY creation retains the exact initial Coin escrow ledger debit while SELL creation carries no Coin escrow ledger entry;
- every immutable fill remains within the referenced BUY/SELL limits and has exactly one matching buyer commodity delivery using the fill's `fill_operation_id`;
- current order remaining quantity is reconstructed from original quantity minus immutable fill quantities;
- open BUY reserve equals the remaining maximum notional, open SELL reserve stays zero, and FILLED/CANCELLED state agrees with immutable fill history;
- cancelled BUY orders return the exact remaining Coin escrow with matching ledger evidence;
- cancelled SELL orders create the exact remaining commodity delivery to the seller;
- each `BAZAAR_MATCH` processed result has a valid bounded aggregate shape, including fill-count, quantity, gross value and destroyed-fee relationships.

The current schema intentionally does **not** persist a direct per-fill -> `BAZAAR_MATCH` operation relationship: each fill's `fill_operation_id` identifies buyer commodity delivery, while one enclosing match pass records aggregate match evidence under a different operation ID. Integrity verification therefore does not invent that missing relationship. A future schema may add an explicit link if per-match reconstruction becomes necessary.

## Auction House

Default market for individualized/non-fungible items.

Examples:

- rolled weapons/armor/equipment;
- artifacts/active-use items where individualized;
- provenance-bearing unique items;
- individualized tradable Map items.

V1 begins with fixed-price Buy-It-Now listings. Bidding is deferred until it provides enough value to justify additional lifecycle complexity.

Unique items remain in authoritative Auction custody while listed. One item instance may have at most one authoritative listing/custody location.

Finished gear is not soulbound by default.

### Auction integrity/recovery evidence

PostgreSQL already enforces the live Auction custody and transition shape: an ACTIVE listing owns the exact item escrow, and a SOLD/CANCELLED listing can release that item only through its matching pending delivery. Restore-time integrity additionally has to prove that the immutable historical evidence behind those transitions survived coherently.

The bounded Auction integrity pass therefore verifies:

- every listing retains the exact `AUCTION_LISTING_CREATE` processed result for seller, item identity/definition, escrow item version and price;
- listing creation retains the exact item-provenance hop from seller inventory to that listing's Auction escrow and the matching one-item seller ledger debit;
- a SOLD listing retains its `AUCTION_LISTING_PURCHASE` result, buyer pending delivery, escrow-to-delivery provenance hop, exact buyer Coin debit, seller Coin credit and buyer item credit;
- a CANCELLED listing retains its `AUCTION_LISTING_CANCEL` result, seller pending delivery, escrow-to-delivery provenance hop and exact seller item credit;
- ACTIVE listings remain terminal-field free, while terminal processed results/deliveries bind back to the exact listing and item authority version.

These checks are historical. Once a settlement delivery is legitimately claimed, traded, listed again, moved into clan storage or otherwise changes current custody, the original Auction settlement remains valid because the verifier checks its immutable creation/settlement evidence rather than requiring the item to remain in that old delivery location.

## Secure direct trade

Both sides can offer commodities, individualized items, and/or Coins. Offer changes invalidate confirmation. Final settlement is atomic and does not depend on trust.

### Secure Trade integrity/recovery evidence

Trade creation intentionally uses `secure_trades.create_operation_id` as the durable idempotency identity and does **not** create a parallel `processed_operations` record. Integrity verification must not invent a processed-create requirement that the authority never writes.

Terminal SETTLED/CANCELLED state is reconstructable because the offered Coin, commodity and individualized-item escrow rows remain as frozen evidence while terminal resolution writes a processed result, append-only trade-delivery evidence and exact settlement ledgers.

The bounded Secure Trade integrity pass therefore verifies:

- every SETTLED trade retains its exact `SECURE_TRADE_SETTLE` processed result and every CANCELLED trade retains its exact `SECURE_TRADE_CANCEL` result;
- the frozen terminal result agrees with trade identity, participants, status, revision/confirmation state and recorded delivery set; cancellation evidence must name one of the two participants as the cancelling player;
- every frozen commodity escrow produces exactly one matching trade-delivery row plus durable pending commodity issuance, delivered to the opposite participant on settlement or returned to the original owner on cancellation;
- every frozen individualized-item escrow produces exactly one matching trade-delivery row, pending unique-item issuance and `TRADE_ESCROW -> PENDING_DELIVERY` provenance hop at the next item state version, with the terminal reason preserved;
- every frozen Coin, commodity and individualized-item escrow produces exactly one terminal CREDIT ledger line to the opposite participant on settlement or the original owner on cancellation, with no extra terminal ledger lines.

These are historical resolution checks. A later legitimate claim or movement may change a delivered item's current custody, and later economic activity may change participant wallet balances; neither invalidates the original trade as long as the frozen terminal operation, escrow, delivery, provenance and ledger evidence still reconciles.

## Crafting economy

Crafting converts fungible inputs into fungible or individualized outputs through authoritative exactly-once resource consumption.

Rolled individualized gear creates an economic role for dedicated crafters:

- buy/obtain inputs;
- craft in volume;
- sell ordinary/good rolls;
- retain/sell rare excellent/perfect rolls;
- reinvest proceeds into more production.

The value of perfect rolls is discovered by the Auction House. The server does not prescribe a target price.

Crafting progression should create value through recipe access, throughput, modest efficiency, and volume rather than an overwhelming perfect-roll probability monopoly.

## Bounty economy

Bounty access deliberately consumes Coins and player effort:

`pay contract fee -> complete family mob requirement -> summon access -> boss attempt -> family materials`

The fee unlocks the contract/quest; it does not directly purchase a boss spawn.

Higher bounty tiers may generate higher-grade family materials. Those materials remain fungible Bazaar supply and can feed specialized gear recipes.

This creates a structural sink/faucet loop:

- Coin contract fee is destroyed;
- player time/combat effort is consumed;
- bounty materials enter the commodity economy;
- crafters/players consume those materials in equipment/upgrade recipes.

## Map economy

Individualized Map items can themselves be traded because different combinations may suit different builds/farming goals.

Map completion may create:

- fungible Map materials;
- future nearby-difficulty Map items;
- configured equipment/resource rewards.

Map failure consumes the opened Map according to the Map lifecycle; it does not silently refund the economic attempt.

## NPC roles

Keep NPC economic roles narrow.

### Service NPC
Interface/infrastructure players cannot replace (for example Bazaar/AH/Bank Manager/clan registration UI).

### Bootstrap supplier
Expensive guaranteed source needed before natural/player supply exists. Example: Witch/Apothecary before Nether unlock.

### Salvage buyer/system
Deliberately poor guaranteed item exit/resource sink with a small controlled Coin/material return where configured.

NPC shops should not become the primary long-term market.

## Witch/Apothecary bootstrap economics

Before Nether unlock, the Witch may sell expensive basic Nether-derived inputs needed to keep brewing/enchanting functional, such as configured amounts of:

- Nether Wart;
- Blaze Powder;
- Glowstone Dust;
- other basic potion inputs;
- baseline guaranteed enchant books.

After natural Nether supply opens, player supply should be able to undercut the Witch. The expensive guaranteed fallback may remain.

A small authorized Nether Wart garden/source may also exist in the starter region with limited throughput and legitimate Farming interaction.

## XP bottle economy

XP bottles are a tradable/storable acquisition route to normal Minecraft XP, not a separate enchanting currency.

A recipe/conversion such as Lapis + bottle -> Experience Bottle may be used; exact ratio is configuration.

Combat and non-combat routes can both lead to normal XP without making one mandatory.

## Compression

Compression is quantity density, not industrial automation.

- explicit player action unless future convenience evidence justifies automation;
- reversible where configured;
- no XP from reversible conversion;
- no yield bonus;
- each compression tier is its own commodity/order book;
- no automatic cross-tier price matching/arbitrage.

Add further tiers only when real quantities require them.

## Refining/crafting commissions

A simple labor market may allow a requester to escrow materials + payment for a qualified player to perform a job.

No giant bidding/contract platform is required for V1. The important architecture is secure escrow and exactly-once settlement.

## Market read models

Order book/listing state is authoritative in PostgreSQL. Cached views/history may be derived for UI/performance.

## Economy observability

Track enough structured events/ledger information to answer:

- what creates/removes Coins;
- Coin location split between pocket/bank/escrow;
- interest credited and death loss destroyed;
- what creates/removes important commodities;
- Bazaar/AH volume/spread/liquidity;
- bounty contract sink/material supply;
- Map material/Map-item supply;
- crafting/salvage volume;
- bootstrap supplier usage;
- suspicious supply discontinuities;
- activity by skill/system.

## Anti-arbitrage rule

Configured NPC prices, bank rates, recipes, compression, salvage, and conversion rules must be validated so the server does not unintentionally provide a deterministic infinite money/resource loop.

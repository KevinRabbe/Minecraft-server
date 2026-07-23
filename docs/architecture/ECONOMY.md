# Economy

## Core rule

**Players determine item value. The server does not.**

The server provides market infrastructure, controlled NPC sinks/bootstrap supply, conversion/recipe rules, and auditability. It does not maintain hidden target prices.

## Currency

Coins are displayed with decimals but stored as fixed-point integers.

Example implementation convention: `1 Coin = 100 internal units`.

Never use floating point for authoritative money.

## Bazaar

Default market for Minecraft-stackable items.

Supports:

- place buy order
- place sell order
- instant buy against cheapest sells
- instant sell against highest buys
- offline fill/settlement
- order cancellation
- spread/volume/history read models

Matching is deterministic price-time priority.

Every order escrows the value required to fulfill its remaining quantity.

## Auction House

Default market for Minecraft-non-stackable items.

V1 can begin with fixed-price Buy-It-Now listings. Bidding is deferred until it provides enough value to justify additional lifecycle complexity.

Unique/non-fungible items remain in authoritative Auction custody while listed.

## Secure direct trade

Both sides can offer items and/or Coins. Offer changes invalidate confirmation. Final settlement is atomic and does not depend on trust.

## NPC roles

Keep NPC economic roles narrow.

### Service NPC
Interface/infrastructure players cannot replace (for example Bazaar/AH/clan registration UI).

### Bootstrap supplier
Expensive guaranteed source needed before natural/player supply exists. Example: Witch/Apothecary before Nether unlock.

### Salvage buyer
Deliberately poor guaranteed item exit/resource sink with a small controlled Coin faucet.

NPC shops should not become the primary long-term market.

## Witch/Apothecary bootstrap economics

Before Nether unlock, the Witch may sell expensive basic Nether-derived inputs needed to keep brewing/enchanting functional, such as configured amounts of:

- Nether Wart
- Blaze Powder
- Glowstone Dust
- other basic potion inputs
- baseline guaranteed enchant books

After natural Nether supply opens, player supply should be able to undercut the Witch. The expensive guaranteed fallback may remain.

A small authorized Nether Wart garden/source may also exist in the starter region with limited throughput and legitimate Farming interaction.

## XP bottle economy

XP bottles are a tradable/storable acquisition route to normal Minecraft XP, not a separate enchanting currency.

A recipe/conversion such as Lapis + bottle -> Experience Bottle may be used; exact ratio is configuration.

Combat and non-combat routes can both lead to normal XP without making one mandatory.

## Compression

Compression is quantity density, not industrial automation.

- explicit player action (unless future convenience artifact is proven necessary)
- reversible where configured
- no XP from reversible conversion
- no yield bonus
- each compression tier is its own item/commodity/order book
- no automatic cross-tier price matching/arbitrage

Add further tiers only when real quantities require them.

## Refining/crafting commissions

A simple labor market may allow a requester to escrow materials + payment for a qualified player to perform a job.

No giant bidding/contract platform is required for V1. The important architecture is secure escrow and exactly-once settlement.

## Market read models

Order book state is authoritative in PostgreSQL. Cached views/history may be derived for UI/performance.

## Economy observability

Track enough structured events/ledger information to answer:

- what creates/removes Coins
- what creates/removes important commodities
- market volume/spread/liquidity
- salvage volume
- bootstrap supplier usage
- suspicious supply discontinuities
- activity by skill/system

## Anti-arbitrage rule

Configured NPC prices, recipes, compression, and conversion rules must be validated so the server does not unintentionally provide a deterministic infinite money/resource loop.

# Economy

## Currency

Coins support two decimal places and are stored as fixed-point integers.

Example:

- `1.00 Coin = 100 internal units`
- never use binary floating point for balances or settlement

## Price discovery

The server does not assign a target market value to items. Players determine prices through supply, demand, liquidity, and arbitrage.

The server may change broken mechanics (for example an exploit or bad drop rate) but should not directly manipulate player market prices merely because a price looks undesirable.

## NPC salvage

NPC salvage is the worst guaranteed way to dispose of eligible excess commodities.

Each item/family may define:

- a low reference/base salvage value
- a return percentage

The result is a deliberately poor guaranteed payout. It creates both a resource sink and a controlled Coin faucet.

NPC salvage must be benchmarked by expected acquisition effort so one easy resource does not become the dominant NPC money printer.

Compression must not create an NPC arbitrage profit; compressed forms inherit economically equivalent salvage behavior from their underlying units.

## Bazaar

Default routing rule: **Minecraft stackable -> Bazaar**.

Required capabilities:

- place buy order
- instant buy from cheapest sell orders
- place sell order
- instant sell into highest buy orders
- deterministic price/time priority matching
- escrow/reservation
- offline settlement/claim
- volume and price history sufficient for player decision-making

Every compression tier/item is an independent order book. The Bazaar never silently buys a lower tier and compresses/decompresses it for the player.

Player arbitrage is allowed and desirable.

## Auction House

Default routing rule: **Minecraft non-stackable -> Auction House**.

V1 should begin with the simplest useful listing model, preferably fixed-price Buy-It-Now. Full bidding/auction mechanics can be added later if real usage justifies them.

AH escrow must make listing/purchase/cancellation atomic and crash-safe.

Typical AH items include tools, weapons, armor, enchanted books, artifacts, amulets, historical collectibles, and other non-stackable individual objects.

## Direct trade

Secure direct trade supports items and Coins. Both sides see final contents and explicitly confirm. Settlement is atomic; no trust is required.

## Economic sinks/faucets

Potential faucets:

- NPC salvage
- any explicitly designed system reward

Potential sinks:

- NPC-sold baseline enchantments
- market/listing fees if needed
- clan/war/service costs if needed
- resource consumption, durability, brewing, crafting, community projects

Do not add sinks/faucets merely to hit a theoretical number. Measure inflation, velocity, market liquidity, and actual player behavior first.

## Invariants

- balances never become negative
- escrow cannot settle twice
- items cannot be sold twice
- unique item instances cannot exist in two valid owners simultaneously
- order cancellation and fill races resolve exactly once

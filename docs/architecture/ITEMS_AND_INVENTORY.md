# Items and Inventory

## Item classes

V1 uses two persistence models.

### Stackable commodities
Fungible values represented as:

- stable `definition_id`
- quantity
- authoritative owner/location

Examples: ores, logs/materials, refined resources, stackable consumables, XP bottles, Map materials, bounty-family materials.

Do not create one database identity per unit.

### Individual/non-fungible items
Items whose individuality matters receive stable `item_instance_id` values where required.

Examples: rolled equipment, artifacts, provenance-bearing unique items, individualized Maps, historical collectibles, other stateful unique objects.

## Item definition

Type-level content data includes as needed:

- stable `definition_id`
- Minecraft material/model reference
- display metadata
- stackability/max stack
- category/subcategory
- base stats
- use requirements
- crafting requirements
- recipe references
- roll profile/ranges for individualized equipment
- valid enchantment behavior
- resource-family/pouch eligibility

Names/lore/models are presentation, not identity.

## Item instance

Instance-level state may include:

- `item_instance_id`
- `definition_id`
- created timestamp/source
- creator/original owner where relevant
- persistent normalized rolled properties/quality
- upgrade state
- enchantments
- durability/other mutable state where authoritative persistence needs it
- Map challenge properties where the item is a Map
- provenance/historical metadata

The definition describes what an item is. The instance describes which exact item it is.

## Stable categories

Top-level categories remain small:

- Materials
- Equipment
- Usables
- Progression
- Historical

Future content should usually add definitions inside established categories rather than create new foundational systems.

## V1 content-surface rule

The launch target is roughly **25–30 meaningful equipment/items across the established categories**, not hundreds of filler definitions.

The initial equipment pool should contain a small number of mechanically distinct weapons, combat/utility wearables, artifacts, active-use equipment, consumables, and gathering/logistics/QoL items.

## Minecraft-native behavior

Use Minecraft item/render/inventory behavior where suitable. A definition may map directly to a vanilla material.

Custom code adds only the network/persistent semantics Minecraft does not provide.

## Ownership versus use

Ownership/trading/storage is unrestricted by skill by default.

Use/equip may be skill/content-gated.

Crafting requirement is separate from use requirement. A specialist may craft an item they cannot personally use. Bounty-family materials may be bought and used economically without personally completing that bounty branch unless an explicit recipe/use rule says otherwise.

Soulbinding is not a default V1 mechanism. Introduce it only with a separately documented system reason.

## Equipment/stat model

Keep the stat surface small. Candidate core families include:

- gathering speed
- gathering luck/yield where meaningful
- damage
- armor/defense
- health where needed
- attack speed only if it adds real gameplay
- category-specific defensive/offensive utility
- mobility/QoL effects

Do not persist effective/calculated stats. Persist their authoritative sources and derive the result deterministically.

## Rolled individualized gear

Individualized equipment may roll one or more configured properties at creation.

### Persistent representation

Store the item's **normalized intrinsic roll quality** (for example 0..1 per rolled property or another deterministic normalized representation), not merely a one-time absolute number that becomes meaningless after rebalance.

Current absolute stat values derive from:

`current item definition/balance version + persistent roll quality + upgrade state + other valid modifiers`

A balance update may change the definition/range but must not reroll the historical item.

### Bounded variance

The intended low-to-high relevant item value spread is generally about **10–30% depending on the item**. Exact ranges/distributions are content/balance data.

Most rolls should remain usable. Near-perfect/perfect rolls are optional luxury optimization and may become extremely valuable on the Auction House without being required for basic progression.

### Roll versus upgrade

Intrinsic roll quality and later upgrade investment are separate state:

- roll quality answers "how good was this item when created?";
- upgrade state answers "how much has been invested into this exact item?".

Upgrading must not silently reroll intrinsic quality.

### Crafting-skill boundary

Crafting skill may affect recipe access, throughput, batch convenience, modest efficiency, or configured costs. It should not create an overwhelming server-wide perfect-roll monopoly unless explicitly redesigned later.

## Modifier pipeline

Conceptual order:

`base item/action -> intrinsic roll -> upgrade state -> player skill -> equipment set/context -> enchantment -> temporary effect -> effective result`

Exact mathematics may vary by stat but must be centralized/deterministic rather than scattered across event handlers.

## Enchantments

Vanilla and custom enchantments may share one logical definition/validation layer. V1 should keep the custom catalog small and retain Minecraft enchanting/anvil behavior where it already works.

## Map items

A tradable Map is an individualized item whose instance carries the authoritative challenge definition needed to create at most one run, such as:

- numeric difficulty
- environment ID
- enemy-family ID
- objective ID
- modifier IDs
- deterministic generation/seed data
- map-generation/balance version context where needed

The Map item is persistent economic state. The live Map run/instance is disposable runtime state.

Opening a Map must consume/move the exact item atomically with run creation so open/trade/AH races cannot duplicate the challenge.

## Minecraft ItemStack representation

A rendered ItemStack may contain compact internal metadata such as:

- `definition_id`
- `item_instance_id` when applicable
- metadata/schema version

Do not store the entire authoritative database record inside ItemStack metadata.

For high-value individual items, authenticity comes from persistent identity/ownership, not display name/lore.

## Inventory authority

Minecraft inventory is the active gameplay representation of network-owned persistent state while one backend owns the session.

Important boundaries (login, transfer, market listing, secure trade, Map opening, recovery) validate authoritative state. Do not query PostgreSQL for every routine inventory click if the loaded single-writer state is already valid.

## Pouches

Pouches exist to remove category-specific inventory friction, not to become generic backpacks.

### Gathering pouches

Examples:

- Mining
- Woodcutting
- Farming
- Fishing when that feature exists

### Bounty-family pouches

Examples:

- Spider Pouch
- Zombie Pouch
- Golem Pouch

A bounty pouch accepts only fungible materials from its configured family. Capacity/QoL may improve with that family progression.

Common pouch rules:

- family allowlist is explicit;
- persistent capacity/state;
- eligible authorized drops may route directly into the pouch;
- selling/transferring contents uses ordinary authoritative commodity accounting;
- pouch custody does not soulbind or remove Bazaar tradability.

## Drops and disposable zones

Ordinary low-value ground drops may remain instance-local and can disappear with a disposable instance according to normal rules.

High-value/unique/historical items must not rely on disposable ground state for correctness. Exact drop restrictions/handling remain an explicit content decision.

## Invalid/conflicting item representation

Examples:

- unknown definition
- malformed metadata
- missing required instance ID
- instance ID owned elsewhere
- duplicate live representations of one unique instance
- Map instance metadata inconsistent with authoritative challenge data
- rolled-item representation inconsistent with authoritative normalized roll state

Do not guess-repair suspicious valuable items. Reject, rebuild from authority, or quarantine them and emit an audit signal.
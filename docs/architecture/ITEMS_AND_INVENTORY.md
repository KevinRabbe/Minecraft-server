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

The implemented skill-use requirement foundation is definition-owned content data:

- one item definition may declare zero or more stable `skill_id -> minimum level` requirements;
- zero configured requirements means unrestricted use and requires no progression lookup;
- minimum levels use the same long-term 1..100 skill domain and the active 50 -> 75 -> 100 cap still applies normally;
- missing player skill rows project as level 0 rather than being created merely by an eligibility check;
- all configured skill requirements are conjunctive: every requirement must be met;
- bundled item/skill content is cross-validated so shipped item requirements reference known skills;
- bounded multi-skill reads load the relevant authoritative levels in one query rather than one PostgreSQL lookup per requirement;
- the eligibility result exposes the exact unmet requirements so adapters can explain why use is denied without changing custody.

Use/equip enforcement belongs at the relevant gameplay action boundary. It must not be implemented by blocking Auction House listing, Bazaar/material acquisition, storage, direct trade, salvage, or ordinary ownership. A newly configured requirement also must not become a hidden mandatory progression route for unrelated content.

The current bundled item definitions remain unrestricted unless a requirement is explicitly added as content. Do not infer requirements from item material, name, category, crafting recipe, creator skill, or provenance.

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

The current implementation stores normalized roll quality in bounded basis points and resolves the current intrinsic multiplier from the item definition's roll profile. Craft recipes may not redefine the output item's intrinsic roll semantics.

### Bounded variance

The intended low-to-high relevant item value spread is generally about **10–30% depending on the item**. Exact ranges/distributions are content/balance data.

Most rolls should remain usable. Near-perfect/perfect rolls are optional luxury optimization and may become extremely valuable on the Auction House without being required for basic progression.

### Roll versus upgrade

Intrinsic roll quality and later upgrade investment are separate state:

- roll quality answers "how good was this item when created?";
- upgrade state answers "how much has been invested into this exact item?".

Upgrading must not silently reroll intrinsic quality.

### Upgrade authority

Upgrade economics/progression remain tuning/content decisions, but the persistent mutation contract is fixed:

- one committed upgrade step advances `upgrade_level` exactly once;
- the exact item `state_version` advances exactly once with it;
- upgrade does not change item custody;
- normalized intrinsic roll state is preserved;
- `item_upgrade_events` is append-only upgrade evidence;
- matching `UPGRADED` item provenance preserves the item authority chain;
- replaying one operation ID returns the same result and cannot rebind the operation to another item/request;
- concurrent attempts from one stale item head cannot both commit;
- a failed/uncommitted upgrade attempt leaves the item intact rather than destroying or silently degrading it.

A carried-item upgrade has an additional single-writer requirement: the serialized ItemStack representation contains the item authority version, so the new player-state payload and upgraded item authority head must commit in the same PostgreSQL transaction under the owning live session. The Paper adapter deterministically reconstructs the expected next inventory payload by changing only that exact item's authority-version claim; any unrelated inventory/metadata mutation fails closed.

The exact upgrade cost curve, progression, maximum gameplay investment, and whether success is deterministic remain explicit balance/content decisions. If a later failure mechanic exists, the item itself remains intact; cost can carry the risk instead.

### Runtime inspection/presentation

Validated unique-item state is loaded in bounded batches at authority boundaries, not queried on every hit or inventory click. Paper may keep disposable local snapshots keyed by stable `item_instance_id` plus exact authority version.

For rolled equipment, derived presentation may show:

- exact normalized roll quality;
- the current intrinsic multiplier resolved from the active item definition;
- upgrade level as separate investment state.

Auction House browse exposes roll quality and upgrade investment before purchase. Lore/PDC presentation never becomes the authority for those values.

### Live intrinsic damage materialization

For the current `damage` roll property, Paper derives the gameplay attribute from trusted runtime authority rather than editing final damage events:

- startup requires the mapped Minecraft material to expose exactly one main-hand-only `ATTACK_DAMAGE` `ADD_NUMBER` modifier and rejects any additional main-hand damage shape that would make the transformation ambiguous;
- the live ItemStack's existing attribute component is never trusted as an input; materialization starts from the material's default attribute component every time;
- the intrinsic multiplier applies to the vanilla player's complete pre-skill weapon base (`player base attack damage + vanilla item contribution`), then converts that target back to the item's additive attack-damage contribution;
- attack speed and every unrelated vanilla attribute entry are copied unchanged;
- player skill, upgrade power, enchantments, set/context bonuses, and temporary effects remain later pipeline stages and are not multiplied by the intrinsic roll;
- reconnect/join installs authority-validated runtime snapshots and must materialize the exact derived attributes before gameplay continues;
- a newly delivered individualized item is first rendered at the definition's minimum possible damage multiplier because the renderer does not own the exact roll; after the generation-fenced authority refresh, the exact multiplier replaces that conservative projection;
- a materialization/shape failure clears local runtime trust and fails closed rather than silently using arbitrary or vanilla damage.

This derived attribute is disposable presentation/runtime state. Persistent truth remains the item definition, normalized intrinsic roll state, item authority version, custody and later modifier-source state.

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
- `authority_version` for version-fenced individualized representations
- metadata/schema version

Do not store the entire authoritative database record inside ItemStack metadata.

For high-value individual items, authenticity comes from persistent identity/ownership, not display name/lore.

## Inventory authority

Minecraft inventory is the active gameplay representation of network-owned persistent state while one backend owns the session.

Important boundaries (login, transfer, market listing, secure trade, Map opening, carried-item upgrading, recovery) validate authoritative state. Do not query PostgreSQL for every routine inventory click if the loaded single-writer state is already valid.

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

A bounty pouch accepts only the fungible materials for its configured family. Capacity/QoL may improve with that family progression.

Common pouch rules:

- family allowlist is explicit;

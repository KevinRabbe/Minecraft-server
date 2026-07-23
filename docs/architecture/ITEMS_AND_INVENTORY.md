# Items and Inventory

## Item classes

V1 uses two persistence models.

### Stackable commodities
Fungible values represented as:

- stable `definition_id`
- quantity
- authoritative owner/location

Examples: ores, logs/materials, refined resources, stackable consumables, XP bottles.

Do not create one database identity per unit.

### Individual/non-fungible items
Items whose individuality matters may receive stable `item_instance_id` values.

Examples: equipment, enchanted books where individuality matters, artifacts, historical collectibles, provenance-bearing unique items.

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
- valid enchantment behavior
- resource-family/pouch eligibility

Names/lore/models are presentation, not identity.

## Item instance

Instance-level state may include:

- `item_instance_id`
- `definition_id`
- created timestamp/source
- creator/original owner where relevant
- enchantments
- durability/other mutable state where authoritative persistence needs it
- provenance/historical metadata

The definition describes what an item is. The instance describes which exact item it is.

## Stable categories

Top-level categories remain small:

- Materials
- Equipment
- Usables
- Progression
- Historical

Future content should usually add definitions inside these categories rather than create new foundational systems.

## Minecraft-native behavior

Use Minecraft item/render/inventory behavior where suitable. A definition may map directly to a vanilla material.

Custom code adds only the network/persistent semantics Minecraft does not provide.

## Ownership versus use

Ownership/trading/storage is unrestricted by skill unless an explicit future system requires otherwise.

Use/equip is skill-gated.

Crafting requirement is separate from use requirement. A specialist may craft an item they cannot personally use.

## Equipment/stat model

Keep the stat surface small. Candidate core families include:

- gathering speed
- gathering luck
- damage
- armor/defense
- health where needed
- attack speed only if it adds real gameplay

Do not persist effective/calculated stats. Persist the sources (definition, player skill, enchantments, temporary effects) and derive the result deterministically.

## Modifier pipeline

Conceptual order:

`base item/action -> skill modifier -> enchantment modifier -> temporary effect/context -> effective result`

Exact mathematics may vary by stat but must be centralized/deterministic rather than scattered across event handlers.

## Enchantments

Vanilla and custom enchantments may share one logical definition/validation layer. V1 should keep the custom catalog small and retain Minecraft enchanting/anvil behavior where it already works.

## Minecraft ItemStack representation

A rendered ItemStack may contain compact internal metadata such as:

- `definition_id`
- `item_instance_id` when applicable
- metadata/schema version

Do not store the entire authoritative database record inside ItemStack metadata.

For high-value individual items, authenticity comes from persistent identity/ownership, not display name/lore.

## Inventory authority

Minecraft inventory is the active gameplay representation of network-owned persistent state while one backend owns the session.

Important boundaries (login, transfer, market listing, secure trade, recovery) validate authoritative state. Do not query PostgreSQL for every routine inventory click if the loaded single-writer state is already valid.

## Pouches

Gathering pouches exist only to remove inventory friction at high throughput.

Planned families:

- Mining
- Woodcutting
- Farming
- Fishing later

They are not generic backpacks. A pouch accepts only its configured resource family and is skill-gated for use.

Pouch state is persistent player/item state, not zone-instance state.

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

Do not guess-repair suspicious valuable items. Reject, rebuild from authority, or quarantine them and emit an audit signal.

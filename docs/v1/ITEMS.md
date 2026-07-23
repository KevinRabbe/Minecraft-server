# Items, Resources, and Compression

## Stable item categories

### Materials
- raw resources
- refined resources
- components
- compressed forms

### Equipment
- weapons
- tools
- armor
- bows/ranged
- shields
- amulets/accessories

### Usables
- potions/consumables
- active utility items

### Progression
- artifacts
- recipes/knowledge where needed

### Historical
- event/provenance collectibles with no power

Do not proliferate new item-system categories when a new item inside an existing category is sufficient.

## V1 raw-resource baseline

Initial Overworld economic resources already identified:

### Mine
- Iron Ore
- Coal
- Stone
- Lapis Lazuli

### Forest
- Raw Timber

### Farm
- Grain
- Raw Fiber
- Herb
- Raw Meat
- Hide

### Nether
Use Minecraft-native Nether materials where useful, including at least:

- Nether Quartz
- Glowstone/Glowstone Dust
- Nether Wart
- Blaze materials
- Soul Sand where required

The exact V1 economic allowlist is frozen before implementation of resource generation; arbitrary Minecraft blocks do not automatically become economic resources.

## Refining graph

Initial families may include:

- Iron Ore -> Iron Ingot -> Steel Ingot (with Coal)
- Stone -> Cut Stone
- Raw Timber -> Treated Timber
- Raw Fiber -> Cord
- Herb -> Herbal Extract
- Hide -> Leather
- Raw Meat + Grain -> Field Ration

Exact ratios are balance configuration, not design constants.

## Compression

Use quantity compression before inventing new raw-resource families.

Initial structural rule:

`128 normal units <-> 1 Enchanted unit`

The ratio may remain configurable until balance is frozen.

Compression/decompression:

- is explicit and player-controlled
- is reversible unless a specific future item says otherwise
- grants no skill XP
- does not automatically normalize Bazaar markets
- may be used as a high-cost ingredient representation

Further tiers such as `Condensed` are deferred until real resource volumes justify them.

## Market identity of compressed tiers

Normal and compressed forms are independent items/markets. `Steel` and `Enchanted Steel` may have different player-discovered prices. The server does not automatically arbitrage or match orders across tiers.

## Unique item identity

Non-fungible valuable items should receive stable `item_instance_id` values and preserve provenance. Names, lore, display data, or inventory positions are not identity.

## Gathering pouches

Generic storage remains Minecraft storage. Specialized pouches exist only to remove inventory friction once a gathering skill becomes fast enough to need them.

Planned families:

- Mining Pouch I-III
- Wood Pouch I-III
- Farming Pouch I-III
- Fishing Pouch I-III later

A pouch is skill-gated for use and only accepts its resource family. Eligible drops may route directly into it.

## Later auto-compression artifact

Not required to ship V1 unless testing proves the need. Planned behavior is convenience only: configurable 1/3/5 resource slots that automatically perform the same lossless compression conversion a player could do manually. It never increases yield or performs gathering.

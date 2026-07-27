# Skills, Gathering, and Modifiers

## Launch/early skills

The generic progression framework supports at minimum:

- Mining
- Woodcutting/Foraging
- Farming
- Combat-related progression where useful
- Refining
- Crafting
- Enchanting
- bounty-family progression such as Spider, Zombie, and Golem

Fishing is not required on Day 0 and may be introduced through a player-selected Fishing expansion/district.

## Implemented starter baseline

The authority/runtime baseline is now proven for the first ordinary progression loops.

### Generic progression authority

- skill identity and XP are PostgreSQL-authoritative;
- the active cap is global/world-configured and advances only `50 -> 75 -> 100`;
- XP cannot hide above the active cap;
- retrying one XP event cannot duplicate XP;
- raising the cap resumes progression from the committed capped state.

The currently loaded starter curves for Mining, Woodcutting, Farming, Combat and Crafting are provisional content values. Their exact curve shape is tuning, not architecture.

### Item-use eligibility projection

Definition-owned skill requirements remain separate from ownership, trade and crafting. The current bundled item catalog declares no such requirement, so Paper performs no eligibility identity/progression read and blocks no action.

When content eventually opts an item into a requirement, Paper uses a bounded disposable projection rather than querying PostgreSQL on every use/equip action:

- only skills referenced by configured item requirements are loaded;
- attachment refresh publishes no stale snapshot while its authoritative read is in flight;
- committed XP results from gathering, managed ordinary PvE, personal crafting/recovery and crafting commissions advance the projection directly by committed skill `state_version`;
- a committed award racing a stale refresh is merged so the newer version wins;
- detach/reconnect invalidation fences the in-flight read and its retries so an older session cannot republish permission state;
- missing/untrusted projection state fails closed for restricted items;
- the projection remains disposable and never becomes progression authority.

Action-level use/equip enforcement remains intentionally dormant until launch content opts a real item into a requirement.

### Authorized gathering

The first Paper source path is:

`authored source interaction -> exact source cycle -> immutable harvest -> commodity delivery + XP -> fenced persistent inventory`

Current proof content includes:

- Mining -> Raw Iron;
- Woodcutting -> Oak Log;
- Farming -> Wheat.

Only version-controlled authored source coordinates are economic sources. A player-placed block that happens to use the same Minecraft material is not a resource authority and cannot mint the configured commodity/XP.

Source cooldown is durable authority. The physical block representation is derived from PostgreSQL `next_available_at`: during cooldown it may render as absent, and restart reconciliation restores the correct visible state from authority rather than trusting saved world bytes.

### Ordinary PvE source identity

Ordinary managed PvE reuses the same renewable source/reward authority rather than creating a second mob-drop economy.

The entity path is:

`source cycle -> unique spawn binding -> exact runtime entity UUID -> exact kill claim -> existing harvest fulfillment`

Current proof content is one managed Zombie source producing Rotten Flesh + Combat XP.

Important invariants:

- entity-bound sources cannot be harvested directly without a matching active kill claim;
- duplicated/stale mobs cannot consume a later source cycle;
- environmental/no-player death advances the source cycle without reward;
- expired pending/active entity bindings recover without leaving the source permanently locked;
- vanilla hostile drops/XP are suppressed on the managed ordinary-PvE backend so they do not form a second value faucet.

### Personal crafting

The first live crafting path is:

`authoritative carried commodities -> exact deterministic ingredient removal -> immutable craft -> durable output custody -> recoverable exactly-once Crafting XP -> pending delivery -> fenced persistent inventory`

Current proof recipe content is deliberately narrow:

`2 Raw Iron + 1 Oak Log -> Starter Sword`

The Starter Sword is an individualized item with persistent normalized intrinsic roll state. The current `damage` roll profile and 25-XP award are provisional tuning values.

Crafting XP is a recoverable fulfillment keyed by immutable `craft_id`. A crash after the craft commits but before the XP fulfillment marker cannot duplicate the item or XP; retry uses a deterministic XP operation ID.

Individualized output uses generic pending unique-item custody. Paper projects the exact post-claim item authority version, inserts that exact representation into serialized player state, then commits item custody + player state atomically. Full inventory leaves the unique item safely pending.

The current `/craft <recipe>` surface is intentionally minimal. A richer crafting station/UI is presentation work, not a new authority model.

The persisted Starter Sword damage roll is now applied by Paper through fail-closed intrinsic attribute materialization from the trusted item definition plus authority-validated runtime snapshot. Item custody/crafting settlement still owns no effective combat value, and later upgrade, player-skill, enchantment, equipment-context and temporary-effect stages remain separate inputs.

## Progression philosophy

Skills support specialization and optional routes. No skill should become a universal mandatory path merely because it exists.

Players are not assigned permanent classes. Time investment, capability unlocks, equipment, capital, and market access create specialization; players may broaden later.

## Staged active caps

The progression framework has a world-era/configured **active cap**:

- Day-0/launch era: 50
- later expansion era: 75
- much later era: 100

Exact timing and XP curves are balance/content configuration.

Rules:

1. XP cannot accumulate invisibly beyond the active cap.
2. raising the cap reopens progression from the player's committed capped state;
3. earlier milestone rewards/unlocks do not reissue when a cap rises;
4. a skill may define fewer meaningful rewards than the global active cap, but it cannot silently bypass the active cap;
5. cap state belongs to feature/world configuration, not one player's local client state.

## Skill XP versus skill benefit

These are separate decisions.

An action/resource may benefit from a skill's speed/luck/tool effects without being a valid XP source for that skill.

Example: sand can benefit from Mining-oriented extraction bonuses while granting no Mining XP if it is not an authorized Mining training source.

This prevents accidental optimal training routes from being defined by tool type alone.

## Authorized economic sources

Economic output/XP is granted only from authorized source state, not arbitrary player-placed blocks/entities.

Conceptual validation:

1. player action occurs;
2. target belongs to an authorized zone/source node/cycle;
3. target is in a valid state (mature, alive, not already consumed, etc.);
4. player/use requirements are valid;
5. base yield is determined;
6. skill/tool/enchantment/context modifiers are applied;
7. authoritative output/XP is created once using a stable operation/event identity where retries are possible;
8. source enters respawn/reset/next-cycle state.

Player-place -> break loops must not mint XP/resources unless the source is explicitly designed to do so.

## Woodcutting / Foraging

The authorized tree-source baseline exists. Later progression mechanics may include:

- skill speed
- skill luck/quality where meaningful
- better axes/use requirements
- connected valid-tree breaking at higher progression
- specialized Wood pouch where throughput justifies it

High level should increase useful throughput without turning into AFK automation.

## Mining

Mining is an extraction profession, not merely "pickaxe use".

Potential Mining-benefit materials may include ores, stone, sand/gravel, Quartz/Glowstone/Soul Sand later, while XP eligibility remains separately configured.

The authorized node/cooldown baseline exists. Later progression mechanics may include:

- speed
- luck/quality where meaningful
- better tools/use requirements
- multi-block/vein-style manual extraction at higher progression
- Mining pouch

## Farming

The authorized starter source baseline exists. Later progression mechanics may include:

- speed
- luck/yield where meaningful
- better tools/multi-harvest
- Farming pouch
- richer authoritative crop/livestock cycles where needed

Only valid mature/legitimate cycles grant economic output/XP. Plant/break spam or immature-cycle abuse must not.

## Combat

Combat progression may govern PvE effectiveness/equipment requirements/drop rules as needed.

- authorized ordinary mobs may grant configured progression and economic output;
- Portal/Map difficulty is **not** permission-gated by Combat level;
- Bounty tier access may depend on the relevant bounty-family progression rather than one global combat ladder;
- ranked PvP disables permanent gear/skill advantage through standardized temporary state;
- clan war intentionally uses real economic equipment/resources through separate custody/settlement rules.

## Bounty-family progression

A bounty family is a specialization track tied to one mob category, for example Spider, Zombie, or Golem.

Progression may unlock:

- higher bounty tiers;
- higher-grade family material access through those tiers;
- family-specific recipes/use requirements where appropriate;
- larger family pouch capacity/QoL;
- other category-specific capability.

Do not make bounty progression a generic infinite `+damage%` ladder. Much of the combat specialization should come from equipment/build choices.

Bounty materials remain Bazaar-tradable. A player does not need personal progression in every family merely to buy/own/craft with its fungible materials unless an explicit use requirement says otherwise.

## Refining

XP comes from legitimate irreversible processing, not reversible compression.

Specialization may increase:

- processing speed
- capacity/concurrency
- recipe access
- modest material/resource efficiency where economically safe

Do not create bonus-output resource generation without an explicit economic design reason.

## Crafting

XP comes from legitimate recipe completion.

Specialization may increase:

- recipe access;
- speed/batch convenience;
- capacity/concurrency;
- modest material efficiency or fee reduction where configured.

Crafting level should **not** create a runaway perfect-roll monopoly. Individualized gear roll quality is generated authoritatively from the item's configured roll profile and remains fundamentally an item-instance property.

Dedicated crafters remain valuable through access, throughput, capital, market knowledge, and volume even when the roll distribution itself is not heavily skill-biased.

## Enchanting

Enchanting retains normal Minecraft XP as the operational enchanting resource where suitable.

Its progression may amplify configured XP gain for other skills but must never amplify itself. It obeys the same staged active-cap rules.

## Modifier pipeline

Keep calculation centralized and deterministic.

Conceptual sources:

`base item/action -> intrinsic roll -> upgrade state -> player skill -> equipment set/context -> enchantment -> temporary effect -> effective result`

Not every action uses every stage. The order defines ownership/composition boundaries: an intrinsic item roll must not accidentally multiply later player skill, upgrade, enchantment, set/context, or temporary-effect contributions.

Do not persist derived effective values that can be recomputed from authoritative inputs.

Map modifiers/bounty context may contribute through explicit context inputs rather than one-off event-handler arithmetic.

## Throughput target philosophy

High skill progression should feel materially stronger. Large effective throughput improvements can come from the product of several understandable mechanics:

- skill speed
- luck/yield
- tool speed
- manual multi-block interaction
- inventory-friction reduction via pouches

Do not solve progression with a single giant opaque multiplier.

## Pouches

Pouches are convenience/progression tools, not generic storage.

Two broad uses are allowed:

### Gathering pouches

Examples: Mining, Woodcutting, Farming, later Fishing.

### Bounty-family pouches

One pouch may store the fungible materials for one bounty family such as Spider/Zombie/Golem.

Common rules:

- explicit resource-family allowlist;
- persistent capacity/state;
- eligible authorized drops may route directly into them;
- capacity/tier numbers remain balance configuration;
- moving/selling contents uses the same authoritative commodity accounting as normal inventory;
- pouch custody never changes Bazaar tradability.

## No gameplay automation

Permitted automation removes friction after player action. It must not create resources while the player is absent.

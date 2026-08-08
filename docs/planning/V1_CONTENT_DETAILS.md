# V1 Content Details

Status: **Canonical V1 content-mechanics plan.** This document resolves the detailed content questions that must be known before implementing canonical launch content. Exact quantities, cooldown lengths, HP/damage values, drop rates, recipe counts, respawn timers, and other balance knobs remain configuration/playtest data unless explicitly fixed here.

## Design rule

The goal is not to turn V1 into a giant handcrafted MMO content pack. Each launch mechanic should be:

- readable with ordinary Minecraft presentation plus particles/sounds/nameplates before custom models exist;
- deterministic enough that players can learn and punish it;
- expressible through reusable registered capabilities rather than per-mob ad-hoc authority;
- compatible with later custom models without changing persistent identity;
- bounded in entity count and scheduler work;
- separate from persistent reward/value authority.

Vanilla entities below are **technical runtime bases only**. Their vanilla identity is not the canonical player-facing creature identity. Vanilla behavior that conflicts with the authored mechanic may be suppressed or replaced by the registered encounter capability.

---

# 1 — Starter Combat elite and first Map

## Ruinbound Champion

The renewable starter elite is **Ruinbound Champion**.

Player-facing role:

- optional authored elite in the Hub Region's directly walkable starter Combat area;
- teaches the basic V1 combat language of visible commitment -> avoid/punish -> exposed recovery;
- is not a Rootborn/Ashbound/Veilborn creature and never counts for a Bounty contract;
- is not a capital-M Map enemy package identity;
- exists primarily as the renewable bootstrap source for the Map loop.

Technical V1 base:

- `HUSK`;
- iron-axe style melee presentation;
- persistent custom name/elite cue;
- ordinary vanilla drops and vanilla XP disabled.

### Core moves

**Committed Cleave**

- clear wind-up using sound/particles/pose;
- broad close-range hit after the tell;
- player can step out or pass around it;
- completion leaves a short punish window.

**Linebreaker Rush**

- Champion faces/marks the target before moving;
- performs a short straight committed rush;
- does not steer perfectly once committed;
- a miss creates the clearest damage window in the encounter.

No random unavoidable stun, teleport, or instant burst is part of the starter elite.

### Placement

The Champion lives in a small **ruined watchyard/side arena near the far end of the starter Combat path**, before the first Rootborn portal but **not blocking that portal**. Players naturally see/pass the encounter while traversing the starter Combat area, but defeating it is not required to enter Rootborn content.

Exact block coordinates are build data and are selected when the Hub map is authored; the spatial relationship above is canonical.

### Respawn and eligibility

- one authored renewable source per starter-Combat region instance is sufficient for V1;
- a successful authoritative player kill makes the source enter its configured respawn cycle;
- exact respawn duration is tuning data;
- no per-player daily/weekly lockout is introduced;
- farming the elite can therefore create additional **difficulty-1 bootstrap Maps**, with supply controlled by source respawn and regional instance count rather than an RNG/pity subsystem;
- environmental/non-player resolution does not create a Map.

### First Map profile

Every qualifying authoritative Ruinbound Champion player kill issues exactly one individualized Map with this canonical bootstrap profile:

- difficulty: **1**;
- environment: **Forgotten Bastion**;
- enemy package: **Relic Guard**;
- objective: **Extermination**;
- modifiers: **none**;
- ordinary unique-Map provenance/ownership rules;
- generation seed derived/stored through the normal Map issuance path.

Issuance must be exactly once from the settled managed-kill operation. A crash after the kill but before delivery is recoverable; retrying the same kill cannot create another Map.

The Map remains tradable. There is no requirement that the killer personally opens it.

---

# 2 — Bounty-family creature implementation plan

## Rootborn

Rootborn combat language is **growth, roots, control zones, ambush and breakable bark protection**.

| Identity | V1 technical base | Role | Canonical mechanic |
| --- | --- | --- | --- |
| Rootling | `SILVERFISH` | common/swarm | gains pressure through numbers and short pack surges; individually fragile |
| Thornstalker | `SPIDER` | mobility/ambush | telegraphed lunge; successful committed hit can apply a short root/slow; missed lunge has recovery |
| Barkhide | `HUSK` | heavy/frontline | frontal bark-guard state reduces ordinary pressure until broken; exposed state is more vulnerable/aggressive |
| Bloomkeeper | `STRAY` | support/control | interruptible support channel plus temporary root/spore control zones; may strengthen nearby Rootborn while channel remains active |
| The Heartroot | `IRON_GOLEM` | boss | anchored living-core encounter using exposed windows, rootguard phases and controlled add/zone pressure |

### The Heartroot

Tier-1 encounter:

1. exposed damage phase;
2. clear transition into **Rootguard**;
3. a small number of authored root nodes/guard targets must be broken while telegraphed root lanes pressure positioning;
4. Heartroot re-enters an exposed damage window;
5. repeat only as required by configured health thresholds.

Tier-2 additions:

- Bloomkeeper interaction/add pressure;
- more complex but still deterministic root-lane patterns;
- alternating fortified/exposed windows;
- elite/mutated ordinary Rootborn combinations.

Heartroot must never be a stationary HP sponge. Rootguard exists to create a target-priority/positioning problem, not merely invulnerability time.

## Ashbound

Ashbound combat language is **heat, commitment, cracked armor, vents and battlefield hazard pressure**.

| Identity | V1 technical base | Role | Canonical mechanic |
| --- | --- | --- | --- |
| Cinderling | `MAGMA_CUBE` | common/swarm | aggressive hopping pressure; short-lived telegraphed ember patch on configured death/enrage events |
| Scorchmaw | `VINDICATOR` | pursuit | committed rush/strike that punishes stationary players; miss creates an exposed recovery window |
| Kilnback | `HUSK` | heavy/frontline | plate/armor state must be cracked through guard-break/stagger pressure; exposed core changes its attack cadence |
| Ashweaver | `BLAZE` | support/control | interruptible heat/vent channel; creates bounded ash/ember zones and can temporarily strengthen nearby Ashbound |
| The Black Kiln | `RAVAGER` | boss | plate/vent/overheat cycle with exposed-core windows and arena heat management |

### The Black Kiln

Tier-1 encounter:

1. armored pressure phase;
2. clearly telegraphed **Vent** sequence creates a small number of hazard lanes/zones;
3. successful handling of the vent sequence opens an **Overheated Core** damage window;
4. boss returns to armored pressure if not defeated.

Tier-2 additions:

- Ashweaver interaction/adds;
- multiple vent directions/zone combinations;
- elite/overheated ordinary Ashbound;
- shorter transitions but never hidden/unreactable burst.

Black Kiln defensive phases are broken through mechanics/positioning rather than simply adding extreme HP.

## Veilborn

Veilborn combat language is **telegraphed displacement, false positions, phase windows and spatial control**.

| Identity | V1 technical base | Role | Canonical mechanic |
| --- | --- | --- | --- |
| Flicker | `ENDERMITE` | common/unstable | periodically makes a short scripted slip with a visible origin/destination tell; no random long-range teleport spam |
| Riftstalker | `ENDERMAN` | mobility/ambush | marks a destination/target -> phases -> committed reappearance strike -> punishable recovery |
| Husk of Glass | `SHULKER` | defensive/heavy | shell/angle-defense state reduces straightforward frontal pressure; shell collapse creates a vulnerability window |
| Veilweaver | `EVOKER` | support/control | scripted false silhouettes and bounded displacement/visibility fields; support channel is readable/interruptible |
| The Unseen Gate | `ENDERMAN` | boss | scripted rift positions, false destinations, safe/unsafe lanes and exposed windows after failed displacement attacks |

Default/random vanilla teleport/spell behavior that conflicts with these authored mechanics must be suppressed. Veilborn readability has priority over preserving the vanilla base AI.

### The Unseen Gate

Tier-1 encounter:

1. boss creates a small set of visibly marked rift destinations;
2. false destinations resolve early enough for attentive players to identify the true threat;
3. boss commits to a reappearance attack;
4. a missed/avoided strike creates an exposed window.

Tier-2 additions:

- Veilweaver support/decoys;
- coordinated displacement fields;
- safe/unsafe spatial lanes;
- elite unstable variants.

There is no random teleport chain whose only counterplay is guessing.

---

# 3 — Capital-M Map encounter design

## Runtime model

V1 uses **authored compact templates with deterministic spawn/encounter anchors**, not procedural terrain generation.

The Map seed determines reproducible choices such as:

- which valid spawn anchors are used;
- pack composition inside the selected package;
- elite-trait assignment where allowed;
- supported modifier variation;
- other bounded encounter choices.

Terrain itself remains a curated template for V1. This keeps build quality high and runtime behavior debuggable.

All three V1 enemy packages are intended to be compatible with all three V1 environments. A specific combination may fail closed during implementation until its required capability is implemented, but there is no product rule permanently pairing one package to only one environment.

## Environments

### Forgotten Bastion

Compact ruined fortification built around cover, lanes and chokepoints.

Canonical flow:

`Breach Gate -> Outer Court -> Collapsed Hall -> Inner Yard`

Rules:

- entrance/staging pad is safe before the encounter begins;
- three primary combat cells with several authored spawn anchors each;
- short elevation differences/battlements provide positioning without mandatory parkour;
- cover exists against ranged pressure;
- no void/fall instant-death gimmick.

This is the first environment implemented because the starter Map uses it.

### Flooded Depths

Compact submerged works/cavern built around choosing dry routes versus slower exposed water.

Canonical flow:

`Dry Dock -> Sluice Gallery -> Flood Channel -> Pump Chamber`

Rules:

- shallow water and dry islands/ledges create positional choices;
- V1 does **not** require long underwater combat, air-management puzzles or swimming mazes;
- at least two usable dry approaches exist through the main encounter spaces;
- vertical layers are small enough that mob navigation remains reliable;
- encounter hazards must remain visible above/through water.

### Windscar Ruins

Compact exposed high-ground ruins built around sightlines and knock/position pressure.

Canonical flow:

`Sheltered Approach -> Broken Causeway -> Wind Court -> High Platform`

Rules:

- open sightlines are balanced by authored cover;
- wind/push zones are local, telegraphed and temporary rather than permanent random knockback;
- falls use recovery shelves/lower ledges or bounded drops; the environment must not turn one ordinary knock into an arbitrary void death;
- bridge/causeway traversal remains combat space, not precision parkour.

## Map-only enemy packages

### Relic Guard

| Role | V1 technical base | Canonical behavior |
| --- | --- | --- |
| Sentry | `SKELETON` | ranged lane pressure; prefers authored firing positions and does not endlessly kite |
| Shieldbearer | `HUSK` | slow frontal guard; flank/guard-break pressure creates exposure |
| Channeler | `STRAY` | interruptible defensive/repair channel that temporarily protects or strengthens one nearby Guard target |
| Juggernaut | `RAVAGER` | committed heavy charge/slam with strong tell and miss recovery |

### Deep Brood

| Role | V1 technical base | Canonical behavior |
| --- | --- | --- |
| Crawler | `CAVE_SPIDER` | fast close pressure; strongest when multiple paths surround the player |
| Burrower | `SILVERFISH` | telegraphed brief burrow/re-emerge movement; origin/destination cue remains visible |
| Spitter | `SPIDER` | scripted ranged spit/hazard projectile; does not inherit arbitrary ranged vanilla behavior |
| Broodkeeper | `WITCH` | interruptible support behavior; may spawn a bounded temporary Crawler pair or strengthen nearby Brood |

### Ruin Raiders

| Role | V1 technical base | Canonical behavior |
| --- | --- | --- |
| Skirmisher | `VINDICATOR` | aggressive melee repositioning and short committed pushes |
| Heavy | `HUSK` | slower armored pressure with stagger/guard-break vulnerability |
| Marksman | `PILLAGER` | ranged pressure from authored lines/cover; repositioning has readable pauses |
| Bannerhand | `EVOKER` | interruptible support aura/channel that improves nearby Raiders rather than being a second primary DPS caster |

Package mechanics use registered capabilities. Vanilla potion/spell/teleport behavior may be suppressed whenever it conflicts with the canonical role above.

## Objectives

### Extermination

- sequential authored packs/waves rather than spawning the entire encounter simultaneously;
- completion requires all objective-owned targets to be defeated;
- ordinary environmental/non-player enemy death fails closed or is handled by the authoritative run rule rather than silently awarding progress;
- HUD/chat progress may show exact remaining objective targets;
- no timer is required for base completion.

### Elite Hunt

- first clear one or two bounded guard packs/encounter gates;
- then materialize/activate the marked elite target;
- completion occurs when the authoritative elite target is defeated;
- remaining disposable support mobs are cleaned up on terminal settlement;
- player-facing progress shows encounter phase plus the marked elite identity/health once active; hidden support counts do not need exact display.

## Modifiers

### Fortified

- increases defensive pressure through armor/guard/knockback-resistance capability;
- does not become a giant hidden HP multiplier;
- preserves the same tells and break windows.

### Relentless

- reduces configured attack/ability recovery and may modestly increase movement pressure;
- does not also multiply raw damage;
- minimum recovery floors prevent unreadable animation/attack spam.

### Swarming

- increases **normal** enemy count/composition density;
- never duplicates the objective elite merely because the modifier is present;
- obeys strict per-wave and total live-entity caps;
- may use slightly reduced per-normal-target durability if needed for pacing, but exact values are balance data.

## Elite traits

### Bulwark

- grants a visible temporary guard state;
- frontal pressure is reduced while the state holds;
- flanking or sufficient guard-break pressure opens a short exposed window;
- cannot stay guarded permanently.

### Hunter

- visibly marks/focuses the player;
- uses a committed pursuit/lunge sequence;
- failed commitment creates recovery;
- does not receive unconditional permanent speed dominance.

### Volatile

- defeat begins a clearly telegraphed delayed burst/hazard;
- warning marker persists long enough to leave the area;
- no zero-delay death explosion;
- burst belongs to the defeated entity/run and cannot create persistent world value.

## V1 implementation order

Implement the canonical Map content in this order so each step adds a reusable capability:

1. **Forgotten Bastion + Relic Guard + Extermination + no modifier** — bootstrap canonical Map;
2. **Fortified + Bulwark** — shared defense/guard capability;
3. **Elite Hunt + Hunter** — reusable marked-target lifecycle;
4. **Flooded Depths + Deep Brood**;
5. **Relentless** — shared cadence modifier;
6. **Windscar Ruins + Ruin Raiders**;
7. **Volatile** — delayed terminal hazard capability;
8. **Swarming** — bounded density modifier;
9. enable/test the remaining valid environment/package/objective combinations.

Unsupported combinations fail closed until their required capability exists. They are not silently downgraded to another encounter.

---

# 4 — V1 equipment mechanics, rolls, upgrades and salvage

## Roll-surface rule

V1 deliberately uses only **three intrinsic rolled stat IDs**:

- `damage`;
- `defense`;
- `gathering_speed`.

Every rolled V1 item has **exactly one** intrinsic property. This avoids frustrating multi-stat combinations and keeps Auction House comparison readable.

The existing normalized quality model remains canonical:

- quality is an integer `0..10000`;
- creation uses the existing independent uniform quality distribution;
- historical normalized quality never rerolls;
- current absolute value derives from the active definition range.

V1 launch ranges:

- weapon `damage`: **10000..12000** basis-point multiplier range;
- wearable `defense`: **10000..11500**;
- gathering-tool `gathering_speed`: **10000..12000**.

These are the canonical initial ranges, but remain balance-versioned content and may be tuned globally without rerolling existing item quality.

## Which items roll

### Weapons — `damage`, upgradeable

- Wayfarer Blade;
- Thornhook;
- Kilnbreaker;
- Longreach Bow;
- Boltframe;
- Vanguard Pike.

### Wearables — `defense`, upgradeable

- Heartwood Mantle;
- Blackglass Guard;
- Trailrunner Boots;
- Vanguard Coat;
- Field Harness.

### Gathering tools — `gathering_speed`, upgradeable

- Forester Axe;
- Deepvein Pick;
- Harvest Sickle.

### Fixed-function items — no intrinsic roll and no V1 upgrade track

- Gatefinder Lens;
- Phase Anchor;
- Echo Bell;
- Wardstone;
- Pulse Lantern;
- Field Ration;
- Root Salve;
- Cooling Draught;
- Stabilizing Tonic;
- Prospector Lantern;
- Packframe;
- Rootborn Pouch;
- Ashbound Pouch;
- Veilborn Pouch.

Utility effect strength therefore does not become another Auction House lottery in V1.

## Weapon mechanics

**Wayfarer Blade**

- mechanically clean baseline melee weapon;
- no special proc/active ability;
- establishes the reference feel for rolled weapon damage.

**Thornhook**

- full/committed melee hits can apply a short **Hindered** capability to targets explicitly supporting mobility/control interaction;
- target-side internal immunity/cooldown prevents permanent root-lock;
- utility duration is fixed content data and is not increased by roll quality or upgrades.

**Kilnbreaker**

- full/committed hits apply strong guard-break/stagger pressure to targets exposing the registered armor/guard capability;
- still functions as a slower ordinary heavy weapon against targets with no such capability;
- roll/upgrades affect damage, not guard-break duration/power unless a later explicit balance revision adds such a stat.

**Longreach Bow**

- rewards full draw/accuracy/positioning;
- partial draws remain possible but do not receive the full intended pressure;
- damage roll applies through the authoritative projectile-damage path.

**Boltframe**

- crossbow-style high single-window pressure with slower reload cadence;
- differentiates from Longreach through stored/burst timing, not a hidden universal damage advantage;
- damage roll applies to the authoritative fired shot.

**Vanguard Pike**

- controlled extended melee reach/spacing relative to ordinary sword range;
- committed thrust provides bounded knock/spacing pressure;
- no extreme reach and no raw-DPS advantage that invalidates Wayfarer Blade.

## Wearable mechanics

**Heartwood Mantle**

- reduces configured root/slow/control duration/severity;
- after leaving an authored hostile control zone, grants a short fixed defensive recovery window;
- roll/upgrades affect defense only.

**Blackglass Guard**

- reduces repeated configured heat/hazard pressure;
- surviving a configured burst/threshold can create a short fixed defensive window;
- never grants blanket fire immunity;
- roll/upgrades affect defense only.

**Trailrunner Boots**

- improves recovery from configured ordinary slow/knock movement pressure;
- outside combat, sustained traversal may grant a modest temporary acceleration after continuous movement;
- no permanent always-on speed dominance;
- roll/upgrades affect defense only.

**Vanguard Coat**

- no family-specific mechanic;
- highest-value straightforward general defensive baseline among launch wearables where tuning supports it;
- roll/upgrades affect defense only.

**Field Harness**

- modestly reduces recovery/cooldown for **active equipment** only;
- does not affect weapon attack cooldowns or consumable use;
- has a hard minimum recovery floor so it cannot create active-item spam;
- roll/upgrades affect defense only, not cooldown reduction.

## Active equipment

**Gatefinder Lens**

- bounded pulse/window that clarifies registered true targets, rifts and misleading silhouettes;
- no player/base detection and no generic through-wall x-ray.

**Phase Anchor**

- short-lived local field;
- limits registered hostile displacement behavior inside the field and dampens configured player knock/displacement pressure;
- one player's anchor cannot permanently suppress encounter mechanics.

**Echo Bell**

- bounded information pulse for nearby registered elites, bosses and active encounter cues;
- never reveals players, hidden player bases or arbitrary containers.

**Wardstone**

- deploys a short-lived local defensive field against registered projectile/environmental pressure;
- positional tool, not passive replacement armor;
- one active field per owning player at a time in V1.

**Pulse Lantern**

- short-range pulse that can interrupt registered interruptible channels and clear/suppress specifically supported temporary hazards;
- no arbitrary universal stun or entity mutation.

Exact radii/durations/cooldowns remain tuning data.

## Consumables

**Field Ration**

- cheap ordinary-resource sustain between encounters;
- no instant burst heal;
- health recovery begins/continues only under the configured out-of-combat condition so it cannot become combat-healing spam.

**Root Salve**

- clears/reduces one configured root/slow/control pressure category and may grant a short fixed resistance window.

**Cooling Draught**

- clears/reduces configured heat/burning pressure and may temporarily slow new heat buildup;
- never grants blanket fire immunity.

**Stabilizing Tonic**

- temporarily reduces configured displacement/phase pressure;
- does not disable all knockback or all teleport mechanics globally.

## Gathering/logistics/QoL

**Forester Axe / Deepvein Pick / Harvest Sickle**

- remain the skill-authorized tools for Woodcutting, Mining and Farming;
- their single roll property is `gathering_speed`;
- upgrades improve only that rolled gathering-speed pipeline stage.

**Prospector Lantern**

- short-range pulse/indicator points toward nearby **registered authored resource sources**;
- no arbitrary block scan/x-ray;
- nearest/limited results only, with a bounded radius.

**Packframe**

- is a logistics adapter, not storage;
- allows bounded bulk collection/claiming/routing of already-authorized pending commodity deliveries using the existing delivery/pouch custody paths;
- never owns a second independent inventory/value balance.

**Family pouches**

- automatically accept only their configured family materials through the existing fungible commodity authority;
- capacity/QoL may be upgraded by configuration/content;
- no roll quality;
- material remains tradable and can leave the pouch through ordinary authorized movement.

## Upgrade progression

V1 rolled items use a deterministic **`+0` through `+5`** upgrade track.

Rules:

- upgrade always succeeds once the authoritative cost transaction commits;
- no item destruction, downgrade or random failure chance;
- no reroll of intrinsic quality;
- each level adds **2%** to the item's rolled-stat upgrade stage;
- `+5` therefore provides a total **10% upgrade-stage increase** over `+0`;
- fixed utility mechanics/cooldowns/control strength do not scale from upgrade level;
- V1 fixed-function items have no upgrade track.

Conceptually:

`base stat -> intrinsic roll multiplier -> (1 + 0.02 * upgrade_level) -> later skill/context/enchant/effect stages`

Upgrade costs:

- always include a configured Coin sink;
- use a configured subset of the same ordinary/Map/Bounty material families already associated with the item's recipe;
- early levels lean on ordinary/common materials;
- later levels may require higher-grade **non-boss** family/Map materials;
- **Heartwood Core, Kilnheart and Gate Fragment are never upgrade costs in V1**; they remain signature crafting components rather than repeat upgrade taxes;
- exact quantities/Coin costs are balance data.

## Salvage

Salvage is a guaranteed poor exit, not recipe reversal.

Rules:

- destroys the exact individualized item through the existing authoritative salvage transition;
- returns an item-specific configured subset of ordinary/common/mid-tier recipe material families;
- may return Map materials when the original item family uses them;
- never returns Heartwood Core, Kilnheart or Gate Fragment;
- never refunds Coin;
- never refunds consumed upgrade costs;
- intrinsic roll quality does not change salvage yield;
- upgrade level does not change salvage yield;
- exact return quantities remain balance data and should stay materially below reconstruction cost.

This makes crafting/upgrading real sinks while still giving unwanted rolled gear a deterministic exit.

---

# 5 — Ordinary PvE death consequence

## V1 rule

Ordinary persistent-world combat death uses a simple pocket-Coin sink:

- lose **5% of current pocket/spendable wallet Coin**;
- calculation rounds down to the smallest Coin minor unit;
- there is no minimum loss and no maximum cap in the V1 baseline;
- lost Coin is destroyed through the authoritative death-loss ledger operation; it is never dropped into the world and cannot be looted by another player;
- protected Bank Manager balance is untouched;
- carried managed items are not dropped/destroyed by this mechanic;
- no XP/skill-level loss is attached to ordinary death.

## Where it applies

The 5% rule applies only in explicitly configured **normal-world combat regions**, initially:

- Rootborn Region;
- Ashbound Region;
- Veilborn Region.

It does **not** apply in:

- the Hub civic/build area;
- the Hub's starter Combat onboarding area;
- ordinary gathering-only areas unless a future region explicitly opts in;
- capital-M Map instances, because Map death already fails/consumes the run according to Map rules;
- Ranked Arena;
- Clan War.

This prevents ordinary building/gathering accidents from becoming a money tax while still making deeper persistent combat carry risk and giving protected Bank custody a clear purpose.

Implementation must therefore make death-loss eligibility explicit by authored zone/region identity rather than enabling the current global percentage blindly on every ordinary Paper backend.

The initial policy is `500` basis points. It remains versioned configuration so playtesting can tune the percentage without changing the authority model.

---

# Planning boundary after this document

The following remain intentionally cheap tuning/build details rather than unresolved product architecture:

- exact HP/damage/armor values;
- exact ability wind-ups, cooldowns, radii and durations;
- exact respawn times;
- exact encounter spawn coordinates inside authored templates;
- exact recipe/upgrade/salvage quantities and Coin costs;
- exact Map difficulty scaling and material/Coin reward values;
- exact visual models/textures/particle polish;
- final block-by-block map builds.

Those values can be changed through balance/content revisions after the mechanics above are implemented and tested. They must not silently change the identity, authority or progression semantics locked here.

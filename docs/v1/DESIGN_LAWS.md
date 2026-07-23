# V1 Design Laws

These rules are architectural constraints, not balance values.

1. **Optimize repeatable player-hours per developer-hour.** Prefer systems that create player-generated situations over hand-authored disposable content.
2. **Use Minecraft where Minecraft already solves the problem.** Do not rebuild generic inventory, storage, enchanting, brewing, movement, building, or combat primitives without a concrete need.
3. **Specialize without isolating. Depend without forcing.** Players may buy from other playstyles instead of personally performing every activity.
4. **No prescribed progression route.** The correct path is whatever serves the player's own goal.
5. **Ownership is open; use is skill-gated.** Players may own, trade, prepare, or speculate on items before meeting their use requirement.
6. **Stable categories, growing item library.** Add items inside existing categories before inventing new systems/categories.
7. **Automate friction, never gameplay.** Sorting, routing, order matching, background job completion, and later auto-compression are valid; automatic resource production is not.
8. **Skill bonuses and skill XP are separate.** An action may benefit from a skill without being allowed to train that skill.
9. **The economy discovers prices.** The server provides markets, sinks, faucets, and conversion rules; players determine market value.
10. **Stackable -> Bazaar. Non-stackable -> Auction House.** Use Minecraft's native stack behavior as the default routing rule.
11. **Never use trust where the system can enforce correctness.** Trades, market settlement, war equipment, rewards, and staff powers must be technically bounded.
12. **Persistent work accumulates history.** Significant community builds and event rewards are archived and attributable.
13. **PvP/loss is opt-in.** Normal gathering, building, economy, and progression are safe from open-world clan destruction.
14. **Scale quantities before complexity.** Compression extends resource scale before new raw-resource families are introduced.
15. **Lock mechanics; tune numbers from real play.** XP curves, prices, drop rates, speeds, luck, capacities, and recipe quantities are configuration, not design law.

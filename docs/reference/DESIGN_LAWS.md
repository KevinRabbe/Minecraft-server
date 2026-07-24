# Design Laws

These are architectural constraints, not balance values.

1. **Optimize repeatable retained player-hours per developer-hour.** Prefer systems that generate situations and interaction over hand-authored disposable content.
2. **Use Minecraft where Minecraft already does the job.** Build only the persistent/game-specific layer Minecraft does not provide.
3. **Do less initially, but make the work support more later.** V1 should be narrow without creating rewrite traps.
4. **Small content surface, high interaction depth.** A small number of systems should reinforce one another.
5. **Specialize without isolating. Depend without forcing. Compete without destroying other playstyles.**
6. **Progression is goal-driven, not route-driven.** There is no globally correct progression path.
7. **No major path should invalidate another.** Trading, gathering, crafting, combat, enchanting, and social play should create options and dependencies rather than mandatory universal routes.
8. **Ownership is open; use is skill-gated.** A player may own, trade, store, or craft an item before meeting its use requirement.
9. **Automate friction, never gameplay.** Routing, order matching, settlement, sorting, background job completion, and convenience compression are valid; AFK resource production is not.
10. **Scale quantities before complexity.** Use compression/density before inventing unnecessary resource families or systems.
11. **Scale concurrency by replication, not geography.** Keep gameplay zones deliberately compact and create additional instances only when that specific zone needs capacity.
12. **Area/zone is gameplay; backend is infrastructure.** Players choose logical places and activities, never backend IDs.
13. **The economy discovers prices.** The server provides markets, sinks, faucets, and conversion rules; players determine item value.
14. **Minecraft stackable/fungible -> Bazaar; individualized/non-fungible -> Auction House by default.** Do not duplicate configuration unless a real exception appears.
15. **Never use trust where the system can enforce correctness.** Trades, markets, commissions, war settlement, rewards, voting, and staff powers must be technically bounded.
16. **Every valuable asset has one authoritative owner/location at a time.** Critical movement is atomic and idempotent.
17. **Commodities are quantities; unique items are identities.** Do not create per-unit database objects for fungible stacks.
18. **Persistent state never depends on a disposable instance.** Instances may disappear; player/economic/history state may not.
19. **Exactly one backend may mutate a player's persistent runtime state at a time.** Transfers explicitly hand off ownership.
20. **Skill benefit and skill XP eligibility are separate.** An action can benefit from a skill without being a valid training source.
21. **Persistent work accumulates history.** Significant player decisions, builds, projects, records, and historical rewards are archived/attributable where useful.
22. **Scarcity of memories and participation, not scarcity of required power.** Historical value may be scarce; mechanical power should not depend on one-time participation.
23. **PvP/loss/disruption is opt-in.** Normal gathering, economy, building, and progression are protected from uncontrolled open-world destruction.
24. **Code implements mechanics; data implements balance.** Curves, rates, capacities, prices, ratios, requirements, roll ranges, and content lists should be configurable where practical.
25. **Lock mechanics; tune numbers from real play.** Do not turn placeholder numbers into architectural truth.
26. **Expand from real usage data.** Add complexity only when observed player behavior or measured load justifies it.
27. **Structure before tuning.** During foundation work, spend the overwhelming majority of effort on authority, persistence, exploit resistance, recovery, interactions, and stability; plausible balance is sufficient.
28. **Settled decisions stay settled.** Do not reopen a locked design choice during ordinary implementation unless explicitly requested or a direct contradiction is discovered.
29. **Players choose the world's trajectory.** Developers define valid choices and rules; they do not steer which valid expansion choice, clan, market strategy, or social outcome should win.
30. **Protect procedural integrity, not preferred outcomes.** Unexpected player strategies are valid unless they violate an invariant, exploit a defect, or contradict an explicit rule.
31. **Map difficulty measures encounter power, not player permission.** Difficulty may exceed current achievable power; available gear/builds/skill determine the practical ceiling.
32. **Power availability comes from content, not arbitrary difficulty gates.** Major milestones such as Nether/End may introduce stronger gear; they do not unlock permission to enter particular Map difficulty numbers.
33. **Bounty specialization creates supply; the market connects it to everyone.** Family materials remain Bazaar-tradable and personal completion is not required merely to economically acquire them.
34. **Rolled gear is bounded optimization.** High rolls should be meaningfully better but not a separate required power tier; roughly 10–30% low-to-high relevant value per item is the target envelope unless data justifies otherwise.
35. **Perfect rolls are prestige/luxury, not viability.** Extremely rare perfect items may become very expensive without becoming mandatory progression.
36. **Skill mastery is staged with the world.** Launch exposes an active cap of 50, later 75, and much later 100; no hidden overflow XP bypasses a cap increase.
37. **District capability is authored; physical district form is player-created.** Ordinary voted districts have no developer-authored blueprint, required appearance, or minimum block count.
38. **Most expansions broaden the ecosystem; selected milestones raise the ceiling.** Horizontal QoL, specialization, production, resource, and sidegrade additions are normal; major vertical jumps are deliberate and rare.
39. **Old systems remain economically relevant where practical.** New content should add dependencies/opportunities rather than make earlier districts/resources automatically obsolete.
40. **Canonical history begins at Day 0.** Private/closed beta state is disposable; after public opening, persistent world state and legitimate player decisions are treated as history rather than casually reset.
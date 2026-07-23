# Minecraft Server

Minecraft-first persistent multiplayer server framework.

## V1 objective

V1 proves a persistent, player-driven economy/social loop with safe cross-server state:

`join -> choose activity -> gather/fight/build/trade -> progress -> acquire better gear -> interact with other players -> log out -> return with exact state`

The server is optimized for repeatable player-hours generated per developer-hour. Minecraft supplies movement, worlds, inventory, building, combat, enchanting, brewing, and other primitives where they are already sufficient. Custom code exists only where the persistent game layer needs behavior Minecraft does not provide.

## V1 engineering order

1. Freeze the V1 contract in `docs/v1`.
2. Bootstrap `common`, `paper`, and `velocity` modules plus local infrastructure.
3. Prove cross-server player-state correctness before valuable gameplay exists.
4. Add renewable activity worlds and authorized resource generation.
5. Add skills, items, economy, social systems, PvP/war, and community projects in that order.

See [`docs/v1/V1_SCOPE.md`](docs/v1/V1_SCOPE.md) for the authoritative V1 scope.

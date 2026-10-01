# Pathfinding

How the search sees the world: the library API, the movement rules and their
costs, path smoothing, and the A\* core. The layers behind it are in
[`ARCHITECTURE.md`](ARCHITECTURE.md); the tools for looking at searches are in
[`TOOLS.md`](TOOLS.md).

## Using it

```java
ArrayBlockView world = ...;                       // an imported map, or /goto's copy of the loaded blocks
WorldPathfinder finder = new WorldPathfinder(world, EntityProfile.DEFAULT, true);

SearchResult r = finder.find(new BlockPoint(0, 1, 0), new BlockPoint(10, 1, 0));
for (PathStep step : r.path()) {
    // step.pos(): feet block; step.via(): WALK, DIAGONAL, JUMP_UP or DROP
}

// Or spread a search over ticks:
AStarSearch search = finder.search(start, goal, SearchListener.NONE);
search.run(200);                                  // up to 200 expansions this tick
```

## Movement rules (`MoveValidator`)

Positions are the block the entity's **feet** occupy. The default entity
(`EntityProfile.PLAYER`) is player-sized: 1.8 blocks tall and 0.6 wide. It
steps up 0.6 without jumping, jumps up to 1.25 and drops up to 3.

Blocks have collision **heights** (`BlockType`): air, partial blocks from
1/16 to 15/16 (carpet, slabs, snow layers, soul sand, dirt paths...),
bottom-half stairs, full blocks, 1.5-tall fences and walls, water, and
ladders and vines. Each cell has an **elevation**, the exact height the
feet rest at: on a slab the feet are in the slab's own cell at y + 0.5.
Water and ladders hold the entity up, so a cell of either is somewhere to
be (feet at y) even with nothing under it. Moves compare elevations:

| Move | Allowed when | Cost |
| --- | --- | --- |
| `WALK` | the neighbouring floor is within 0.6 up or down (flat ground, carpet, a slab, a stair step), with room for the body | 1 |
| `DIAGONAL` | the same, and both side columns are clear and not over a hazard | √2 |
| `JUMP_UP` | the neighbouring floor is 0.6 to 1.25 higher: a full block, but never a fence (1.5) | 2 |
| `DROP` | the neighbouring floor is more than 0.6 lower, within the drop limit | 1 + 0.5 per block fallen |
| `SWIM` | any move out of water (level, up onto a bank, down), a level move into water, or straight up into water | 2 per block of straight-line distance |
| `CLIMB` | straight up or down a ladder, or a level move onto or off a ladder with no floor under it | 1.5 per block |

Stairs have two floors: the low step (y + 0.5) in their own cell and the
top (y + 1), which counts as the cell above. Moving between them is a
`WALK` in place that costs 0.5. So a staircase of stairs or slabs is
climbed by walking, with no jumps.

Scaffolding is climbed like a ladder inside (up by jumping, down by
sneaking) and stood on from above like a full block.

Dropping into a pool is still a `DROP`, and stepping off a ladder's top
onto the ledge beside it is a `JUMP_UP`. Straight-line smoothing never
crosses water, and keeps every swim and climb step as its own waypoint.

Some blocks change how a cell is crossed, not just its shape. The search
moves as if each were its plain shape, and `TerrainCostModel` adds the
difference on top of the move's cost (`TerrainCosts`, on by default):

| Block | Moves like | Extra cost (default) |
| --- | --- | --- |
| Closed door, fence gate | air, if the entity opens doors (players do, zombies don't); otherwise a wall, or a 1.5 fence | +1 for stepping into the doorway |
| Soul sand, honey | a 14/16 or 15/16 floor | a move onto it costs 2.5 times as much |
| Cobweb | air | a move into it costs 4 times as much |
| Magma, berry bush | a full block / air | +5 for stepping onto or into it |
| Cactus | lava: never entered, stood on or brushed | |
| Powder snow | a wall that can't be stood on | |

Every slowdown is at least 1 and every extra at least 0, so the heuristic
stays admissible. Straight-line smoothing keeps off these blocks, since the
search priced the grid route and a line across soul sand could cost more.
`EntityProfile.opensDoors` says whether an entity opens doors;
`TerrainCosts.NONE` turns the extra costs off. `/goto` plays in adventure
mode, where doors can't be opened, so its player never opens them and
routes go around closed ones.

Every move costs at least its horizontal length, which keeps the
`OCTILE_XZ`, `SIXTEEN_XZ` and `MANHATTAN_XZ` heuristics admissible, so A* always returns
the cheapest path. `DefaultCostModel` refuses costs that break this.

## Path smoothing (`PathSmoother`)

A* paths go block by block. The smoother turns them into a few waypoints
joined by straight lines, which the executor lays its walk along:

```java
List<Waypoint> waypoints = finder.smoother().smooth(result.path());
// each Waypoint: pos() and kind(): START, STRAIGHT, JUMP_UP, DROP, SWIM or CLIMB
```

- **Flat stretches** are merged greedily into straight segments at any
  angle. Floors within 1/8 of a block of each other count as flat (carpet,
  dirt paths, soul sand); a slab or stair step ends a segment.
- **Jumps and drops** keep their exact takeoff and landing blocks.
- **Safety:** a straight segment is only used if `canWalkStraight` allows
  it. The entity is treated as a capsule of its width (default 0.6):
  - every cell under its centre line needs a floor and headroom;
  - every cell its body brushes must be clear and not above lava.
- **Length:** the result is never longer than the grid path.

On the terrain demo, 18 path points become 11 waypoints. The four stair
jumps and two drops are kept, and the flat parts become four straight
segments.

## The core

- **`AStarSearch`** is resumable: `step()` expands one node, and
  `run(maxExpansions)` is the per-tick budget. `bestSoFar()` returns the
  path to the node closest to the goal, for when a search is stopped early.
- **Node state** lives in flat arrays behind a hash table, with no object
  per node. The open set is a binary heap of node ids, sifted up in place
  when a node's cost drops.
  - Nodes are created as the search reaches them, so the world can be
    unbounded.
  - On the Dwarven Mines route (113k nodes expanded) it takes about 65 ms,
    against about 75 ms for the original one-object-per-node version,
    and makes about a third of the garbage collections.
  - The tests check that it matches that original exactly.
- **Positions** are packed into a `long` with the same layout as Minecraft's
  `BlockPos.asLong()`.
- **`SearchListener`** reports start, each expansion, nodes opened and
  improved, and finish. Events carry immutable `NodeView` copies; with
  `SearchListener.NONE` nothing is copied.
- **The move graph** (`NavGraph`, read through the core's `MoveGraph`):
  every move reachable from a start, worked out and priced once per map and
  kept in arrays, so a search reads its moves instead of checking blocks.
  After a block change only the columns it touched are worked out again.
- **Landmarks** (`Landmarks`, the ALT heuristic): exact costs from a few
  points round the map's edge to every cell and back, built once per map.
  The triangle inequality turns them into a heuristic that knows about
  walls and the long way round, so searches expand far fewer nodes and
  paths cost the same.

## What `/goto` uses

`GotoPathfinder.create` sets the rules the mod plans with, and the route
tool's `--goto` and `--exec` use the same:
- the player profile, with doors never opened (adventure mode);
- 16 headings and a turn cost of 0.1 per 45°, so open ground is crossed in
  fewer, gentler turns;
- drops cost 2 plus 1 per block fallen, twice the library default, so routes
  walk down stairs and slopes rather than step off ledges;
- climbing and swimming cost 20 per block, so routes go around ladders and
  water, which the executor doesn't do yet, unless there's no other way;
- steps against a wall or at a ledge cost 1.3 times as much (`--wall-cost`),
  so routes keep to the middle of the way;
- the move graph and 16 landmarks, built for the whole map once and kept in
  `nav.bin`.


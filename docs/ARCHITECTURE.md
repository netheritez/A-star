# Architecture

A custom A* pathfinder for a 3D block world, and a Minecraft mod (`/goto`)
that plans a player's movement along its paths and steers the player there
through the keys and camera. What each part does for a user is in the
[README](../README.md); the search rules are in [`PATHFINDING.md`](PATHFINDING.md).

Checklists of what's built and what's next are in
[`ROADMAP.md`](ROADMAP.md).

## The core rule

**The A\* search and the cost system stay pure and headless.** Everything else
lives in helpers and subsystems around them:

- **Validation**: which blocks an entity can stand in, jump to or drop to.
- **Tasks**: when searches run, for how long, and what happens when they time out.
- **Movement**: turning a path into velocity and jump input.

## Layers

```
core       A* search, min-heap, costs, heuristics.          depends on nothing
  ↑
pathing    World lookup, validation, move generation,        depends on core
           move graph, landmarks, smoothing, teleport hops
  ↑
adapters   mcworld (reads world saves), viz (Swing/Java2D)    depend on pathing

movement   Player inputs, scripts, traces, the physics       depends on pathing
           simulator, the execution plan, the executor
           (follower and aim), and the recovery (Journey)
client     Fabric 26.3 client mod (separate Gradle build)    compiles movement, mcworld,
                                                             pathing and core in
```

Each layer is a Gradle module, so the build refuses a dependency in the wrong
direction. **Only adapters may import Swing, AWT or Minecraft classes.**

| Module | Package | Contains |
| --- | --- | --- |
| `core` | `astar.core` | `AStarSearch` (with its array-based `NodeTable`), `MinHeap`, `MoveSource`, `CostModel`, `Heuristic`, `SearchListener` |
| `pathing` | `astar.pathing` | `BlockView`, `ArrayBlockView`, `EntityProfile`, `MoveValidator`, `BlockMoveSource`, `MoveCache`, `NavGraph`, `Landmarks`, `WorldPathfinder`, `PathAnalysis`, `PathSmoother`, the teleport planner (`WarpHops`, `TransmitHops`, `HopGraph`, `Flights`), and the experimental `HierarchicalPathfinder` (with `ClusterLayout`, `PortalGraph`, `ClusterDijkstra`) |
| `mcworld` | `astar.mcworld` | Reads Minecraft 1.18+ world saves (NBT, region files, chunk palettes), finds islands, maps blocks onto `BlockType` |
| `viz` | `astar.viz` | Renderer, Swing editor, console and PNG demos, island import UI, `route --plan` and `--exec` (depends on `mcworld` and `movement`) |
| `movement` | `astar.movement` | `Keys`, `Scenario` (input scripts), `trace` (the trace format, `TraceWriter`, `Trace.read`), `sim` (`SimulatedPlayer`, a copy of the game's player movement, and `TraceReplay`, which checks it against recorded traces), `plan` (`StraightRoute` lays the path along smoothed lines, `Curve` rounds its corners, `PlanBuilder` turns it into an `ExecutionPlan`), `exec` (`Executor`, `Follower`, `AimController`, the `PlayerController` seam, `Journey` for recovery, `GotoPathfinder` for /goto's search rules, and `SimRun`, which walks a whole plan in the simulator) |
| `client` | `astar.client` | Fabric client mod: `/goto` (`Navigator`, which copies the loaded blocks into a `LiveWorld`, plans off the game thread and plays the executor on the real player; `LiveMap` keeps the copy and its pathfinder between trips; `Places` saves chunks and recognises maps; `Warps`, `WarpCast` and `TransmitCast` for teleports; `RouteOverlay` draws the route) and the trace recorder (`/trace`). A separate build (`./gradlew -p client build`) so the main build never downloads Minecraft; it compiles `movement`'s, `mcworld`'s, `pathing`'s and `core`'s sources in directly |

## Coordinates

- **Axes:** `x` and `z` are horizontal, `y` is up, as in Minecraft.
- **A node is the block the entity's feet occupy.** An entity standing on
  the ground at `y = 0` has its node at `y = 1`.
- **Packed positions:** inside the search, a position is a single `long`
  (`Pos.pack(x, y, z)`) using the same layout as Minecraft's
  `BlockPos.asLong()`: x in the top 26 bits, z in the next 26, y in the
  low 12. That gives x and z within ±33,554,431 and y within −2048..2047, which
  covers the game's −64..319 build height.
- **`BlockPoint(x, y, z)`** is the readable form, used in results and in
  the listener.

## Core (`astar.core`)

The core knows nothing about blocks. It sees positions, the moves out of
each one, and what each move costs.

- **`AStarSearch`** is the search, written as a resumable object.
  - `step()` expands one node and returns `RUNNING`, `FOUND` or `NO_PATH`.
  - `run(maxExpansions)` runs up to that many steps. This is the tick budget.
  - `result()` returns the path, cost, open and closed sets, and nodes expanded.
  - `bestSoFar()` returns the path to the node closest to the goal so far,
    for timeouts.
  - `AStar.findPath(...)` runs a search to the end in one call.
- **Node storage** (`NodeTable`, internal) keeps every node's state in flat
  arrays: position, g, h, parent, move type, closed flag and heap slot. An
  open-addressing hash table maps positions to them.
  - There's no object per node, so large searches make little garbage.
  - Nodes are created the first time the search reaches them, and each
    search starts empty, so the world can be unbounded.
- **The open set** is a binary min-heap of node ids ordered by f, then h,
  with each entry's keys stored alongside it. Each node knows its heap
  slot, so a node whose cost drops is sifted up in place (decrease-key)
  and the heap never holds duplicates.
  - `MinHeap`, the generic version of the same heap, is kept as a utility.
  - The original one-object-per-node search is kept in the tests as
    `ReferenceAStarSearch`. The current search must match it exactly:
    results, tie-breaking and listener events.
- **`MoveSource`**, the seam to validation: `moves(from, sink)` emits each
  reachable neighbour and its `MoveType`. The core never asks whether a
  block is walkable.
- **`MoveType`** is `WALK`, `DIAGONAL`, `JUMP_UP`, `DROP`, `SWIM`,
  `CLIMB`, `WARP` or `TRANSMIT` (teleport hops, only in `HopGraph`). Doors are walked
  through: see `TerrainCostModel` below.
- **`CostModel`**: `cost(from, to, moveType)`. `DefaultCostModel`
  charges walk 1, diagonal √2, jump up 2, and drop 1 + 0.5 × fall height.
  A `WALK` that stays in the same column (between the low step and the top
  of stairs) costs `stepInPlace`, 0.5. It has no horizontal length, so it
  can't break the rule below. `SWIM` costs 2 and `CLIMB` 1.5 per block of
  straight-line distance between the two cells, which is never less than
  the horizontal length.
- **`Heuristic`**: `estimate(dx, dy, dz)` from absolute differences.
  `OCTILE_XZ` is for 8-way movement, `SIXTEEN_XZ` for 16-way,
  `MANHATTAN_XZ` for 4-way, and `ZERO` turns the search into Dijkstra.
- **`SearchListener`** lets tools watch the search. It reports start,
  each expansion, a node opened, a node improved (cheaper path found) and
  finish. Events carry `NodeView` copies, never live nodes. With the
  `NONE` listener nothing is copied, so observing costs nothing when
  unused.

### Why the heuristic stays admissible

A* returns the shortest path only if the heuristic never overestimates the
remaining cost. The rule that guarantees it:

> **Every move costs at least its horizontal length** (1 for a straight
> step, √2 for a diagonal, √5 for a 16-way step one across and two along).

`OCTILE_XZ` is the cheapest possible horizontal distance with 8-way moves,
so it can never overestimate when the rule holds. `DefaultCostModel`
refuses settings that break the rule. `SIXTEEN_XZ` is the same for 16-way
moves: with a the longer side and b the shorter, b long steps then the rest
straight when 2b ≤ a, otherwise a − b long steps and 2b − a diagonals.
It's exact on open ground, so it's also consistent. `MANHATTAN_XZ` is only admissible
without diagonal moves. Any new move type, cost or heuristic must keep this
property. The random-world tests compare A\* with Dijkstra and will fail if
it's broken.

## Pathing subsystems (`astar.pathing`)

- **`BlockView`**: the only way the pathfinder reads the world.
  `blockAt(x, y, z)` returns a `BlockType`, which is mainly a collision
  height (every shape is simplified to the full width of the block):
  - `AIR`: no collision.
  - `PARTIAL_1` ... `PARTIAL_15`: 1/16 to 15/16 of a block (carpet, bottom
    slabs, snow layers, soul sand, dirt paths). The feet stand *in* the
    cell, on top of it.
  - `STAIRS`: bottom-half stairs. Two floors: the low step (0.5) in the
    cell, and the top (1.0), which counts as the cell above.
  - `SOLID`: a full block.
  - `TALL`: 1.5 (fences, walls, closed gates). Its top half blocks the
    cell above, and it's too high to jump.
  - `HAZARD` (lava, fire): never passable or safe to stand on.
  - `VOID` (unloaded or outside the world): a solid barrier.
  - `WATER`: no collision; swum through, and holds the entity up.
  - `CLIMBABLE` (ladders, vines): no collision; climbed, and holds the
    entity up.

  - `SCAFFOLDING`: climbed like a ladder inside, stood on from above.
  - `DOOR`, `GATE` and the terrain types (soul sand, honey, cobweb, magma,
    berry bush, cactus, powder snow) move like a plain shape and are priced
    by `TerrainCostModel` (below).

  `ArrayBlockView` is an in-memory world for tests, the visualizer,
  imported maps and the mod: `/goto`'s `LiveWorld` copies the loaded blocks
  into one on the game thread.
- **`EntityProfile`** describes the entity: height (1.8), step height
  (0.6, climbed without jumping), jump height (1.25), maximum safe drop (3)
  and width (0.6). `PLAYER` is the default; `ZOMBIE` is 1.95 tall. The
  footprint fits in one block; entities wider than a block are future
  work.
- **`MoveValidator`** holds the movement rules, as pure checks over
  `BlockView` + `EntityProfile`. A node is the cell the feet are in, and
  its **elevation** is the exact height they rest at:
  - **Stand:** a floor in the cell (a partial block, stairs, or the top
    half of a fence below) or a full block, fence or stairs right under
    it; and nothing solid from the feet up to the head
    (`elevation + height`).
  - **Moves** go to every floor in a neighbouring column the body can
    reach, classified by the change in elevation Δ:
    - |Δ| ≤ step height: `WALK` (or `DIAGONAL`);
    - step height < Δ ≤ jump height: `JUMP_UP`. So a full block can be
      jumped, a fence can't;
    - Δ < −step height, within the maximum drop: `DROP`.
  - **Headroom:** both columns must be clear from each floor up to the
    higher elevation + height, the space the body sweeps.
  - **Diagonal:** only within the step height. Both side columns must be
    clear for the body, so it never clips a corner, and neither may sit
    over a hazard.
  - **Stairs:** an in-place `WALK` between the low step and the top.
  - **Water and ladders** hold the entity up: a `WATER` or `CLIMBABLE`
    cell is somewhere to be, feet at y, even with nothing below. Neither
    has collision.
    - Every move out of water is a `SWIM` (level, up onto a bank, or
      down), and so is a level move into water and a move straight up
      into a water cell. Dropping into a pool is a `DROP`.
    - Straight up or down between a ladder cell and the cell below it is
      a `CLIMB`, and so is a level move onto or off a ladder where there's
      no floor under it. Stepping off a ladder's top onto a ledge a block
      up is a `JUMP_UP`.
    - The stairs step wins over a ladder or water above the stairs.
  - **Shapes:** every rule reads a block as its `BlockType.shape` for
    the entity, one of the plain kinds above. A `DOOR` or `GATE` is air
    for an entity that opens doors and a wall or 1.5 fence otherwise; soul
    sand and honey are 14/16 and 15/16 floors, cobwebs and berry bushes
    air, magma solid, cactus a hazard and powder snow a barrier with no
    top. So the move rules never need to know about them.
- **`BlockMoveSource`** implements the core's `MoveSource` using
  the validator, with 4-way, 8-way or 16-way movement. 16-way adds level
  steps one across and two along (headings about 27° and 63° off the
  axes), typed `DIAGONAL` and priced at √5/√2 of a diagonal, wherever
  `canWalkStraight` says the body can walk the line. They're opt-in
  (`new WorldPathfinder(world, profile, 16)`); the hierarchical search
  stays 8-way.
- **`MoveCache`** wraps a `MoveSource` over an `ArrayBlockView` and
  remembers each cell's moves the first time they're asked for, one int
  per move (the step of up to two columns, the rise or fall, and the type). `WorldPathfinder`
  always uses one on array worlds. It pays off wherever cells are expanded
  again: repeat searches, and above all the hierarchical search, whose
  cluster Dijkstras go over the same cells once per portal. Cells with no
  moves aren't kept. Pages of 16 × 16 columns are locked per lookup, so
  the portal graph's parallel build can share it. `ArrayBlockView.version()`
  changes on every block set, and the cache starts again when it does.
- **`TerrainCostModel`** wraps a `CostModel` and prices what the shapes
  leave out, from the blocks where a move ends: times `slow` (2.5) onto
  soul sand or honey, times `web` (4) into a cobweb, `+door` (1) into a
  doorway from outside one, and `+damage` (5) onto magma or into a berry
  bush. Slowdowns are at least 1 and extras at least 0, so every move
  still costs at least its horizontal length. Only the end is read, since
  reading both ends made the Mines search about 17% slower; this way it's
  within noise.
- **`WorldPathfinder`** ties a `BlockView`, profile, cost model, terrain
  costs and heuristic together. It checks that the start and goal can be stood on before
  searching.
- **`PathAnalysis`** finds a path's key nodes: the start, the goal, and
  every point where the direction or move type changes.
- **`PathSmoother`** (Phase 3) turns a path into `Waypoint`s joined by
  straight lines. Each waypoint says how to reach it: `STRAIGHT`,
  `JUMP_UP`, `DROP`, `SWIM` or `CLIMB`.
  - Flat stretches are merged greedily: from each waypoint, it takes the
    furthest later step still reachable by
    `MoveValidator.canWalkStraight`.
  - Jumps and drops keep their exact takeoff and landing blocks, and every
    swim and climb step is kept.
  - `canWalkStraight` treats the body as a capsule, the centre line
    widened by half the entity's width:
    - every cell the centre line crosses needs a floor (not water, nor a
      ladder with nothing under it) and headroom, all
      within 1/8 of a block of each other (carpet and dirt paths are
      level enough; a slab or stair step ends the segment);
    - every other cell the body overlaps must be clear and not above
      lava, or a floor at about the same height.
  - For a single grid step, that is exactly the walk and diagonal rules.
  - The worst case is O(n²) line checks, which is fine at today's path
    lengths. It's a candidate for optimisation later.
- **`NavGraph`**: every move reachable from a seed cell, worked out and
  priced once (on all cores) and kept in flat arrays, as the core's
  `MoveGraph`. Searches read moves from it instead of checking blocks. After
  block changes, `patch()` works out only the changed columns again.
- **`Landmarks`** (ALT): exact costs from 16 landmarks round the map's edge
  to every cell and back, from Dijkstras over the move graph. Each search
  uses the 4 that say most about its start, and the triangle inequality
  bounds the rest of the route. Admissible, so paths cost the same;
  `TurnLandmarks` adds tables per heading for exact turn costs.
- **Teleports** (`/goto warp`, `it`, `aotv`):
  - `WarpHops` finds every *sure* etherwarp over the move graph: from
    anchors (at most one per 4-block box), every jittered ray from the eye
    (eye height, ±0.3 sideways, aim ±0.25 on the face) must land on the
    same block's top, within 57 blocks and at least 35 away.
  - `TransmitHops` does the same for Instant Transmission: the eye moves
    along the view up to 12 blocks and stops short of the first block;
    only casts that land on a floor for every jittered view are kept, and
    the fall after is simulated.
  - `HopGraph` merges the walking moves with the hops into one graph, with
    extra costs for teleports and walking that don't head for the goal, and
    an admissible rate for the heuristic.
  - `Flights` runs after that search: from the backward cost-to-goal it
    beam-searches chains of casts through the air (each cast with its own
    view, turns limited as a player's), three searches with different
    spreads in parallel, and keeps a flight where it saves time over the
    rest of the trip.
  - `HopFile` writes the hops beside the map's saved chunks (`warps.bin`,
    `casts.bin`), keyed by the world's checksum and the rules' version.
- **`HierarchicalPathfinder`** (HPA\*, experimental: only the route tool's
  `--hpa` uses it, and on the Mines the move graph with landmarks is faster
  at the median and always finds the cheapest path). It
  wraps a `WorldPathfinder` and reuses the core's `AStarSearch` unchanged;
  nothing in `core` knows about it.
  - **`ClusterLayout`** splits the world into square columns, 16 × 16
    blocks and the full height by default (`--cluster n`). Every move goes
    to a neighbouring column, so it leaves its cluster only from the
    cluster's edge, and swims and climbs up and down never cross one.
  - **`PortalGraph`** is built once per world, entity and cost model:
    - every move out of a cluster-edge cell that ends in another cluster
      is a *transition*;
    - transitions between the same two clusters are grouped into
      *entrances*: two are in one entrance when their starts are the same
      cell or one move apart both ways, and so are their ends. A cave
      floor and a ledge above it stay separate;
    - each entrance keeps its middle transition, plus both ends when it's
      more than 6 columns wide. Their cells are the *portals*;
    - a `ClusterDijkstra` from each portal, which never leaves its
      cluster, prices an edge to every other portal of the cluster it can
      reach. It keeps its state in arrays indexed within the cluster, and
      the clusters run in parallel.
    - Edges are directed, since drops are one-way. No route is lost: any
      path through a transition can use its entrance's kept one instead.
  - **A query** inserts the start and goal with cluster Dijkstras, runs
    `AStarSearch` over a `QueryGraph` (the portal graph plus this query's
    start and goal edges, as a `MoveSource` and `CostModel`), then turns
    each hop back into real steps: a move between clusters is one step,
    a hop inside one is the cluster Dijkstra's path. The heuristic stays
    admissible on the portal graph because every edge is made of real
    moves.
  - It finds a path exactly when `WorldPathfinder` does. The path can cost
    a little more than the cheapest, since it passes through portals.
  - **Short routes** skip the hierarchy: placing the start and goal costs
    about the same at any distance, which dwarfs a short plain search. When
    the goal is within `shortRange` blocks by the heuristic (4 clusters by
    default), a plain `AStarSearch` runs first for up to `shortBudget`
    expansions (32 per block of range). If it finishes, its answer (the
    cheapest path, or no path) is returned; if not, the hierarchical query
    runs as above, so a bad guess costs at most the budget.
  - Not yet: tick budgets (each stage is resumable, but a stepped search
    waits for `PathService`), and rebuilding only the clusters a block
    change touches (it waits for `PathInvalidator`).

### Not built

- **`PathService`** (search lifecycle: queue, per-tick budget, timeouts,
  cancellation) and **`PathInvalidator`** (replan on block changes) were
  planned for searches on the server thread. `/goto` doesn't need them:
  it plans on a background thread and its recovery (`Journey`,
  `LiveRouter`) re-reads blocks ahead and replans when they change.

## Execution

Planning answers *which way*; execution makes a **player** actually go that
way, tick by tick, in a world that may not match the plan. The full design
plan (aim, input timing, physics, recovery, testing) is published at
https://claude.ai/artifact/MmqjcJWbuiR4ePwphmeUkn; this is the summary.

A player has two kinds of input: the camera (yaw and pitch, moved by the
mouse every frame) and keys (W A S D, jump, sneak, sprint, use), read once
per tick. Movement direction is the yaw plus the WASD direction, and sprint
only works with W held, so **the yaw is the steering wheel**. The executor
never sets velocity.

```
Route ─▶ ExecutionPlan ─▶ Follower ─▶ primitives ─▶ intent (yaw, pitch, keys)
                                                        │
                              aim controller + key driver ─▶ PlayerController ─▶ SimulatedPlayer / Fabric client
   ▲                                                                                  │
   └──── recovery (rejoin, local fix, replan) ◀── monitor (predicted vs observed) ◀───┘
```

- **`ExecutionPlan`**: continuous segments built from the grid path and the
  waypoints, with real floor heights, a corridor (how far the body may
  drift), hazard sides, doors (found in the grid path) and a speed profile.
- **Follower**: pure pursuit. It aims at a point a lookahead distance
  ahead on the path (longer when sprinting, shorter near corners, jumps,
  doors and the goal) and picks walk, sprint, coast or sneak to stay under
  the speed profile. Built in M4: each tick it predicts the next tick's
  move for each gait (what's left of the velocity plus the gait's push)
  and takes the fastest that stays under the limit and can still slide to
  a stop on the goal. The gaits are sprint, walk, coast (forward and back
  held together) and brake (back); it never sneaks, as a player walking
  doesn't. It can strafe, and follows the route along smoothed straight
  lines with clothoid curves at the corners (`StraightRoute`, `Curve`).
- **Primitives**: walk, corner, jump up, drop, arrive (climb and swim are
  not built; routes avoid ladders and water, and doors are never opened).
  Each has a precondition, its inputs and a success check. Built in M5 for
  jumps and drops, as part of the follower rather than takeoff tables: it
  runs the game's air physics forward (push, move, drag, gravity) to
  predict where a jump started this tick, or a fall off an edge, would
  land. It jumps on the first tick the landing clears the top's edge, once
  lined up with the jump (or when pressed against it, or standing at a
  gap's edge), and walks off a drop no faster than lets the fall land
  before the room below runs out (where the route turns or ends). In the
  air it faces the landing and presses forward, nothing or back to come
  down inside that room. It doesn't aim past a jump's top or a drop's
  landing until it's there, and tells apart a route that doubles back
  under itself by height. On the ground it aims along a drop's line only
  once the aim is on the drop, and in the air a jump up steers for its top.
- **Aim controller**: a critically damped, rate-capped turn toward the
  target yaw and pitch, run every frame and fed through the game's mouse
  path, with a small hand-like drift (`/goto hand`). It anticipates curves so the smoothing lag doesn't become heading
  error. Built in M4: 25 rad/s, at most 540°/s, in whole mouse counts
  (0.15° at the default sensitivity), three frames per tick.
- **Monitor and recovery**: each tick the simulator predicts the next
  state; a large difference means a push, a lag-back or wrong block data.
  Off course, no progress, snagged, a wrong landing or an invalid path lead
  to the cheapest fix first: rejoin the plan, a local fix, a short local
  replan, a full replan, or a safe stop.
- **`PlayerController`** is the only seam to the game: `SimulatedPlayer` in
  tests, the Fabric client in game.

Everything is built and tested against `SimulatedPlayer` first, calibrated
with traces recorded in the real game ([`TRACES.md`](TRACES.md)), then run
on the Dwarven Mines save in singleplayer. `route --exec --pairs 200`
(stretches stuck: 0) and `--disturb` are the checks after executor changes;
see [`TOOLS.md`](TOOLS.md).

**Teleports** are played by `WarpCast` (stop, sneak, ease the aim onto the
target, check the crosshair, right-click the Aspect of the Void in the
hotbar) and `TransmitCast` (line up with small key taps, re-aim from the
real feet, click; single casts on the move, chains clicked the tick after
each teleport shows). A cast that doesn't land as planned bans that hop and
plans again.

The checklist for this work is in [`ROADMAP.md`](ROADMAP.md#executor-after-planning).

## Adapters

- **`viz`**: a diagnostic visualizer.
  - The 3D world is drawn as **one panel per Y level**, top-down, with x
    across and z down.
  - Each panel shows cells, walls, gaps (air with nothing below), hazards,
    the closed and open sets, the path, key nodes, ▲ / ▼ markers where
    the path jumps up or drops, and optionally the smoothed route.
  - `VizMain` writes PNGs, and opens Swing windows when a display exists.
  - `EditorApp` (Phase 2.5) is interactive. It edits blocks at a chosen
    level, lets you drag the start and goal, replays the search step by
    step from a `SearchRecorder`, and inspects g/h/f/parent at the current
    step. Its logic is in the Swing-free `EditorModel`, so it's tested
    headless.
- **`mcworld`**: imports islands from a Minecraft world save (1.18+
  format, such as 1.21.x), so long real routes can be tested without
  running the game.
  - It has its own readers for NBT and region files (zlib, gzip,
    uncompressed and external `.mcc` chunks; LZ4 is refused with a
    clear message), and for paletted chunk sections.
  - It reads a world folder or a `.zip` in place, through Java's zip file
    system. It finds every overworld `region` folder inside, skipping
    `DIM-1`, `DIM1` and `dimensions`.
  - An island is a group of non-empty chunks that touch. `WorldImporter`
    copies one island's bounding box, plus a margin and headroom, into
    an `ArrayBlockView`, and records the in-game origin.
  - `BlockMapping` maps block states by collision height, by name:
    - no-collision blocks become air;
    - bottom slabs, carpet, snow layers, soul sand, dirt paths, chests,
      beds and so on become partial blocks; bottom-half stairs become
      `STAIRS`; top slabs and upside-down stairs are solid;
    - fences and walls are `TALL`;
    - closed doors and fence gates are `DOOR` and `GATE` (iron doors are
      solid); open doors, trapdoors and gates are air; closed bottom
      trapdoors are 3/16;
    - water, seagrass, kelp, bubble columns and waterlogged plants are
      `WATER`; ladders and vines are `CLIMBABLE`; scaffolding is
      `SCAFFOLDING`;
    - lava and fire are hazards; soul sand, honey, cobwebs, magma, berry
      bushes, cactus and powder snow have types of their own;
    - everything else is solid.
  - `BlockShapes` holds the real collision shape, friction and speed of
    every block state that isn't a plain cube or empty (stairs, slabs,
    fences, walls, ice, soul sand...), from a table the client mod writes
    out of the game (`-Pastar.shapes.dump=<file>`, `ShapeDump`). The
    importer notes each such cell's shape in `ImportedShapes`, keyed with
    the `BlockType` it imported, so an edited cell falls back to its type.
    The pathfinder still searches on `BlockType`; the executor's plan and
    simulator (`BlockTypeWorld`) use the real shapes.
  - `sealRoofIfCapped` fills the air above an island whose top layer
    covers at least 80% of its columns, so routes stay inside closed
    maps such as the Dwarven Mines.
  - It's tested against a world written by an independent NBT library
    (`tools/make_test_world.py`, using nbtlib).
- **`client`**: the Fabric mod. `LiveWorld` classifies the game's block
  states into `BlockType` the same way as the importer, and keeps their real
  shapes for the planner; the player controller carries out the executor's
  intents through the keyboard input and the camera.

## Target platform: Fabric, Minecraft 26.3

- **Toolchain:** Minecraft 26.3 (the target since 2026-09-24, when it was the
  newest release; the recorder and simulator were first built on 1.21.6)
  needs Java 25 and ships unobfuscated, so mods use Mojang's names and no
  mappings. Only the `client` build applies Fabric Loom and compiles for
  Java 25; `core`, `pathing` and `movement` stay plain Java 21 libraries that
  the mod compiles in.
- **Moving the player:** the executor drives the client's own player with
  camera turns and movement keys (`/goto` swaps the keyboard input for the
  recorder's `ScriptedInput` and turns the camera through the mouse path
  each frame), so vanilla physics and collision still apply.

## Threading

The game thread only copies blocks (`LiveWorld`, and each loaded chunk for
`Places`) and plays the executor one tick at a time. Searches, plan
building, the move graph, landmarks and teleport hops all run on background
threads, reading the copy, never the live world:
- **Warm-up:** while you play, `LiveMap` keeps a copy of the blocks around
  the player with its move graph and landmarks, and `WalkWarmUp` runs the
  planner once so the JVM has compiled it before the first trip.
- **Whole maps:** once `Places` knows the map, a copy of the whole of it
  with its move graph and landmarks is built (or read from `nav.bin`) in
  the background.
- **Parallel work:** the move graph floods a ring at a time on all cores,
  and `Flights` runs its three searches in parallel.
- **Saving chunks** matches places and writes files on a thread of its own.

## Roadmap

| Phase | Work | Where |
| --- | --- | --- |
| 1 ✓ | Headless A*, min-heap, heuristics | `core` |
| 2 ✓ | Diagnostic PNG renders | `viz` |
| 2.5 ✓ | Interactive visualizer: editing, playback, inspection | `viz` |
| 3 ✓ | Path smoothing into waypoints | `pathing` |
| 3.5 ✓ | Richer block model: collision heights, terrain, water, ladders | `pathing` |
| 4 ✓ | Execution: plan, follower, aim, recovery, the Fabric mod | `movement`, `client` |
| ✓ | Move graph, landmarks, teleports | `pathing`, `client` |
| later | Ladders and water in the executor, starfall meteors | see [`ROADMAP.md`](ROADMAP.md) |

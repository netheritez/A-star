# Roadmap and checklists

What's built, what's next, and what's been noted for later. The design
behind each item is in [`ARCHITECTURE.md`](ARCHITECTURE.md). Tick items
here as they land.

## Release

- [x] Docs: a README for players (install, commands, teleports, saved
  maps), and `PATHFINDING.md` and `TOOLS.md` for the library and the tools.
- [x] License: PolyForm Noncommercial 1.0.0 (`LICENSE`, also in the mod's
  metadata): free to use and change, not to sell.
- [x] Version: 0.1.0.
- [x] No GitHub releases: `main` is the release. Anyone builds the mod from
  it with `./gradlew -p client build` (see the README).
- [ ] Make the repository public.

## Path smoothing (Phase 3)

**Built:**
- [x] Greedy string pulling over flat stretches (`PathSmoother`)
- [x] Width-aware straight-line check (`MoveValidator.canWalkStraight`):
  the centre line needs a floor, the body mustn't touch walls or lava
- [x] Jumps and drops keep their exact takeoff and landing blocks
- [x] Never longer than the grid path; checked by an independent sampler
  on random 3D worlds
- [x] Shown in the visualizer, with a toggle in the editor

**Later:**
- [ ] **Faster line checks.** Today each check scans the segment's
  bounding box, and smoothing is O(n²) checks in the worst case. Walking
  only the cells the capsule touches, and caching results per anchor,
  would fix both for long Minecraft paths.
- [ ] **Cost-aware smoothing.** Smoothing only asks whether a straight
  line is *safe*, not whether it's *cheap*. For now lines simply keep off
  water and every block with a terrain cost (the threshold option). Comparing
  a segment's cost with the grid route it replaces would let a line cross
  soul sand where the path already does.
- [x] **Partial-height blocks.** A straight segment stays on floors within
  1/8 of a block of each other (carpet, dirt paths), and stops at each
  slab or stair step.
- [ ] **Segments that follow heights.** A line up a long slab or stairs
  staircase is one waypoint per step today. It could be one segment that
  rises with the floor.
- [ ] **Run-up before jumps.** The takeoff waypoint is exact, but the
  segment leading into it may arrive at an awkward angle. The executor
  may want the last stretch lined up with the jump direction.
- [ ] **Any-angle search (Theta\*)** as an alternative to smoothing
  afterwards. It's only worth it if smoothed paths prove noticeably
  worse than optimal any-angle ones.

## Testing on real maps

- [x] **Import islands from 1.21 world saves** (`mcworld`). Each island is
  found automatically. Start and goal are picked far apart for a long
  route. In-game coordinates are shown in the inspector.
- [x] **The `islands` command** lists a world's islands and renders each one's
  longest route to a PNG.
- [x] **Big maps:** one byte per cell, and imports of up to 200 million cells.
  `./gradlew route` draws an overview, a height profile and level
  close-ups. The route picker stays inside the largest round-trip area.
  Capped maps get their roofs sealed. Tried on the Hypixel Dwarven Mines:
  a 909-step route in about a tenth of a second.
- [x] **Replay on big maps.** A recording indexes its events once (each
  cell's touches, and its node and open/closed state after each), and a
  frame is a read-only view of the last touch before its step. On the
  Mines' flat-heuristic search (129k steps, 336k events), a playback frame
  takes about 0.07 ms instead of about 35 ms, with frames identical to the
  old replay; indexing costs about as much as one old full frame, once per
  search.
- [ ] **The editor on big maps, later:** a cap on what's recorded (every
  event is still kept, about 20 bytes each once indexed).
- [x] **Zipped worlds** open directly: a world folder inside the zip,
  `level.dat` at the top level, or several worlds in one zip. Nether and
  End folders are skipped.
- [x] **Editor:** **Open world...**, zoom, and a log-scale speed slider from 1
  to 10,000 steps per second.
- [x] **Seeing big maps whole:** the editor's view from above (grey with
  the route coloured by height, or floors coloured by height), zoom from
  1/8 to 64 pixels a block, Fit, Ctrl + wheel zoom and right-drag panning.
  Imported maps open from above, fitted to the window.
- [x] **Saved map:** the last world opened is remembered, and `edit`,
  `islands` and `route` use it when given no path. With nothing saved,
  they use the Dwarven Mines in `maps/dwarven-mines.zip`.
- [ ] **Import from other formats:** Litematica, WorldEdit `.schem` and
  structure `.nbt` files, if they turn out to be useful.
- [ ] **LZ4-compressed regions** (`region-file-compression=lz4`). They're
  refused with a message for now.
- [ ] **Benchmark on the largest islands:** search time and memory, and
  replay cost per frame.

## Block model (before Phase 4)

One step per PR, for a player-sized entity (1.8 tall, 0.6 wide, step 0.6,
jump 1.25).

- [x] **Collision heights** (step 1).
  - Standing height per block, in sixteenths: slab 0.5, stairs 0.5 and 1,
    carpet 1/16, snow layers, soul sand, dirt paths...
  - A 0.6 step-up, so slabs and stairs are walked, not jumped.
  - Fences, walls and closed gates are 1.5, so they can't be jumped.
  - The visualizer shades each kind, the editor paints slabs, stairs and
    fences, and the inspector shows where the feet rest.
  - On the Dwarven Mines route, jumps went from 62 to 12. With the jump
    cost raised to 4 it takes 1, where before most jumps were forced by
    stairs imported as full blocks.
- [x] **Faster move generation, part 1.** Scanning each neighbouring column
  for floors made the Mines search about twice as slow. Now each column is
  scanned once per move: the first cell that isn't air ends the scan (nothing
  below it can be reached), every block is read once, and one "free top"
  scan answers both the headroom and the move's ceiling. Block reads per
  expansion fell from 193 to 73 (41 before step 1), and the search is about
  28% faster. `MoveEquivalenceTest` checks it against the direct version on
  600 random worlds.
- [x] **Faster move generation, part 2.** `MoveCache` remembers each cell's
  moves (an int per move, about 30 bytes plus 4 per move per cell), so a
  cell is worked out once however often it's expanded. `WorldPathfinder`
  uses it on array worlds; a block change drops it. On the Dwarven Mines:
  - the far route searched again: 85 ms instead of 130–140 ms, with 8.5 MB
    of moves kept;
  - hierarchical queries (200 random routes): 3.9 ms median instead of
    7.4–7.9 ms, since placing the goal runs a cluster Dijkstra per portal
    over the same cells (placing now takes 1.2 ms instead of 3.0 ms); the
    graph build is unchanged (about 1 s) and leaves 15 MB of moves;
  - the cost: the first search over new ground is about 16% slower
    (160 ms instead of 135–140 ms) while it fills the cache.
  Tried and dropped: caching each cell's headroom above its floor (a byte
  per cell): no gain on a first search and 12% on a repeat, since the
  neighbour scans and corner checks remain. Earlier: copying whole
  columns up front (reads cells the scan never needs) and storing columns
  contiguously (no measurable gain).
- [ ] **Move cache, later:** a size cap (it grows with the ground
  searched), and dropping only the pages a block change touches.
- [x] **The whole-map move graph** (`pathing.NavGraph`, `core.MoveGraph`).
  Every move reachable from the start is worked out and priced once, and
  searches read it from plain arrays: cells as dense ids, so no hashing and
  no move rules or pricing per node. `WorldPathfinder` builds it on the
  first search over an array world and keeps it; after an edit the next
  search builds it again (in the background, searching as before
  meanwhile, when the last build took over 60 ms). Landmarks are built over
  the same graph. Same nodes, same order, same paths (`NavGraphTest`
  checks every result against the old way on 600 random worlds). On the
  Mines (136k cells, 870k moves, built in about 150–300 ms, 27 MB):
  - the long route, default settings: 11 ms instead of 25 ms;
  - with no landmarks: 52 ms instead of 123 ms;
  - with exact turns: 80 ms instead of 123 ms;
  - 16-way: 10 ms instead of 15 ms (the build is 730 ms there, knight
    moves).
  `route --no-graph` searches the old way.
- [x] **Patch the move graph after an edit** (`NavGraph.patch`). The world
  logs which columns changed; cells within 3 columns of them are worked out
  again, new reachable cells are flooded in, and the rest is copied. On the
  Mines a one-block edit patches in about 10–50 ms (sometimes 150–200 ms)
  instead of a 300 ms rebuild. Big edits (over a quarter of the cells) or
  unknown changes still rebuild. Landmarks keep their cells and redo their
  tables on the patched graph in the background: about 0.6–1 s instead of
  about 2 s. `NavGraphTest` and `LandmarksTest` check patched graphs and
  rebuilt landmarks against fresh ones after random edits.
- [x] **Half the landmark memory.** Cell tables are stored as floats,
  rounded so the bounds stay consistent (`CompactDistances`): 16 landmarks
  on the Mines take 17.4 MB instead of 34.8 MB, same nodes. Turn tables
  stay doubles (floats there cost exact-turn searches more nodes).
- [ ] **Move graph, later:** drop the move cache where the graph covers
  everything; landmarks' per-node look-up by cell id.
- [x] **Water and climbing** (step 2).
  - New block types `WATER` and `CLIMBABLE`, and move types `SWIM` and
    `CLIMB` in core (costs 2 and 1.5 per block, both admissible).
  - Water and ladders hold the entity up: a cell of either is a node even
    over a drop. Every move out of water is a swim; a level move into it
    is too, while dropping into a pool is still a drop. Ladders are
    climbed straight up and down, and stepped onto or off sideways; their
    top is left with a jump onto the ledge beside it.
  - Smoothing keeps every swim and climb step, and straight lines never
    cross water.
  - Imports map water, kelp, seagrass and waterlogged plants to water, and
    ladders and vines to climbable. The editor paints both.
  - Random test worlds get pools and ladders, and `MoveEquivalenceTest`
    checks them against the direct version.
- [ ] **Water and climbing, later:**
  - a fall into water deep enough is safe from any height (today the drop
    limit applies);
  - scaffolding: climbed inside, stood on top (it's solid for now);
  - flowing water pushes, and bubble columns lift or pull down;
  - mobs that can't swim or climb (an `EntityProfile` flag).
- [x] **Doors and terrain costs** (step 3).
  - New block types, appended: `DOOR`, `GATE`, `SOUL_SAND`, `HONEY`,
    `COBWEB`, `MAGMA`, `BERRY_BUSH`, `CACTUS`, `POWDER_SNOW`. Each moves
    like a plain shape (`BlockType.shape`), so the move rules are unchanged.
  - Doors and fence gates open for entities that open doors
    (`EntityProfile.opensDoors`: players yes, zombies no); for the rest
    they're a wall or a 1.5 fence. Iron doors import as solid.
  - `TerrainCostModel` on top of any cost model: slow ground multiplies a
    move's cost, doors and damage add to it. On by default; `TerrainCosts.NONE`
    turns it off. `route` takes `--door-cost`, `--slow-cost`,
    `--web-cost` and `--damage-cost`.
  - Cactus is a hazard (never entered or brushed), powder snow a barrier,
    and lily pads a 1/16 floor over water.
  - Straight lines keep off all of these.
  - The editor paints doors, soul sand, cobwebs and magma. Random test
    worlds get them too; `MoveEquivalenceTest` covers them for an entity
    that opens doors and one that doesn't.
- [ ] **Doors and terrain, later:**
  - opening trapdoors (a hatch over a ladder); mobs never do, so they
    keep their closed shape for now;
  - zombies breaking doors on Hard, and iron doors opened by a button;
  - leather boots, which let you walk on powder snow;
  - honey's lower jump, and falls onto honey or into cobwebs being safe;
  - pricing by the whole cell a move sweeps, not only where it ends.
- [x] **Terrain costs** in `CostModel`: done in step 3, above.
- [x] **Hazards to brush, not just stand on:** cactus is now a hazard.
  Berry bushes hurt only when walked into, so they're priced instead.
- [ ] **`McBlockView`** (Fabric 26.3): classify each block by its
  collision shape and fluid state, not by a list of block names, so
  modded blocks work.
- [ ] **Replace `mcworld`'s name-based `BlockMapping` approximations** with
  the same model. Heights, water and ladders are mapped by name now.

## Executor (after planning)

A plan isn't movement. Something has to *carry out* the waypoints tick by
tick, watch what actually happens, and react when reality doesn't match
the plan. The design is under **Execution** in `ARCHITECTURE.md`.

> **Warning: steering is the hard part.** Search is a closed problem: the
> world holds still, and optimality can be proved. Steering fights
> physics we don't fully control:
> - momentum, and friction that changes with the block underneath (ice,
>   soul sand, slime);
> - fixed jump arcs, where a tick too early or late means clipping the
>   ledge or losing speed against it;
> - box collision and step-up snagging on corners;
> - vanilla's own move and jump controls, turn-rate limits, other AI
>   goals, knockback, water currents, mobs pushing each other, and only
>   20 ticks a second.
>
> Failures show up only in context: doorways, stairs, lava edges, crowds.
> So:
> 1. **Measure before building.** First build a simulator that copies
>    Minecraft's per-tick physics, and check it against traces recorded
>    in the game.
> 2. **Build small movement primitives, each tested on its own.**
> 3. **Put noticing failure and recovering ahead of perfect steering.**
> 4. **Drive the player through its own inputs (camera and keys), never
>    by setting velocity directly.**
> 5. **Build replay for movement from day one.**

The executor drives a **player**: camera turns and movement keys, never
velocity. The design plan, agreed on 2026-09-24, is at
https://claude.ai/artifact/MmqjcJWbuiR4ePwphmeUkn and summarised under
**Execution** in `ARCHITECTURE.md`. Decisions: Fabric 26.3 (1.21.6 until the
same day, when the newest release became the target); singleplayer and
private servers (nothing designed to get past anticheat); smooth,
rate-capped aim that faces the path; any movement key or mouse move pauses
it; stop within 0.3 blocks of the goal; sprint where the corridor allows;
walking, corners, jumps, drops and ladders first, then water and doors.

One PR per milestone:

- [x] **M1: trace recorder.** A Fabric 1.21.6 client mod (`client/`, its own
  Gradle build) records inputs, movement and nearby block shapes every
  tick, while you play or from a script (`/trace play sprint-jump`). The
  format and scripts are in `movement` and described in
  [`TRACES.md`](TRACES.md).
- [x] **M2: `SimulatedPlayer`**, a port of the player movement code (now
  26.3's)
  (`movement/.../sim/`): ground and air acceleration, friction by block,
  gravity, jumps, sprint and sneak rules, sneaking at edges, step-up and
  box collision against recorded shapes. 18 traces recorded on a flat test
  course (walls, steps, a slab, ice, soul sand, a ledge) replay through it
  bit for bit, and are test fixtures. Water, ladders and doors report
  themselves as not modelled yet (M6).
- [x] **M3: execution plan builder** (`movement/.../plan/`): real floor
  heights, the room on each side (measured by moving the player's box
  sideways; wall, edge to fall off, or open), doors, straight runs and
  special moves, and a speed profile carried back along the route by how
  fast the player can slow down. `route --plan` summarises it for a map.
- [x] **M4: follower and aim controller** (`movement/.../exec/`). Pure
  pursuit on the plan, steering with the yaw alone; four gaits (sprint,
  walk, sneak, coast) picked each tick so the next tick's move stays under
  the plan's speed limit and the player can still slide to a stop on the
  goal (within 0.3 blocks). The aim is a critically damped, rate-capped
  turn run every frame, moved in whole mouse counts, that leads a steadily
  turning target. It stopped in front of moves it couldn't do yet. `route
  --exec` walks a route in the simulator: on the Mines, 4,475 stretches of
  400 random routes all reach their end.
- [x] **In-game accuracy check**: `route --exec --script` writes the
  executor's simulated runs as scripts that teleport to their start and
  replay the keys and camera turns; the recorder mod plays them in the
  Mines save, and `route --exec --score` measures how far the real player
  drifted from the simulated one and from the route. On the long Mines
  route the simulator replays every trace exactly on the recorded blocks;
  9 of 14 stretches match the simulated run to the bit, and the others
  part where the pathfinder's simplified blocks differ from the real ones
  (stairs, a fence, slabs), by up to 5 blocks on one stairway.
- [x] **Real block shapes for planning**: import collision shapes (stairs,
  fences, slabs as they are) so the plan and the simulator see the blocks
  the game does. The client mod dumps every block state's shape from the
  game into a table the importer reads; the plan moves nodes off cell
  centres to get the body past stair backs, and marks a walk up the back
  of a stair as a jump. Each step of the plan is checked by walking the
  body along it, so staircases that turn under a low ceiling climb onto
  a stair's back half before turning.
- [x] **No standing on thin or hollow blocks.** Nothing stands on a fence,
  wall or gate any more (a thin post: the body slips off between posts),
  and a block more than a step high whose real top wouldn't hold a body
  up anywhere on the cell is `THIN`, a barrier nothing stands on: glass
  panes, iron and copper bars, cauldrons, hoppers, composters, lanterns,
  chains, lightning rods, end rods, bells, brewing stands, amethyst,
  bamboo, pointed dripstone and so on (`BlockMapping.refine`, from the
  shape table on import and from the game in the mod). The two Mines
  routes that got stuck (a walk across fence tops, a cauldron at the goal)
  now arrive, and 200 simulated routes walk with none stuck (was 2). Of
  600, 2 still stick: a diagonal jump that falls short into a one-deep
  hole, and sneaking at a goal half a step down a stair.
- [x] **M5: jumps and drops.** The executor now goes on through jumps up
  and drops, and stops only at ladders and water (and getting on or off
  them). The follower predicts each jump's and fall's landing from the
  game's air physics: it jumps when the landing clears the top, slows
  before a drop so the fall lands before the route turns, and steers in
  the air with forward, nothing or back. On the Mines, 1,800 random routes
  (1,000 of any length and 400 each within 30 and 128 blocks) take 3,382
  stretches with 39,000 jumps and drops, and 3 get stuck, at two spots:
  a jump the grid sees as one block that the real shapes make 1.5 (a stair
  above a slab), and a walk along the tops of a fence. Played in the real
  game, the long route (5 stretches, 12 jumps up and 9 drops), a staircase of
  11 jumps and a chain of drops match the simulator exactly (0.000 drift)
  and stop within 0.3 blocks of their ends.
- [ ] **M6: ladders, water and doors**, with their physics added to the
  simulator and calibrated.
- [x] **M7: monitor, recovery and replanning.** `Journey` watches over the
  executor. Each tick it predicts the player with the simulator and
  compares, so a push or a lag-back shows the moment it happens. Every few
  ticks it checks the next 24 steps can still be walked, so a block placed
  on the path or a door shut across it is seen before the player gets
  there. Off the route, moved, or blocked, it first finds a short way back
  onto the route a few steps ahead, and only then plans the whole way again;
  a step it got stuck on is treated as a wall. `route --exec --pairs 300
  --disturb` walks 300 random Mines routes while pushing the player every
  few seconds (3,567 pushes), lagging it back and placing a block ahead:
  210 arrive, 77 stop in front of a ladder or water as before, and 11 fail,
  8 of them pushed off an edge into a pit with no way out that doesn't
  climb. In the game, a clean route has no false alarms (the prediction
  matches exactly), and a teleport back to the start and a wall placed
  ahead are both handled on the way to the goal. Doors are never opened:
  the target is adventure mode. Movement replay in the visualizer is left
  for later.
- [x] **M8: the player controller in the client.** `/goto x y z` copies the
  loaded blocks around the way, finds the path and plans off the game
  thread, then drives the real player live, tick by tick. Any key or mouse
  turn pauses it; routes steer clear of ladders and water; on getting stuck
  it plans again around the step it couldn't reach. On the Mines save, four
  routes of 136 to 292 blocks arrive, one after a replan around a stair
  corner the simulator also gets stuck on. (The tick-by-tick comparison
  with the simulator is the in-game check under M5.) Camera turns are set
  once per tick for now; smoothing them per frame comes later.

## Also noted

- [ ] **`PathService` (tasks):** a request queue, a per-tick budget, a node
  limit, timeouts, cancellation, and a fallback to `bestSoFar()`.
- [ ] **`PathInvalidator`:** block-change events that touch the active path
  or a running search's closed set → replan.
- [x] **16 directions.** Level steps one across and two along, so routes
  can head at about 27° and 63° as well as along the axes and diagonals.
  `/goto` uses them. On 300 random Mines routes: 1.8% shorter (median),
  turns of 45° or more down from 20.7 to 11.7 per 100 blocks, the executor
  walks all of them (0 failed stretches), and with pushes, lag-backs and
  placed blocks 213 arrive against 210 (6 failed against 11). Searches take
  about 25% longer. `RouteTool --16way` tries it. Any angle comes later.
- [x] **Fewer zigzags** (the grid part: see the turn cost below; 0.1 per
  45° is now the default everywhere, `/goto` included). `/goto` walks the grid path, which zigzags across
  open ground where many paths cost the same. Change the weights (for
  example a small cost for each change of direction) so straight runs win.
- [x] **A heuristic that knows about height** (`route --height-heuristic`,
  `DefaultCostModel.heightAwareHeuristic`). It adds the cheapest cost per
  block up or down that no move undercuts, so it stays consistent. With the
  default costs that is little: a walk up a slab or a stair costs the same
  as a level one, so height only counts when the goal is further up or
  down than it is away. 300 random Mines routes: 0.3% fewer nodes, best
  route 8.4%. The terrain demo's goal is at the start's height, so no
  estimate from the two ends alone can see the hill in between.
- [x] **A heuristic that knows the map** (landmarks, ALT; `route --landmarks
  n`, `pathing.Landmarks`): exact costs to and from a few landmarks, worked
  out once, bound the rest by the triangle inequality. 16 landmarks on the
  Mines: built in about 1 s, 38 MB; 300 random routes search 70% fewer
  nodes (83% with no turn cost; the long route 128,065 -> 960). Falls back
  to the flat heuristic after an edit.
- [x] **Landmarks by default** in the route tool (16) and the editor
  (`pathing.AutoLandmarks`): built when a search first needs them, and again
  after edits or when a search starts somewhere they don't cover; builds
  slower than 60 ms run in the background while searches use the flat
  heuristic.
- [x] **Landmarks and a kept move graph in `/goto`** (`client.LiveMap`,
  `pathing.KeptPathfinder`). The copy of the world is kept from one trip to
  the next while trips fit in it (a new copy takes every loaded chunk around
  the player), and read again for changes on each trip, so only changed
  blocks patch the graph. The first trip searches as before while the move
  graph and 16 landmarks are built in the background; every later trip
  reuses them. `route --goto n` times it: 200 trips in a row in the Mines,
  each within 150 blocks, 96 ms per plan before (median; a new graph on a
  copy around each trip) and 3.1 ms now, first plan 85 ms, landmarks ready
  1.4 s later. 45 of the 200 trips had no path inside the old copy's
  margins and now do.
- [ ] **Landmarks tighter under turn costs**, which the landmark costs
  leave out.
- [x] **Array-based node storage.** No object per node, identical results.
  Measured on the Dwarven Mines route (113k nodes): about 65 ms against
  about 75 ms before, and about a third of the garbage collections.
  - The earlier 120–230 ms figures were benchmark noise: the Gradle
    background process competing for CPU, and GC pauses.
  - A cache-friendly brick-layout hash was tried; it was slower with
    linear probing, so it was reverted.
- [x] **Faster move generation**: see parts 1 and 2 under the block model.
- [x] **Hierarchical A\* (HPA\*) for long routes** (`route --hpa`). A
  cross-map route explored nearly the whole walkable area, so no heuristic
  tuning could help. With portals between 16 × 16 columns, the Mines route
  takes 16 ms instead of 154 ms (20k nodes instead of 126k), for a path 3%
  dearer. Over 100 random routes it's 3.3× faster at the median. The graph
  takes about 1 s to build, once per map. **Experimental since the move
  graph and landmarks:** those find the cheapest path and are faster at the
  median (hierarchical is 0.8x as fast), so `/goto` doesn't use it and it
  stays a route tool option (checked 2026-09-30).
  - [ ] Rebuild only the clusters a block change touches (with
    `PathInvalidator`).
  - [ ] A stepped hierarchical search for tick budgets (with `PathService`).
  - [ ] Place the goal faster: today it runs one cluster Dijkstra per portal
    of the goal's cluster, which is what makes 32-block clusters slow.
  - [ ] Shorter paths: a final plain A\* limited to the clusters on the
    portal path, if the 3% matters.
  - [x] Plain A\* for short routes: within 4 clusters it runs first, with a
    2,048-expansion budget. Routes under 32 steps went from 30× slower
    than plain A\* to as fast, with the cheapest path; 32 to 127 steps
    from 0.3× to 0.8×.
  - [ ] Medium routes (32 to 255 steps) are still no faster than plain A\*:
    placing the start and goal is the floor (about 4 ms). Placing the
    goal faster (below) would help most.
  - [ ] Show clusters and portals in the editor.
- [x] **Tuning from the command line:** `route --bench n` gives steady timings
  (Dwarven Mines route: a median of about 62 ms), and `--compare` with
  `--jump-cost` and the other weights runs two settings side by side.
  Doubling the jump cost there barely changed the route (61 jumps against
  62), because those jumps are forced: stairs import as full blocks. The
  block model is the real fix.
- [x] **Step costs and weights side by side** (`route --sweep`, and
  Straight / Diagonal / Weight in the editor, which also shows every path
  node and the key nodes from above). Any step cost above 0 works: the
  heuristic is scaled to the cheapest straight and diagonal moves, so A\*
  stays exact. On 200 random Mines routes, against the defaults (1 and
  1.414): a diagonal of 1 walks 2.4% further with 21% more turns and 7%
  more nodes expanded; 1.6 changes almost nothing; 2 walks 3.4% further
  for 11% fewer nodes. A heuristic weight of 1.5 expands 34% fewer nodes
  for 4% dearer paths with 28% more turns; 3 expands 58% fewer for 17%
  dearer paths.
- [x] **Turn cost** (`route --turn-cost`, Turn in the editor): a cost per
  45° of heading change, seen from above, charged from each node's parent,
  so diagonal runs count as straight. On 200 random Mines routes: 0.05
  cuts turns per 100 blocks from 18.8 to 12.6 at no extra length; 0.25 to
  10.5 for 0.4% dearer paths and 5% more nodes; 1 to 8.1 for 1.9%
  dearer. Plain A\* only: HPA\* ignores it. By default a node keeps
  whichever parent reached it most cheaply, so the path is not always the
  cheapest counting turns. `--exact-turns` (Exact in the editor) keeps a
  node per heading and is exact: on the long Mines route 952.0 instead of
  952.3, but 200 random routes search 4.8x the nodes and take about 5x as
  long (long route 12.7 -> 112 ms with landmarks), so it stays off.
  Since the landmarks count turns (below), it is on by default.
- [x] **Landmarks that count turns** (`pathing.TurnLandmarks`). Over the
  move graph's states (a cell and the heading it was entered by, 808k on
  the Mines), with each move priced with its turn, the landmark bounds
  hold with turns included, and stay consistent over exact-turn search
  nodes (`ExactTurnCostTest` checks both on random worlds). Built for the
  first 4 landmarks when the pathfinder charges exact turn costs: 0.6 s and
  57 MB more on the Mines. Long route: 52,506 -> 939 nodes, 60 -> 2.6 ms,
  cost 952.0 (exact). Tried as the default, then turned back off: on 300
  random Mines routes exact turns take a median 22.8 ms (p90 93 ms)
  against 5.2 ms (p90 14 ms) approximate, and 16-way 35 ms against
  5.8 ms. `--exact-turns` (Exact in the editor) keeps it.
  - 2 turn landmarks instead of 4: the same long route at 48 MB instead of
    74 MB, but 24% more nodes on random routes.
  - 8 or 16 turn landmarks: no fewer nodes (988), more memory.
  - 16-way: 807 nodes, but still about 10 ms (more headings per cell).
- [x] **Faster 16-way.** Each move's heading is stored with the move
  graph, and the turn cost is read from a heading-by-heading table instead
  of working the turn out from positions (`TurningCostModel.turnCostByHeading`).
  Same paths and nodes. 300 random Mines routes (warm, best of 3): 16-way
  total 1941 -> 1774 ms, median 4.1 -> 3.7 ms, now within about 10% of
  8-way (3.6 ms). 8-way and exact turns: no change beyond noise. What's
  left per node is the open list (about 27%) and the landmark look-ups
  (about 22%). The long-route 11 ms from before was mostly noise and
  turn landmarks still building in the background.
- [x] **Faster smoothing.** `canWalkStraight` checks only the strip of
  cells along the line instead of its bounding box, and clips without
  allocating: the long Mines route smooths in 2.4 ms instead of 10.7 ms,
  to the same waypoints.
- [x] **Diagonal jumps and drops.** Jumping up and dropping down go
  diagonally too, when both side columns are clear for the body over the
  whole rise or fall (the same corner rule as diagonal walks); they cost
  the jump, or the drop's base, times 1.414. On 300 random Mines routes,
  without them 2 routes aren't found at all, and the rest cost 0.0% more at
  the median and 1.4% at p95 (`EntityProfile.diagonalLeaps`,
  `route --no-diagonal-leaps` turns them off).
  - [x] Teach the executor diagonal jumps and drops. A diagonal jump lands as
    far onto the block as a straight one (not just over its corner, which a
    glass pane doesn't fill), and a jump after sneaking up to the edge
    counts the sneak's slower push. `/goto` and `route --exec` use them: on
    200 simulated Mines routes, 13,255 s of game time instead of 13,463 s,
    and no route fails because of them.
- [x] **Keep off walls and ledges** (`route --wall-cost`, Wall in the
  editor, `pathing.Clearance`). Routes hugged every wall and cut every
  corner as close as the body allows, which no player does. Now a step
  ending against a wall or at a ledge (no floor within a block of its
  height next to it) costs 1.3 times as much, one block in 1.15, and
  smoothing never pulls a line closer to a wall than the steps it
  replaces. On 200 random Mines routes: steps at an edge 24% -> 11%,
  smoothed lines at an edge 19% -> 7%, for 1.8% longer routes; walked by
  the executor, 0 failures, 23% fewer jumps, 5% less camera turning.
  Building the Mines move graph takes about 0.5 s instead of 0.3 s. On
  by default in the route tool, the editor and `/goto`; the library's
  `TerrainCosts.DEFAULT` leaves it off.
- [x] **`/goto` past render distance** (`client.Places`, `SavedChunk`,
  `SavedView`). Chunks are kept per place as the game loads and unloads
  them (`.minecraft/astar/places/`), and a place is recognised again by
  its blocks; the Dwarven Mines map comes with the mod as a place of its
  own. When there's no way round in what's loaded, `/goto` finds the whole
  way over the saved chunks and walks it a stretch at a time. In the Mines,
  168, 202, 283 to -168, 206, 86 (no way within the 433 x 294 blocks read
  before): three stretches walked, about 500 blocks, then it stops in front
  of a ladder. A route that stepped onto a ladder without a climb move no
  longer counts the ladder's board as a floor, so it stops there instead of
  sliding down and giving up.
- [x] **Weighted A\* as a tunable option** (`--heuristic-weight`) (3× weight: 25% fewer expansions
  on the Mines route, for an 11% longer path).

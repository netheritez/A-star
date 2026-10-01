# Developer tools

The editor, the image renderers, map import and the `route` command, for
looking at searches and checking changes without starting the game. How the
search works is in [`PATHFINDING.md`](PATHFINDING.md); trace recording for
the executor's physics is in [`TRACES.md`](TRACES.md).

Use `.\gradlew.bat` instead of `./gradlew` in Windows PowerShell, and quote
Gradle properties that have dots in them (`"-Pastar.goto=10,64,10"`).

```bash
./gradlew edit     # interactive editor: edit the world, replay the search step by step
./gradlew islands --args="<world folder or .zip>"   # list islands, render their longest routes
./gradlew route --args="<world or .zip> [--island n] [--from x,y,z --to x,y,z]"   # draw a route on a big map
# Leave the path out to use the last map you opened, or else the Dwarven Mines in maps/.
./gradlew run      # console demo: flat 4-way and 8-way maps, plus a 3D terrain route
./gradlew viz      # renders build/viz/*.png, opens windows if a display exists
./gradlew test     # all modules
```

## Visualizer

One top-down panel per Y level (x across, z down):

| Colour | Meaning |
| --- | --- |
| Light grey / dark grey | Standable cell / solid block at this level |
| Tan / brown / dark brown | A slab, carpet or other low block / stairs / a fence or wall (and its top half, in the level above) |
| White | Gap: air with no floor below |
| Red-orange | Hazard (lava), or the air right above it |
| Light blue / olive | Water / a ladder or vine |
| Purple / grey-brown / orange | A door or gate / slow ground (soul sand, honey, cobweb) / ground that hurts (magma, berry bush) |
| Blue / light green | Closed set (expanded) / open set (frontier) |
| Dark blue | Replay only: the node being expanded at this step |
| Amber cells + line | The path; a segment that changes level appears in both panels |
| Red dots | Key nodes, where direction or move type changes |
| Magenta line + squares | The smoothed route and its waypoints (toggle with **Smoothed** in the editor) |
| ▲ teal / ▼ blue | Arrived here by jumping up / by dropping down |
| Green / purple | Start / goal |

## Interactive editor (`./gradlew edit`)

A Swing window with three parts: the map in the middle, two rows of
controls on top, and an inspector on the right. Every edit re-runs the
search and records it, so the whole search can be replayed.

The map shows either one panel per level, or the **whole map from
above**: each column's highest walkable floor, in grey with the route
coloured by height, or with the floors themselves coloured by height.
Imported maps open from above, zoomed to fit the window.

| Control | What it does |
| --- | --- |
| Click or drag on a cell | Paints the selected tool (**Solid**, **Air**, **Lava**, **Slab**, **Stairs**, **Fence**, **Water**, **Ladder**, **Door**, **Soul sand**, **Cobweb**, **Magma**) at *that exact level*. A door needs painting at the feet level and the one above. A 2-high wall needs the feet level and the one above. The inspector shows where the feet rest in a cell ("feet at +0.5" on a slab). |
| Drag the S or G marker | Moves the start or goal, to any level shown. From above, it lands on the highest floor of the column. |
| `\|◀` `◀` `Play` `▶` `▶\|` | First step, step back, play/pause, step forward, last step. Keys: Home, ←, Space, →, End |
| Speed slider | Playback speed, 1–10,000 steps per second (logarithmic) |
| Zoom, Fit | Pixels per block, from 1/8 (eight blocks a pixel) to 64. **Fit** (Ctrl+0) zooms so the whole view fits the window. Ctrl + mouse wheel zooms around the pointer, and dragging with the right or middle button pans. |
| Open world... | Import an island from a Minecraft world save (see below) |
| 8-way, Heuristic | Diagonal moves on or off; the heuristic: landmarks (the default, which knows the map and rebuilds itself after edits: at once on small worlds, in the background on big ones), flat (octile / Manhattan), or none (Dijkstra) |
| Smoothed | Shows or hides the smoothed route on the finished path |
| Nodes | From above: shows every path node as a dark dot (from 4 px per block) and the key nodes (where the direction or the kind of move changes) as red rings. The status line counts the key nodes. |
| Straight, Diagonal, Weight | The cost of one block straight and one block diagonally (defaults 1 and 1.414; any value above 0), and the heuristic weight (1 finds the cheapest path, more searches fewer nodes) |
| Turn, Exact | Cost added for every 45° the path turns (default 0.1, 0 = off). Straight runs, along the axes or the diagonals, then beat zigzags. Exact (off by default) keeps a node per heading, so the path is the cheapest counting turns; the landmarks then count turns too. It is fastest on the long Mines route but about 4x slower on typical routes. |
| View combo | The whole map from above (grey, or coloured by height), every level the search reached, or a single Y level |
| World combo, Reset | Switch between the terrain and flat demos; discard edits |

The inspector shows the hovered cell's block, the block below it, whether
it can be stood on, and its g, h, f, parent and move type **at the current
step**. It also shows "not reached yet" when the search hasn't got there,
and a status line (path found, no path, or which endpoint can't be stood
on).

All behaviour lives in `EditorModel`, which has no Swing in it. The Swing
classes (`EditorPanel`, `ControlsBar`, `InspectorPanel`, `EditorApp`) only
forward input and repaint. Replay is built from `SearchRecorder` and
`Recording.frame(k)`. While a replay runs, the node being expanded is dark
blue and the best path to it so far is dashed.

On a machine with no display, run it under a virtual one:
`xvfb-run -a ./gradlew edit --args="--script --screenshot build/viz/editor.png"`
runs a short scripted session and saves the window as a PNG.

## Loading your own maps

Islands from a Minecraft **1.21.x world save** can be loaded straight into
the editor, from the world folder or from a **`.zip`** of it. There's no
need to unzip; any of these zip layouts works:
- the world folder inside the zip;
- `level.dat` at the top level;
- several worlds in one zip.

Nether and End folders are skipped.

- **In the editor:** press **Open world...** and choose the world folder
  (the one with `level.dat`) or the `.zip`. If it has several islands,
  pick one from the list.
- **From the command line:**
  ```powershell
  .\gradlew.bat edit --args="--world C:\path\to\saves\MyIsland --island 1"
  .\gradlew.bat islands --args="C:\path\to\MyIsland.zip"
  ```
- **Your saved map:** the last world opened by any of these (or by
  `route`) is remembered in `%USERPROFILE%\.astar\settings.properties`
  (`~/.astar/` elsewhere). `edit`, `islands` and `route` open it when no
  path is given; `edit --args="--demo"` starts on the demos instead.
  Opening another world replaces it; deleting the file forgets it.
- **The built-in map:** with nothing saved, they open the Hypixel Dwarven
  Mines, which ships in the repo as `maps/dwarven-mines.zip` (6 MB). So
  `.\gradlew.bat edit` and `.\gradlew.bat route` work on a fresh clone.
  `islands` lists every island, largest first, with its size and
  in-game coordinates. It also writes a PNG of each island's longest
  route to `build\viz\islands\`.

How it works:
- An island is a group of touching chunks with blocks in them, which
  suits skyblock worlds, where islands float in void.
- The start and goal are set far apart automatically, so you get a long
  route at once.
- The inspector shows each cell's in-game coordinates next to its local
  ones.

Blocks are mapped by collision height, simplified to the full width of
the block:
- bottom slabs are 0.5, carpet 1/16, snow 2/16 per layer above the first,
  soul sand and chests 14/16, dirt paths and farmland 15/16;
- bottom-half stairs are stairs; top slabs and upside-down stairs are full
  blocks;
- fences and walls are 1.5, so they can't be jumped;
- closed doors and fence gates are `DOOR` and `GATE` (iron doors, which
  need redstone, are solid); open ones are air. Closed trapdoors keep
  their shape (a 3/16 floor, or solid at the top of the cell); open ones
  are air;
- soul sand, honey, cobwebs, magma, berry bushes (past their first
  stage), cactus and powder snow have types of their own; lily pads are
  a 1/16 floor, so they're walked across;
- water, and seagrass, kelp, bubble columns and waterlogged plants, are
  water; ladders and vines (cave, twisting and weeping too) are
  climbable; scaffolding stays solid for now; flowers and torches are air.

Worlds saved before 1.18, or with LZ4 region compression, are refused
with a message.

## Big maps: `./gradlew route`

For maps too big to draw one panel per level (the Hypixel Dwarven Mines is
576 × 256 × 502 blocks), the `route` command finds a route and draws it
four ways into `build/viz/route/`:
- **`overview.png`:** the map from above, showing each column's highest
  walkable floor, with the route coloured by height and its jumps and
  drops marked.
- **`heights.png`:** the same, with the floors coloured by height and the
  route in white.
- **`profile.png`:** height against distance travelled.
- **`levels.png`:** close-ups of the six levels the route uses most, with
  in-game y.

```powershell
.\gradlew.bat route --args="C:\path\to\map.zip"
.\gradlew.bat route            # the saved map, once one has been opened
.\gradlew.bat route --args="--from 168,202,283 --to -168,207,86"
```

**Speed and weights:**
```powershell
.\gradlew.bat route --args="'C:\path\to\map.zip' --bench 20"
.\gradlew.bat route --args="'C:\path\to\map.zip' --compare --jump-cost 4 --drop-per-block 1"
```
- **`--bench n`:** runs the same search `n` times after warming up, and
  prints the median, fastest and slowest search time in milliseconds,
  the smoothing time, and nodes expanded per millisecond. The single
  timing printed without it is one cold run.
- **Weights:** `--walk-cost`, `--diagonal-cost`, `--jump-cost`,
  `--drop-cost`, `--drop-per-block`, `--swim-cost`, `--climb-cost`
  (defaults 1, 1.414, 2, 1, 0.5, 2 and 1.5), and for terrain
  `--door-cost`, `--slow-cost`, `--web-cost`, `--damage-cost` (defaults
  1, 2.5, 4 and 5).
  Any move cost above 0 works, including a diagonal cheaper than 1.414:
  the heuristic is scaled to the cheapest straight and diagonal moves, so
  A\* still returns the cheapest path for those costs.
- **`--no-diagonal-leaps`:** jumps up and drops down only along x or z,
  as the executor needs (`--exec` does this by itself). By default they
  can go diagonally too, where both corners are clear.
- **`--landmarks n`:** a heuristic that knows the map (ALT), on by default
  with 16 (`--landmarks 0` turns it off; off with `--hpa`). It picks `n`
  landmarks round the edges of the map, works out the exact cost from each to
  every cell and back once (about 1.6 s and 17 MB for 16 on the Mines), and
  bounds the rest of each route by the triangle inequality, so walls, hills
  and the long way round count. Paths cost the same. Each search uses the 4
  landmarks that say most about its start. 300 random Mines routes, 16
  landmarks: 70% fewer nodes and about 2x faster (median) with the default
  turn cost; with `--turn-cost 0`, 83% fewer and 3.5x faster, and the long
  route searches 960 nodes instead of 128,065. Very short routes can be a bit
  slower. Plain A\* only.
- **`--no-graph`:** plain A\* works out each node's moves as it goes. By
  default it searches the move graph: every move reachable from the start,
  worked out and priced once per map (about 150–300 ms and 27 MB on the
  Mines, again after an edit), then read from arrays. Same paths; the long
  Mines route searches in 11 ms instead of 25 ms.
- **`--height-heuristic`:** the heuristic also counts the cheapest cost of
  climbing or coming down to the goal's height. Paths cost the same. With
  the default costs it only helps when the goal is further up or down than
  it is away (a walk up a slab or a stair costs the same as a level one):
  on 300 random Mines routes it expands 0.3% fewer nodes.
- **`--exact-turns` / `--approx-turns`** (the default)**:** with a turn
  cost, `--exact-turns` keeps a search node per heading (the step into each
  cell), so the path is the cheapest counting turns. The first 4 landmarks
  then also get tables per heading that count turns (`TurnLandmarks`, about
  57 MB and 0.6 s more to build on the Mines). It suits the long Mines route
  (939 nodes, 2.9 ms, cost 952.0, against 9,908 nodes and 7.6 ms), but on
  300 random Mines routes it is slower: median 22.8 ms against 5.2 ms (p90
  93 ms against 14 ms), so approximate turns stay the default: one node per
  cell, each turn charged from the cell's cheapest way in (costs within a
  few tenths of a block of exact).
- **`--turn-cost t`:** adds `t` for every 45° the heading turns, seen
  from above (a right angle costs `2t`, a 16-way bend about `0.6t`).
  Diagonal runs count as straight, so among paths of about the same
  length the one with the fewest, gentlest turns wins. The default is 0.1
  (0 with `--hpa`, which can't use it); `--turn-cost 0` turns it off.
  Plain A\* only. `/goto` uses 0.1 as well.
- **`--wall-cost w`:** a step that ends against a wall or at a ledge (two
  or more blocks up or down right next to it) costs `1 + w` times as much,
  one block further in `1 + w/2` times, so routes keep to the middle of
  corridors and a step back from drops, as a player walks. Straightened
  lines never pass closer to a wall than the steps they replace. The
  default is 0.3 (`/goto` and the editor's Wall box too); `--wall-cost 0`
  walks as tight as it can.
- **`--heuristic-weight w`:** weighted A\*. Faster, but a path can cost up to
  `w` times the cheapest.
- **`--compare`:** also runs the default settings on the same endpoints.
  It prints steps, blocks walked, jumps, drops, swims, climbs, doors, nodes expanded and time
  side by side (with both paths priced under the default weights), and
  writes both sets of images (`*-default.png`, `*-custom.png`).
- **`--sweep [w/d/h[/t],...]`:** searches the same route with each straight
  cost `w`, diagonal cost `d`, heuristic weight `h` and turn cost `t`, prints how the
  paths differ (blocks walked, key nodes, turns, cost at default prices,
  nodes expanded), and draws them side by side: `sweep-overview.png` (the
  whole map) and `sweep-closeup.png` (where the paths differ most, with
  every node and key node). With no list it compares `1/1.414/1`,
  `1/1/1`, `1/1.6/1`, `1/2/1`, `1/1.414/1.5` and `1/1.414/3`. Add
  `--pairs n` to compare them over `n` random routes too:
  ```powershell
  .\gradlew.bat route --args="--sweep --pairs 200"
  .\gradlew.bat route --args="--sweep 1/1.414/1,1/1.2/1,1/1.414/2"
  .\gradlew.bat route --args="--sweep 1/1.414/1/0,1/1.414/1/0.1,1/1.414/1/0.5 --pairs 200"
  ```
- **`--plan`:** also builds the route executor's plan for the route and
  summarises it: straight runs, jumps, drops and climbs, doors, how much of
  the way has an edge to fall off beside it, the narrowest spot, and how much
  can be sprinted. On the Dwarven Mines route, 93% of the way can be
  sprinted, and the narrowest spot leaves the body 0.2 blocks either side.
- **`--exec`:** also walks the plan with the executor in the movement
  simulator, stretch by stretch. It walks, jumps up and drops; ladders and
  water aren't done by the executor yet, so the player is put down past
  them. It prints how many stretches reached their end, how far the player
  got from the route, and where any stretch got stuck. With `--pairs n`
  (and optionally `--pairs-within n`), it walks that many random routes
  instead of timing them, and counts the jumps and drops done. The plan
  and the simulator see stairs, slabs and fences with their real shapes.
  On the Mines, every stretch of the long route and all 336 stretches of
  200 random routes (`--exec --pairs 200`) reach their end. With `--disturb`, the random routes are walked with
  the recovery on while the player is pushed, lagged back and walled off
  (see M7 in the roadmap). `--speed-effect n` gives that player Speed n:
  `/goto` reads the player's real movement speed, so the plan's speed limits,
  the follower's pushes and the check for being pushed all go by it. At
  Speed VII (2.4x) on 200 Mines routes with pushes, trips take 32% less game
  time than before this, and none fail (3 did).

- **Endpoints:** without `--from` and `--to`, it picks two far-apart points
  in the largest area where you can walk there *and back*. Drops are
  one-way, so it avoids pits you can't climb out of.
- **Closed maps:** if an island is capped by a solid top layer, as the
  Dwarven Mines is at y = 255, the roof is sealed on import so routes stay
  inside.
- **Speed:** the Dwarven Mines imports in about 25 s. A 914-step route
  across it takes 0.2 s to find.
- **Memory:** the Gradle tasks ask for a 4 GB heap.

**Teleports** (what `/goto warp`, `/goto it` and `/goto aotv` plan with):
```powershell
.\gradlew.bat route --args="--warp --transmit"
.\gradlew.bat route --args="--transmit --no-diagonal-leaps --pairs 100"
```
- **`--warp`:** also finds the route with etherwarp hops: every sure hop
  over the move graph (every jittered ray from the eye lands on the target
  block), and prints the route walking only and with hops, with where each
  hop aims. It writes `teleports.png`: the height map cropped to the route,
  walking in white, etherwarps in magenta and Instant Transmission in
  orange with a dot per cast, numbered.
- **`--transmit`:** the same with Instant Transmission casts, including air
  chains (casts from mid-air, each with its own view). With `--warp` too,
  both. `--transmit-min m` leaves out casts landing closer than `m` blocks
  to walking (default 10).
- **`--warp-range r`, `--warp-cost c`, `--warp-min m`, `--warp-spacing s`,
  `--warp-turn t`:** etherwarp reach from the eye (57), what a hop costs in
  blocks walked (5), the shortest hop kept (35), at most one anchor per
  `s`-block box (4), and turning onto or off a hop per 45° (1).
- **`--hop-detour d`, `--ether-length l`, `--walk-detour w`:** what a
  teleport pays per block that doesn't bring it nearer the goal (0.5),
  etherwarps shorter than `l` blocks paying 0.15 per block short (40), and
  what walking pays per block that doesn't (0.25). These keep trips headed
  for the goal instead of wandering.
- With `--pairs n`, both print how much quicker the trips are over `n`
  random routes, and a fingerprint of all the routes, which stays the same
  as long as the planner's choices do.

**Other options:** `--4way`, `--16way`, `--out dir`, `--goto n` (times `n`
`/goto` plans one after another), `--grid-steps`, `--script dir` and
`--score dir` (see [`TRACES.md`](TRACES.md)). The full list with defaults is
at the top of `viz/src/main/java/astar/viz/RouteTool.java`.

## Experimental: hierarchical A\* (`--hpa`)

Hierarchical A\* is kept as a route tool option to experiment with. `/goto`
doesn't use it: the default search (the move graph with landmarks) finds the
cheapest path and, on the Mines, is faster at the median (hierarchical takes
about 1.25 times as long), so there's no reason to. The numbers below were
measured against plain A\* before the move graph and landmarks existed.

It splits the map into 16 × 16 columns
(`--cluster n` for another size), finds the cells where moves cross
between them once per map, then searches between those portals and fills
in the steps. With `--compare`, the default column is plain A\*. With
`--bench`, it also times building the portal graph and each stage of the
search. `--pairs n` times `n` random routes (the same ones every time)
both ways and prints the speed-up and the extra path cost by route length.
On the Dwarven Mines, with 16-block clusters:

| | Plain A\* | Hierarchical |
| --- | --- | --- |
| Far-apart route: search (median of 15) | 154 ms | 16 ms |
| Far-apart route: nodes expanded | 126,363 | 20,387 |
| Far-apart route: cost | 944.2 | 972.9 (+3.0%) |
| 100 random routes: speed-up, median | | 3.3× (4.1× for 256+ steps) |
| 100 random routes: extra cost, median | | 3.3% |
| Portal graph, built once | | about 1 s, 19,242 portals, 2.9 MB |

8-block clusters were faster on the random routes (4.0×) but cost more
(3.7%); 32-block ones were slower (1.1×), mostly in placing the start
and goal.

**Short routes use plain A\*.** Placing the start and goal costs about
4 ms whatever the distance, so on short routes the hierarchy was up to
30× slower than plain A\*, and its detours through portals cost up to 70%
more. When the goal is within 4 clusters (64 blocks), plain A\* runs
first with a budget of 2,048 expansions (about what a hierarchical query
costs); if it doesn't finish, the hierarchy takes over. `--short-range n`
and `--short-budget n` change these (`--short-range 0` turns it off), and
`--pairs-within n` picks random routes with goals within `n` blocks.
300 such routes on the Dwarven Mines, speed-up over plain A\* (median):

| Route length | Routes | Before | After | Extra cost, before → after |
| --- | --- | --- | --- | --- |
| under 32 steps | 94 | 0.03× | 1.1× | 0% (p95 73%) → 0% |
| 32 to 127 steps | 97 | 0.3× | 0.8× | 3.3% → 1.4% |
| 128 to 255 steps | 63 | 1.9× | 1.2× | 3.4% → 3.3% |
| 256 steps or more | 46 | 4.8× | 4.5× | 3.0% → 3.0% |
| total search time | | 1,586 ms | 1,362 ms | |

Routes of 128 to 255 steps pay for the tries that run out of budget.
Far-apart random routes are unchanged (3.9× before, 3.7× after, within
run-to-run noise).

```powershell
.\gradlew.bat route --args="--hpa --compare"
.\gradlew.bat route --args="--hpa --compare --bench 15 --pairs 100"
.\gradlew.bat route --args="--hpa --cluster 8 --compare --bench 15 --pairs 100"
.\gradlew.bat route --args="--hpa --pairs 300 --pairs-within 128"
.\gradlew.bat route --args="--hpa --pairs 300 --pairs-within 128 --short-range 0"
```

## Tests

- **Core:**
  - A* matches an independent Dijkstra on 300 random grids.
  - Stepping a search to the end gives the same result as running it in one call.
  - The run budget is respected.
  - Listener events are consistent, and attaching a listener never changes the result.
  - Packed positions round-trip across the full Minecraft range.
- **Pathing:**
  - One test per movement rule.
  - The staircase, cliff and ledge routes.
  - The flat port reproduces the original 2D costs (15 and 14.414).
  - A* matches Dijkstra on 300 random 3D worlds with hills, lava and overhangs.
  - Smoothing on those same worlds:
    - waypoints stay on the raw path;
    - the result is never longer than the raw path;
    - an independent sampling checker walks every straight segment in
      0.02-block steps, confirming the centre always has a floor and the
      body never touches a wall or lava.
  - Every single grid step passes the straight-line check.
  - Hierarchical A\* on 2,400 routes in random worlds (4-way and 8-way,
    clusters of 2 to 6): it finds a path exactly when plain A\* does, every
    step is a real move, and it never costs less than the cheapest. Every
    portal-graph edge is a real move or the exact cheapest cost inside its
    cluster. Short routes answered by plain A\* stay within their budget
    and cost the least; ones that run out of it are handed to the hierarchy.
  - Heights: standing heights for each block, slab and stairs staircases
    climbed with no jumps, fences that can't be jumped, low ceilings over
    slabs, drop distances measured from the exact heights, and straight
    lines over carpet but not across slabs.
  - Straight lines at wall corners, across holes and beside lava.
- **Movement** (`movement`): every built-in input script loads and ends
  with the keys released; script mistakes name their line; every trace
  value (positions, velocities, yaw, block shapes) reads back bit for bit;
  a block recorded twice keeps its latest state.
- **World import** (`mcworld`):
  - Every one of the 5,057 blocks in a test world written by an
    independent NBT library (`tools/make_test_world.py`, using nbtlib)
    decodes exactly. The world covers four region files at negative
    coordinates, a section with more than 16 block types, a section
    below y = 0, a uniform section, all three compression types and an
    external chunk.
  - Islands and their bounds are found correctly.
  - Block mappings: slabs, stairs, snow layers, carpet and other heights,
    doors, gates, trapdoors, fences at 1.5, water, ladders and terrain.
  - Paths on the imported island go around the fence and reach the
    raised platform.
- **Viz:**
  - Pixel colours for each layer and marker.
  - Editor model: editing re-routes the search, refusals, marker drags and
    paint strokes, playback clamping and restarts, settings, inspection at
    a step, and resets that keep the demo worlds untouched.
  - Real Swing mouse events reach the model through the panel.
  - Pixel-to-cell mapping across multi-panel layouts.
  - Replay frames: the final frame equals the search result, frame k has
    exactly k expanded nodes, costs only go down over time, and the
    best-so-far path runs from the start to the node being expanded.

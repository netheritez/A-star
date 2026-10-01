# "A# jantq's own algo" (A*)
A pun insult jantq for not knowing what A* is.

A Fabric mod for Minecraft 26.3 that walks your player to a block by itself.
Type `/goto x y z` and it finds the path, plans the movement and steers your
player there through the movement keys and the camera, the way a person would
play: sprinting, jumping up blocks, dropping down ledges, taking corners in
smooth curves. On Hypixel SkyBlock it can also etherwarp and cast Instant
Transmission with an Aspect of the Void.

Under it is a custom A\* pathfinder in plain Java for 3D block worlds, and
tools for looking at its searches: an editor that replays a search step by
step, and a route command that draws routes across whole maps such as the
Dwarven Mines.

> **Fair play.** `/goto` plays the game for you. Use it in singleplayer, on
> your own server, or where the server allows it. Automated movement breaks
> the rules of many servers, Hypixel included, and can get an account banned.

## Installing

You need Minecraft 26.3, [Fabric Loader](https://fabricmc.net/use/) 0.16 or
newer, [Fabric API](https://modrinth.com/mod/fabric-api) and Java 25.

Build the mod from this repository (any JDK 17 or newer runs Gradle; it
downloads Java 25 for the build if it isn't installed):

```powershell
.\gradlew.bat -p client build
```

Put `client\build\libs\astar-client-0.1.0.jar` (about 6.5 MB, with the Mines map inside)
in your `.minecraft\mods` folder, next to Fabric API. On Linux or macOS use
`./gradlew -p client build`. To try it without installing,
`.\gradlew.bat -p client runClient` starts a development game with the mod.

Turn **auto-jump off** (Options, Controls): the game's own jumps get in the
way of the planned ones.

## Commands

| Command | What it does |
| --- | --- |
| `/goto <x> <y> <z>` | Walks to that block. |
| `/goto warp <x> <y> <z>` | Walks and etherwarps where that's quicker. |
| `/goto it <x> <y> <z>` | Walks and casts Instant Transmission, in single casts and in chains through the air. |
| `/goto aotv <x> <y> <z>` | Both etherwarps and Instant Transmission. |
| `/goto stop` | Stops, and gives you the controls back. |
| `/goto resume` | Plans again from where you are and carries on after a pause. |
| `/goto show [colour]` | Hides or shows the route drawn in the world. With a colour (`red`, `orange`, `yellow`, `green`, `cyan`, `blue`, `purple`, `pink`, `white`) it draws it in that one, remembered for next time. |
| `/goto hand left\|right` | Which hand you hold the mouse in, so the camera's small drifts turn the way yours would. |
| `/goto map [name]` | Says which saved map it thinks you're on; with a name, tells it (`dwarven-mines` comes with the mod). |
| `/goto cache [on\|off\|forget]` | Says what's saved for this place; `off` and `on` stop and restart saving chunks, `forget` deletes this place's saved chunks. |

Pressing any movement key or turning the mouse pauses it and hands you the
controls. `/trace` records movement for calibrating the physics; see
[`docs/TRACES.md`](docs/TRACES.md).

## How it gets there

- **Routes** head in 16 directions and pay a little for every turn, so open
  ground is crossed in a few long straight lines. Sharp corners are rounded
  off with curves that bend in and out evenly, taken at sprint speed. Routes
  keep to the middle of corridors and a step back from drops, and walk down
  stairs and slopes rather than step off ledges when the way round isn't
  much longer.
- **The camera** looks down the route a little ahead (further the faster
  you go), eases in and out of big turns and drifts a little, like a hand
  on a mouse. It never crouches. The rotations aren't perfect: see
  [`docs/ROTATIONS.md`](docs/ROTATIONS.md) for their limits and how to swap
  in your own.
- **Adventure mode.** It never breaks or places blocks and never opens
  doors, so routes go around closed doors.
- **Ladders and water** aren't climbed or swum yet. Routes go around them
  where they can; when there's no other way it stops in front of them.
- **When things go wrong** (it's pushed, lagged back, stuck, off the route,
  or a block or door appears ahead), it finds a short way back onto the
  route, or plans the whole way again. It reads the blocks from the game as
  it goes, and stops if you die.
- **Speed effects** are read from the player, so it plans and steers for
  Speed VII as well as for walking pace.
- **Planning** happens off the game thread. While you play it keeps a copy
  of the blocks around you ready, with its move graph and heuristic tables,
  so a `/goto` usually starts within a fraction of a second.

## Teleporting with the Aspect of the Void

`/goto warp`, `/goto it` and `/goto aotv` need an item named **Aspect of the
Void** in your hotbar. They walk short bits to line up, then:
- **etherwarp:** stop, sneak, aim at the top of a block up to 57 blocks away
  and right-click. Only hops of 35 blocks or more are used, and only where
  every nearby aim lands on the same block;
- **Instant Transmission:** right-click without sneaking, up to 12 blocks
  along the view. Single casts are made on the move; chains of casts through
  the air each have their own view, climbing and turning like a player's.

If a cast doesn't land where planned, it plans again from wherever you are,
leaving that hop out. Teleporting trips need the whole map's copy (below);
the first trip on a new map waits for it and for the hops, which are then
kept on disk.

**In singleplayer**, any item named "Aspect of the Void" teleports the same
way (sneak and right-click to etherwarp, right-click to cast), so trips can be
tried with real clicks:

```
/give @s diamond_shovel[custom_name="Aspect of the Void"]
```

## Long trips and saved maps

The goal has to be within your render distance, unless the map is saved:
- **The Dwarven Mines** come with the mod. When the chunks around you match
  them, `/goto` plans across the whole map.
- **Other places** are saved as you explore: every chunk the game loads is
  kept under `.minecraft/astar/places/<server>/<dimension>/` (about 4 KB a
  chunk), and the next visit is recognised by its blocks, so changing server
  names don't matter.
- **Whole maps.** Once it knows the map, it makes a copy of all of it in the
  background with its move graph and heuristic tables, and keeps them in
  `nav.bin` beside the saved chunks (about 35 MB for the Mines; read back in
  well under a second). Trips anywhere on the map then plan in one go.

When the way runs past what's loaded, it walks to the last point of the route
inside your loaded chunks and plans the next stretch from there.

The mod also writes `config/astar-mouse-hand.txt` (from `/goto hand`) and
`astar-casts.log` in the game folder (every Aspect of the Void click, for
checking the teleport rules against the server's).

## For developers

The main build (Java 21) has the pathfinder, the movement simulator and the
tools; the mod is a separate build so the main one never downloads Minecraft.

```bash
./gradlew edit     # interactive editor: edit the world, replay the search step by step
./gradlew route    # find and draw a route across the Dwarven Mines (or any world save)
./gradlew test     # all modules
./gradlew -p client build   # the mod
```

### Route maps and heatmaps

`route` finds a route across a map and draws it as images in
`build\viz\route\`. Run it with no path for the built-in Dwarven Mines, or
give it a world folder or `.zip`. It remembers the last map you gave it.

```powershell
.\gradlew.bat route                                              # the Mines, two far-apart points
.\gradlew.bat route --args="--from 168,202,283 --to -168,207,86"  # your own endpoints, in game coordinates
.\gradlew.bat route --args="C:\path\to\world.zip"                # another map
.\gradlew.bat route --args="--warp --transmit"                    # with etherwarps and Instant Transmission
```

| Image | What it shows |
| --- | --- |
| `overview.png` | The map from above (each column's highest floor, in grey), with the route coloured by height and its jumps and drops marked |
| `heights.png` | The heatmap: the same view with the floors themselves coloured by height, and the route in white |
| `profile.png` | Height against distance along the route |
| `levels.png` | Close-ups of the six levels the route uses most |
| `teleports.png` | With `--warp` or `--transmit`: the height map cropped to the route, walking in white, etherwarps in magenta and Instant Transmissions in orange with a dot per cast, numbered in order |

To look around a map yourself, `.\gradlew.bat edit` opens the editor on it.
Its view menu switches between the map from above in grey, coloured by
height, or one level at a time, and you can drag the start and goal to
re-route. Every option of both is in [`docs/TOOLS.md`](docs/TOOLS.md).

| Module | What it holds | Depends on |
| --- | --- | --- |
| `core` | The A\* search: `AStarSearch`, `MinHeap`, costs, heuristics, `SearchListener` | nothing |
| `pathing` | The block world, movement rules, move graph, landmarks, smoothing, and the teleport planner (`WarpHops`, `TransmitHops`, `Flights`) | `core` |
| `mcworld` | Reads Minecraft 1.18+ world saves and imports islands into a block world | `pathing` |
| `movement` | The player's movement: input scripts, recorded traces, the physics simulator, the execution plan, the executor and its recovery (`Journey`) | `pathing` |
| `viz` | Renderer, Swing editor, console and PNG demos, the `route` tool | `mcworld`, `movement` |
| `client` | The Fabric 26.3 mod: `/goto` and `/trace` | `movement`, `mcworld`, `pathing`, `core` (compiled in) |

Docs:
- [`docs/PATHFINDING.md`](docs/PATHFINDING.md): the library API, movement
  rules and costs, smoothing, and the search core.
- [`docs/TOOLS.md`](docs/TOOLS.md): the editor, map import, and the `route`
  command's options, including the simulator checks used after every
  executor change.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md): the layers and how
  planning and execution fit together.
- [`docs/ROTATIONS.md`](docs/ROTATIONS.md): how the camera turns, where it
  falls short, and how to put in your own rotation code.
- [`docs/TRACES.md`](docs/TRACES.md): recording movement in the game to
  calibrate the simulator.
- [`docs/ROADMAP.md`](docs/ROADMAP.md): what's built, what's next, and
  what's been noted for later.

The Dwarven Mines map in `maps/dwarven-mines.zip` is a copy of Hypixel's, used
to test routes; `edit` and `route` open it when no other map has been opened.

## Questions

Message me on Discord: **`.netherite_`**. Bug reports and ideas are also
welcome as [GitHub issues](https://github.com/abdyzam50-rgb/A-/issues).

## License

[PolyForm Noncommercial 1.0.0](LICENSE). You can use it, change it and
share it for free, as long as it's not for money and the `Required Notice`
line stays with it. Selling it, or using it in anything commercial, needs a
separate deal: ask on Discord (`.netherite_`).

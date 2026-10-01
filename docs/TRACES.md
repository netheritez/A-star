# Movement traces

The route executor will be tested against `SimulatedPlayer`, a copy of Minecraft's player
movement code. Traces are how we check that copy against the real game: the game records
exactly what the player did each tick, and the simulator must reproduce it from the same
inputs.

## Replaying

`astar.movement.sim.SimulatedPlayer` is the copy of the game's movement, and
`TraceReplay.replay(trace, resync, tolerance)` plays a trace's inputs through it and reports
the largest position and velocity error and the first tick that differed. With `resync` each
tick starts from the recorded position and velocity, so one early difference doesn't hide
later ones. `TraceReplayTest` replays every fixture and requires an exact match.

## Recording

The recorder is a Fabric client mod for Minecraft 26.3 in `client/`. It's a separate Gradle
build, so the main build and CI never download Minecraft. It compiles for Java 25, which
Gradle downloads if it isn't installed.

```
./gradlew -p client build        # the mod: client/build/libs/astar-client-0.1.0.jar
./gradlew -p client runClient    # a development client with the mod loaded
```

To use it in a normal install, put the jar and Fabric API in `.minecraft/mods/` of a Fabric
26.3 profile.

In game:

| Command | What it does |
|---|---|
| `/trace play <script>` | Waits until you stand still on the ground, then plays the script's inputs and records them. The keyboard does nothing until it ends. |
| `/trace start` | Records while you play. |
| `/trace stop` | Ends either kind of recording. |
| `/trace list` | Names the scripts. |

Traces are saved to `.minecraft/astar-traces/<script>-<date>-<time>.jsonl`.

For clean traces: turn **Auto-Jump off** (it adds jumps the script didn't ask for), use a
flat area with room to run, and use survival or adventure mode so the player can't fly. Speed
or jump effects, armour enchantments and hunger (no sprinting at 3 drumsticks of food or fewer) all change
the movement, and the header records the attributes that result.

### The test course

`/trace course <x> <y> <z>` builds a flat calibration course with its origin at that block
(it needs cheats on, and clears a 49 by 65 area up to 8 blocks high): open floor for walking
and turning, a slab and a raised floor, a wall, steps one to three blocks high, packed ice,
soul sand, and a platform three blocks up. While a course is built, scripts that start with
an `at` line teleport there first, so every run starts from the same spot. The layout is in
`client/src/main/java/astar/client/TestCourse.java`.

The Mines save (`maps/dwarven-mines.zip`) works too, but it is cramped for sprints. It opens
in spectator mode with an old data pack that 26.3 can't load; switch to survival and
remove `datapacks/` from a copy before using it.

### Unattended runs

`astar.trace.autoplay=walk,sprint,turn` plays those scripts one after another once a world
is joined, `astar.trace.course=0,290,0` builds the test course there first, and
`astar.trace.autoquit=true` closes the game after them. They are system properties
(`-D...`); with `runClient` pass them as Gradle properties instead
(`-Pastar.trace.autoplay=...`), which the run forwards to the game. With
`--quickPlaySingleplayer <world>` a whole set of traces can be recorded without touching the
game, even on a machine with no screen (under `xvfb-run`). 26.3's renderer needs EGL there:
install Mesa's EGL (`libegl1`, `libegl-mesa0`) and set `SDL_VIDEO_FORCE_EGL=1`,
`LIBGL_ALWAYS_SOFTWARE=1` and `XDG_RUNTIME_DIR`. Opening an older save shows an upgrade and
an experimental-settings screen first, which need a click.

The fixtures in `movement/src/test/resources/astar/movement/traces/` were recorded this way:

```
./gradlew -p client runClient -Pastar.trace.course=0,290,0 \
    -Pastar.trace.autoplay=walk,sprint,...,course-sneak-edge -Pastar.trace.autoquit=true \
    --args="--quickPlaySingleplayer <world>"
```

### The executor in the real game

`route --exec --script <dir>` writes every stretch the executor walked in the simulator as
a script (`route-00.txt`, ...) that starts with `from x y z yaw`: a teleport to the
stretch's first step in the world, then the executor's keys and camera, tick by tick.
`autoplay.txt` lists them. Copy the scripts into `config/astar-scenarios/`, and play them
in the same map with commands allowed; `astar.trace.commands="difficulty peaceful"` runs
commands first (peaceful keeps hunger from ending sprints). Then `route --exec --score
<trace dir>` scores the newest trace of each script:

- **drift**: how far the real player got from the simulated one, at worst and at the end;
- **off route**: how far from the route's line, against the room the plan gave;
- **end**: how far from the stretch's last step it stopped;
- **replay**: the simulator run again on the blocks the trace recorded. Near zero means the
  physics is right, so any drift comes from the blocks the plan was made on.

### Block shapes from the game

The importer gives stairs, slabs, fences and other odd-shaped blocks their real collision
shapes from `mcworld/src/main/resources/astar/mcworld/block-shapes.txt.gz`. The mod writes
that table from the game on its first tick, so after a Minecraft update, regenerate it:

```
./gradlew -p client runClient -Pastar.shapes.dump=$PWD/mcworld/src/main/resources/astar/mcworld/block-shapes.txt.gz
```

```
./gradlew route --args="--exec --script build/choreo"
cp build/choreo/route-*.txt client/run/config/astar-scenarios/
./gradlew -p client runClient -Pastar.trace.commands="difficulty peaceful" \
    -Pastar.trace.autoplay=$(cat build/choreo/autoplay.txt) -Pastar.trace.autoquit=true \
    --args="--quickPlaySingleplayer mines"
./gradlew route --args="--exec --score client/run/astar-traces"
```

## Scripts

Built in (`movement/src/main/resources/astar/movement/scenarios/`): `walk`, `sprint`,
`sneak`, `jump`, `walk-jump`, `sprint-jump`, `strafe`, `diagonal`, `turn`, `sprint-stop`, and
for the test course `course-steps`, `course-wall`, `course-wall-glance`, `course-jump-up`,
`course-ice`, `course-soul-sand`, `course-ledge`, `course-sneak-edge`.
Your own go in `.minecraft/config/astar-scenarios/<name>.txt` and override a built-in one of
the same name.

One step per line: how many ticks it lasts, the keys held, and optional camera settings.

```
# Sprint, jump once, and coast to a stop.   <- the first comment is the description
15 W sprint
1  W sprint jump
20 W sprint
20 -
```

- Keys: `W A S D jump sneak sprint`, or `-` for none.
- `yaw=<degrees>` sets the yaw at the start of the step, relative to where you faced when
  the script started. Positive turns right.
- `turn=<degrees>` turns the yaw by that much every tick of the step.
- `pitch=<degrees>` sets the pitch (positive looks down). Otherwise it's left alone.
- `at <x> <y> <z> <yaw>`, once before the steps, says where on the test course the script
  starts, relative to the course's origin (yaw -90 faces +x). It's ignored when no course is
  built.
- `from <x> <y> <z> <yaw>`, instead, is a place in the world: the player is teleported there
  first (commands must be allowed).

The parser is `astar.movement.Scenario`.

## Format

JSON Lines: one object per line, told apart by `type`. `astar.movement.trace.Trace.read`
reads a file back; every number is written so that it reads back bit for bit.

**`header`**, always first:

```json
{"type":"header","format":1,"game":"26.3","scenario":"sprint","started":"2026-09-24T18:02:11+00:00",
 "attributes":{"minecraft:movement_speed":0.10000000149011612,"minecraft:step_height":0.6,"option:auto_jump":0.0}}
```

`attributes` holds the player's movement attributes at the start (movement speed, jump
strength, step height, gravity, sneaking speed, movement and water movement efficiency, safe
fall distance, fall damage multiplier, scale, block reach) and a few options (auto-jump,
toggle sprint, toggle sneak, mouse sensitivity) as 0 or 1. Attributes are base values, so
the sprint boost isn't in them. Entries starting `state:` are what the first tick starts from
that the tick lines don't give: `on_ground`, `horizontal_collision`, `sprinting`,
`sneak_pose`, `crouching`, `sneak_key` (0 or 1), `fall_distance`, `movement_speed` and
`food`.

**`block`**: a block near the player that isn't plain air. Blocks within 12 blocks sideways
and 6 up or down are recorded at the start, and again every 10 ticks or when the player has
moved 4 blocks; a block is written again only if it changed (a later record replaces the
earlier one when read).

```json
{"type":"block","p":[-80,221,-119],"state":"minecraft:oak_slab[type=bottom,waterlogged=false]",
 "boxes":[[0.0,0.0,0.0,1.0,0.5,1.0]],"slip":0.6,"vel":1.0,"jump":1.0,"fluid":"","fh":0.0,"climb":false}
```

`boxes` is the collision shape the game uses for the player, relative to the block's corner.
`slip` is ground friction, `vel` and `jump` are the block's speed and jump multipliers,
`fluid` is `water`, `lava` or empty, `fh` the fluid's height in the block, and `climb` whether
it's a ladder, vine or other climbable block. `kind` is `fence`, `wall` or `gate` for those
blocks (the game treats what's underfoot differently for them), and `ys` lists the heights
the shape is divided at (a bottom slab gives `[0.0,0.5,1.0]`), which stepping up tries; both
are left out when there's nothing to say.

**`tick`**: one game tick.

```json
{"type":"tick","t":17,"keys":"WR","mv":[0.0,0.98],"yaw":-90.0,"pitch":12.5,
 "p0":[-79.5,222.0,-118.5],"v0":[0.21,-0.0784,0.0],"p":[-79.28,222.0,-118.5],"v":[0.23,-0.0784,0.0],
 "flags":"GR","fall":0.0}
```

- `keys`: the keys the game read this tick, one letter each from `WASDJNR` (J jump, N sneak,
  R sprint), or `-`.
- `mv`: sideways (positive is left) and forward movement input after the game scaled it
  (0.98, sneaking, using an item).
- `yaw`, `pitch`: the camera the tick moved with.
- `p0`, `v0`: feet position and velocity (blocks per tick) before the tick; `p`, `v` after.
- `flags`: one letter per true flag from `GHVRNWC`: on the Ground, Horizontal collision,
  Vertical collision, spRinting, sNeaking, in Water, Climbing.
- `fall`: fall distance after the tick.

**`event`**: something that isn't movement, such as the script ending, recording stopping
early, blocks changing, or the player moving between ticks (a teleport or a server
correction). `t` is the last tick recorded before it.

```json
{"type":"event","t":79,"text":"script ended"}
```

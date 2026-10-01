package astar.pathing;

import astar.core.BlockPoint;
import astar.core.MoveGraph;
import astar.core.MoveType;
import astar.core.PathStep;
import astar.core.Pos;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Air lines: a pass over a finished route that puts runs of it in the air, the way a player
 * flies with Instant Transmission.
 *
 * <p>From a spot on the route, the player casts, and while still in the air casts again, each
 * cast at a view of its own: up steeply to climb over something, level to cruise, turned
 * up to {@link #MAX_AIR_TURN} degrees to follow a bend (turned at a hand's mid-air pace,
 * see {@link HandTurn}, while the player falls), or down to drop onto the floor.
 * Views are worked out a cast at a time, kept to the few flights that have got furthest along
 * the route for the least time (a beam), and a flight lands on the route further on, or a step
 * off it. Where one saves time over the part of the route it skips (walking and single casts;
 * etherwarps are left alone), it takes that part's place.
 *
 * <p>Every cast is checked sure as {@link TransmitHops} checks a chain's: the first from
 * standing, with the eye anywhere near the middle of the spot; the later ones from the middle
 * of the block the last one ended in, fallen as far as the player falls between a teleport
 * showing and the next click: the tick after, when the view stays, or once it has turned.
 */
public final class Flights {
    private Flights() {}

    /** Yaw grid: this many directions round. */
    static final int YAWS = 48;
    /** How far the first cast may turn from the way the route goes, in steps of the yaw grid. */
    static final int FIRST_CONE_STEPS = 7;
    /** How far a cast in the air may turn from the last one, in steps of the yaw grid. */
    static final int AIR_TURN_STEPS = 6;
    /**
     * The most a cast in the air may turn from the last one, in degrees: mid-air there's no
     * time for a big turn before the fall spoils the cast.
     */
    static final double MAX_AIR_TURN = 45;
    /**
     * What a flight pays for each cast whose view turns sideways at all, in blocks walked: a
     * player turns once, sharper, then holds the line, rather than a little every cast. Only
     * to pick flights by; it's not part of the time a flight takes ({@link Flight#cost}).
     */
    static final double TURN_EACH = 1;
    /** Most casts in one flight. */
    public static final int MAX_CASTS = 32;
    /** Flights kept after each cast. */
    static final int BEAM = 120;
    /**
     * The spread-out searches: at most {@code per} flights kept each cast in each box of the
     * map {@code 1 << shift} blocks wide, so the beam can't fill up with near copies of one
     * line (along a wall, say, a block higher or lower) and lose the straight one down the
     * middle of a hall. The plain search has no limit.
     */
    record Spread(int shift, int per) {}

    static final List<Spread> SPREADS = List.of(new Spread(0, Integer.MAX_VALUE),
            new Spread(3, 8), new Spread(2, 3));
    /**
     * What a flight pays per degree its view turns in the air, in blocks walked, on top of the
     * time the turn takes: a nudge toward straight lines.
     */
    static final double TURN_COST = 0.002;
    /**
     * For a flight over ground too far down to stop on: what flying on is guessed to cost per
     * block toward the goal, and on top.
     */
    static final double FLY_RATE = TransmitHops.CHAIN_COST / TransmitHops.DEFAULT_RANGE;
    static final double NO_FLOOR = 10;
    /** Flights kept only while they look no worse than the best landing found by this much. */
    static final double BEAM_SLACK = 6;
    /** How far ahead the first cast looks for the way the route goes, in blocks. */
    static final double LOOK_AHEAD = 40;
    /** The least a flight must save to take a part of the route's place, in blocks walked. */
    public static final double MIN_SAVING = 1.0;
    /**
     * Other places a flight could land that are looked one flight further on before picking
     * (a climb onto a ledge can look worse than a drop on the ground's prices and still be
     * the quicker trip, flying on from up there): up to this many, in other parts of the map
     * ({@link #ALT_CELL} blocks a side) than the best.
     */
    static final int ALTS = 1;
    /** Blocks from where a flight landed within which the next may not turn back on it. */
    static final double TURN_BACK_WALK = 12;
    static final int ALT_CELL = 8;
    /**
     * From the spot a route casts from, a flight may also start this many blocks' walk away,
     * from up to {@link #WALK_IN_SPOTS} spots at least {@link #WALK_IN_GAP} apart.
     */
    static final double WALK_IN = 8;
    static final int WALK_IN_SPOTS = 10;
    static final double WALK_IN_GAP = 2.5;
    /** What a cast cut short by a block costs on top, in blocks walked. */
    static final double SHORT_HIT = 4;
    /** What each block fallen after a flight's last cast costs on top, past the first two. */
    static final double FALL_EXTRA = 1;
    /** How much more a flight after a walk must save than one from where the route is. */
    static final double WALK_IN_MARGIN = 2;
    /** Flights are tried from every this many route steps (and from every landing). */
    static final int TRY_EVERY = 2;

    /**
     * Pitches a cast may take: climbing steeply (three), a chain's rises of 3 down to -3 blocks
     * (seven, see {@link #pitch}: the user's own chains climb and drop up to 3 a cast), dropping
     * steeply (three).
     */
    static final double[] STEEP = {-60, -40, -20, 0, 0, 0, 0, 0, 0, 0, 20, 40, 60};
    /** The first and last of the rises in {@link #STEEP}, and the rise of the first. */
    static final int FIRST_RISE = 3;
    static final int LAST_RISE = 9;
    static final int PITCHES = STEEP.length;

    /**
     * Pitch {@code pi} for a cast clicked {@code wait} ticks after the last teleport showed
     * (the rises allow for how far the player has fallen by then).
     */
    static double pitch(int pi, int wait) {
        return wait >= 0 && wait < PITCH_AT[pi].length ? PITCH_AT[pi][wait] : pitchOf(pi, wait);
    }

    /** {@link #pitch} worked out once for the waits a chain takes. */
    private static final double[][] PITCH_AT = new double[PITCHES][64];

    static {
        for (int pi = 0; pi < PITCHES; pi++) {
            for (int w = 0; w < PITCH_AT[pi].length; w++) {
                PITCH_AT[pi][w] = pitchOf(pi, w);
            }
        }
    }

    private static double pitchOf(int pi, int wait) {
        if (pi >= FIRST_RISE && pi <= LAST_RISE) {
            return TransmitHops.chainPitch(FIRST_RISE + 3 - pi, TransmitHops.DEFAULT_RANGE,
                    wait);
        }
        return STEEP[pi];
    }

    /**
     * One flight: casts from standing spot {@code from} (packed) at views {@code yaw[n]},
     * {@code pitch[n]}, ending on standing spot {@code land}; {@code through} is the block
     * each cast puts the feet in, then the one they fall onto, if that's another.
     */
    public record Flight(long from, long land, float[] yaw, float[] pitch, List<Long> through,
            double cost) {
        public int casts() {
            return yaw.length;
        }
    }

    /**
     * The route with air lines put in: {@code flights} holds the flight that reaches step k of
     * {@code path} (a {@link MoveType#TRANSMIT} step), by k; {@code saved} is what they save,
     * in blocks walked.
     */
    public record Result(List<PathStep> path, Map<Integer, Flight> flights, int tried,
            double saved) {}

    /**
     * {@code path}, a route to its last step over {@code graph}, with air lines put in where
     * they're quicker. What's left of the trip from wherever a flight lands is the cheapest way
     * on over {@code graph} at the prices {@link HopGraph#costs} charges without the turns
     * ({@code detour}, {@code etherLength}, {@code walkDetour}), which is also what a flight is
     * judged by: its own time plus what's left from where it lands, against what's left from
     * where it starts. No flight starts at a spot in {@code banned} (packed).
     */
    public static Result straighten(ArrayBlockView world, MoveGraph graph, List<PathStep> path,
            double range, double detour, double etherLength, double walkDetour,
            Set<Long> banned) {
        long goal = path.get(path.size() - 1).pos().pack();
        long t0 = System.nanoTime();
        ToGoal left = ToGoal.of(graph, goal, detour, etherLength, walkDetour);
        STATS.toGoalNs += System.nanoTime() - t0;
        STATS.plans++;
        // Flights picked one after another can't see far: the plain search and the
        // spread-out ones (SPREADS) side by side, and the quickest trip of them kept (the
        // first if they tie), so a route is never slower than the plain search alone.
        List<Result> all = SPREADS.parallelStream()
                .map(sp -> straighten(world, graph, path, range, banned, goal, left, sp))
                .toList();
        Result best = all.get(0);
        double quickest = time(graph, best.path(), best.flights());
        for (Result r : all.subList(1, all.size())) {
            double t = time(graph, r.path(), r.flights());
            if (t < quickest) {
                best = r;
                quickest = t;
            }
        }
        return best;
    }

    private static Result straighten(ArrayBlockView world, MoveGraph graph, List<PathStep> path,
            double range, Set<Long> banned, long goal, ToGoal left, Spread spread) {
        java.util.function.Supplier<Search> s =
                () -> new Search(world, graph, left, goal, range, spread);
        Map<Spot, Choice> after = new HashMap<>();
        double facing = Double.NaN;
        List<PathStep> rest = new ArrayList<>(path);
        List<PathStep> out = new ArrayList<>();
        Map<Integer, Flight> flights = new HashMap<>();
        int tried = 0;
        double saved = 0;
        int i = 0;
        int lastTry = -TRY_EVERY;
        out.add(rest.get(0));
        while (i < rest.size() - 1) {
            MoveType via = rest.get(i).via();
            boolean landing = via == MoveType.TRANSMIT || via == MoveType.WARP;
            Found got = null;
            long at = rest.get(i).pos().pack();
            if (!banned.contains(at) && (i == 0 || landing || i - lastTry >= TRY_EVERY)) {
                lastTry = i;
                tried++;
                boolean casts = i + 1 < rest.size() && (rest.get(i + 1).via() == MoveType.TRANSMIT
                        || rest.get(i + 1).via() == MoveType.WARP);
                long t1 = System.nanoTime();
                // Just landed, or a few steps on from there: the next flight goes on the way
                // the last one went.
                double from = out.size() > 1 && dist(at, rest.get(0).pos().pack()) < TURN_BACK_WALK
                        ? facing : Double.NaN;
                Choice c = i == 0 && out.size() > 1 ? after.get(new Spot(at, from)) : null;
                if (c == null) {
                    c = choose(s, at, rest, i, i == 0 || landing || casts, from);
                }
                got = c.got();
                if (got != null && !c.alts().isEmpty()) {
                    got = lookOn(s, left, got, c.alts(), after);
                }
                STATS.searchNs += System.nanoTime() - t1;
            }
            if (got == null) {
                i++;
                out.add(rest.get(i));
                continue;
            }
            saved += got.saving();
            out.addAll(got.walk());
            out.add(new PathStep(point(got.flight().land()), MoveType.TRANSMIT));
            flights.put(out.size() - 1, got.flight());
            rest = left.path(got.flight().land());
            facing = lastYaw(got.flight());
            i = 0;
            lastTry = 0;
        }
        return new Result(out, flights, tried, saved);
    }

    /** The flight picked from a spot, and the others landing elsewhere worth a look on. */
    private record Choice(Found got, List<Found> alts) {}

    /** A spot a flight is looked for from, and the way the player faces there (NaN: any). */
    private record Spot(long at, double facing) {}

    private static double lastYaw(Flight f) {
        return f.yaw()[f.casts() - 1];
    }

    /**
     * The flight from {@code at}, step {@code i} of {@code rest}, that saves the most (walking
     * a little further first, if {@code walkIn}, only when that saves clearly more), and the
     * best few landing elsewhere.
     */
    private static Choice choose(java.util.function.Supplier<Search> make, long at,
            List<PathStep> rest, int i, boolean walkIn, double facing) {
        // From the spot and after a walk: two searches, side by side.
        Search s = make.get(), w = walkIn ? make.get() : null;
        s.facing = facing;
        List<java.util.function.Supplier<Found>> jobs = new ArrayList<>();
        jobs.add(() -> s.best(at, rest, i, false));
        if (w != null) {
            w.facing = facing;
            jobs.add(() -> w.best(at, rest, i, true));
        }
        List<Found> found = jobs.parallelStream().map(java.util.function.Supplier::get).toList();
        Found got = found.get(0);
        List<Found> alts = new ArrayList<>(s.alts);
        if (w != null) {
            // Walking a little further first only when that saves clearly more.
            Found walked = found.get(1);
            alts.addAll(w.alts);
            if (walked != null && !walked.walk().isEmpty() && (got == null
                    || walked.saving() > got.saving() + WALK_IN_MARGIN)) {
                if (got != null) {
                    alts.add(got);
                }
                got = walked;
            }
        }
        if (got == null) {
            return new Choice(null, List.of());
        }
        alts.sort(Comparator.comparingDouble(f -> -f.saving()));
        List<Found> keep = new ArrayList<>();
        List<Long> parts = new ArrayList<>(List.of(part(got.flight().land())));
        for (Found f : alts) {
            if (keep.size() < ALTS && !parts.contains(part(f.flight().land()))) {
                parts.add(part(f.flight().land()));
                keep.add(f);
            }
        }
        return new Choice(got, keep);
    }

    /**
     * Of flight {@code got} and the others in {@code alts}, the one that makes the quickest
     * trip taking the next flight on from where it lands as well (worked out once per spot,
     * kept in {@code after} for when the trip gets there).
     */
    private static Found lookOn(java.util.function.Supplier<Search> s, ToGoal left, Found got,
            List<Found> alts, Map<Spot, Choice> after) {
        Found pick = got;
        double best = Double.POSITIVE_INFINITY;
        List<Found> all = new ArrayList<>();
        all.add(got);
        all.addAll(alts);
        // The spots not looked on from yet, all at once.
        List<Spot> fresh = new ArrayList<>();
        for (Found f : all) {
            Spot at = new Spot(f.flight().land(), lastYaw(f.flight()));
            if (!after.containsKey(at) && !fresh.contains(at)) {
                fresh.add(at);
            }
        }
        List<List<PathStep>> ways = new ArrayList<>();
        for (Spot at : fresh) {
            ways.add(left.path(at.at()));
        }
        List<Choice> made = java.util.stream.IntStream.range(0, fresh.size()).parallel()
                .mapToObj(k -> choose(s, fresh.get(k).at(), ways.get(k), 0, true,
                        fresh.get(k).facing()))
                .toList();
        for (int k = 0; k < fresh.size(); k++) {
            after.put(fresh.get(k), made.get(k));
        }
        for (Found f : all) {
            Choice c = after.get(new Spot(f.flight().land(), lastYaw(f.flight())));
            // What's left from where it lands, then minus what the next flight saves on it.
            double on = -f.saving() - (c.got() == null ? 0 : c.got().saving());
            if (on < best) {
                best = on;
                pick = f;
            }
        }
        return pick;
    }

    /** {@link #TURN_EACH} if a cast's view turns sideways from {@code from} to {@code to}. */
    static double turnCharge(double from, double to) {
        return Math.abs(HandTurn.wrap(to - from)) > 1 ? TURN_EACH : 0;
    }

    /** The part of the map ({@link #ALT_CELL} blocks a side) spot {@code p} is in. */
    private static long part(long p) {
        return Pos.pack(Math.floorDiv(Pos.x(p), ALT_CELL), Math.floorDiv(Pos.y(p), ALT_CELL),
                Math.floorDiv(Pos.z(p), ALT_CELL));
    }

    /**
     * How long {@code path} over {@code graph} takes, in blocks walked, with the steps in
     * {@code flights} flown: each step's price, without turns.
     */
    public static double time(MoveGraph graph, List<PathStep> path, Map<Integer, Flight> flights) {
        double t = 0;
        for (int i = 1; i < path.size(); i++) {
            Flight f = flights.get(i);
            t += f != null ? f.cost() : price(graph, path.get(i - 1).pos().pack(),
                    path.get(i).pos().pack(), path.get(i).via());
        }
        return t;
    }

    /** The cheapest move of kind {@code via} from {@code a} to {@code b} in {@code graph}. */
    static double price(MoveGraph graph, long a, long b, MoveType via) {
        int from = graph.cell(a), to = graph.cell(b);
        int[] start = graph.moveStart();
        double best = Double.MAX_VALUE;
        for (int e = start[from]; e < start[from + 1]; e++) {
            if (graph.moveTo()[e] == to && (via == null || graph.moveType()[e] == via.ordinal())) {
                best = Math.min(best, graph.moveCost()[e]);
            }
        }
        return best;
    }

    private static BlockPoint point(long c) {
        return new BlockPoint(Pos.x(c), Pos.y(c), Pos.z(c));
    }

    /**
     * What's left of the trip to one goal from every cell of a graph, the cheapest way, and
     * which move to take for it.
     */
    static final class ToGoal {
        final MoveGraph graph;
        private final double[] cost;
        /** The move to take out of each cell, -1 at the goal or where it can't be reached. */
        private final int[] next;
        /**
         * Cells whose cost is final. The search out from the goal goes only as far as it's
         * been asked about (a trip uses a small part of the map), and on when asked further:
         * the same steps as all at once, so the same costs.
         */
        private final boolean[] done;
        private final Heap heap;
        private final long goal;
        private final double detour, etherLength, walkDetour;
        private final Reverse r;

        private ToGoal(MoveGraph graph, long goal, double detour, double etherLength,
                double walkDetour) {
            this.graph = graph;
            this.goal = goal;
            this.detour = detour;
            this.etherLength = etherLength;
            this.walkDetour = walkDetour;
            int n = graph.positions().length;
            cost = new double[n];
            Arrays.fill(cost, Double.POSITIVE_INFINITY);
            next = new int[n];
            Arrays.fill(next, -1);
            done = new boolean[n];
            r = reverse(graph);
            int g = graph.cell(goal);
            heap = new Heap(16);
            if (g >= 0) {
                cost[g] = 0;
                heap.push(g, 0);
            }
        }

        /**
         * Searches on until cell {@code v}'s cost is final, or it's known it can't be reached.
         * Flights are looked for on several threads at once: one searches on at a time, and a
         * cell marked done has its cost and move written before.
         */
        private void settle(int v) {
            if ((boolean) DONE.getAcquire(done, v)) {
                return;
            }
            synchronized (this) {
                while (!done[v] && !heap.empty()) {
                    step();
                }
            }
        }

        private static final java.lang.invoke.VarHandle DONE =
                java.lang.invoke.MethodHandles.arrayElementVarHandle(boolean[].class);

        /** One cell out of the heap: its cost is final; the moves into it priced. */
        private void step() {
            long[] pos = graph.positions();
            double[] price = graph.moveCost();
            int[] inStart = r.inStart(), inMove = r.move(), from = r.from();
            double[] length = r.length();
            byte[] kind = r.kind();
            int v = heap.top();
            double d = heap.topKey();
            heap.pop();
            if (d > cost[v]) {
                return;
            }
            DONE.setRelease(done, v, true);
            double toV = dist(pos[v], goal);
            for (int k = inStart[v]; k < inStart[v + 1]; k++) {
                int e = inMove[k], u = from[k];
                double w = price[e];
                // As HopGraph.extra: the part of the move not toward the goal, and for an
                // etherwarp, how much shorter than it should be it is.
                if (kind[k] != 0) {
                    double len = length[k];
                    double c = detour * Math.max(0, len - (dist(pos[u], goal) - toV));
                    if (kind[k] == 2) {
                        c += HopGraph.SHORT_ETHER * Math.max(0, etherLength - len);
                    }
                    w += c;
                } else if (walkDetour > 0) {
                    w += walkDetour * Math.max(0, length[k] - (dist(pos[u], goal) - toV));
                }
                double c = d + w;
                if (c < cost[u]) {
                    cost[u] = c;
                    next[u] = e;
                    heap.push(u, c);
                }
            }
        }

        /**
         * A graph's moves backwards, which don't depend on the goal: into each cell, the moves
         * that come in ({@code inStart} into {@code move}, {@code from}), each one's length and
         * kind (0 a walk, 1 a transmission, 2 an etherwarp). Worked out once per graph.
         */
        private record Reverse(int[] inStart, int[] move, int[] from, double[] length,
                byte[] kind) {}

        private static final Map<MoveGraph, Reverse> REVERSE =
                java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

        private static Reverse reverse(MoveGraph graph) {
            return REVERSE.computeIfAbsent(graph, gr -> {
                long[] pos = gr.positions();
                int n = pos.length;
                int[] start = gr.moveStart();
                int[] dest = gr.moveTo();
                byte[] type = gr.moveType();
                int m = start[n];
                int[] inStart = new int[n + 1];
                for (int e = 0; e < m; e++) {
                    inStart[dest[e] + 1]++;
                }
                for (int v = 0; v < n; v++) {
                    inStart[v + 1] += inStart[v];
                }
                int[] fill = Arrays.copyOf(inStart, n);
                int[] inMove = new int[m];
                int[] from = new int[m];
                double[] length = new double[m];
                byte[] kind = new byte[m];
                byte transmit = (byte) MoveType.TRANSMIT.ordinal();
                byte warp = (byte) MoveType.WARP.ordinal();
                for (int u = 0; u < n; u++) {
                    for (int e = start[u]; e < start[u + 1]; e++) {
                        int k = fill[dest[e]]++;
                        inMove[k] = e;
                        from[k] = u;
                        length[k] = dist(pos[u], pos[dest[e]]);
                        kind[k] = type[e] == transmit ? (byte) 1 : type[e] == warp ? (byte) 2 : 0;
                    }
                }
                return new Reverse(inStart, inMove, from, length, kind);
            });
        }

        static ToGoal of(MoveGraph graph, long goal, double detour, double etherLength,
                double walkDetour) {
            return new ToGoal(graph, goal, detour, etherLength, walkDetour);
        }

        /** What's left from cell {@code p} (packed); infinite if it's not in the graph. */
        double left(long p) {
            int v = graph.cell(p);
            if (v < 0) {
                return Double.POSITIVE_INFINITY;
            }
            settle(v);
            return cost[v];
        }

        /** The next {@code moves} spots on the cheapest way from {@code p} to the goal. */
        long[] ahead(long p, int moves) {
            long[] pos = graph.positions();
            int v = graph.cell(p);
            if (v >= 0) {
                settle(v);
            }
            long[] out = new long[moves];
            int k = 0;
            while (v >= 0 && next[v] >= 0 && k < moves) {
                v = graph.moveTo()[next[v]];
                out[k++] = pos[v];
            }
            return Arrays.copyOf(out, k);
        }

        /** The cheapest way on from {@code p} (packed) to the goal, starting with {@code p}. */
        List<PathStep> path(long p) {
            List<PathStep> out = new ArrayList<>();
            long[] pos = graph.positions();
            MoveType[] types = MoveType.values();
            int v = graph.cell(p);
            if (v >= 0) {
                settle(v);
            }
            out.add(new PathStep(point(p), null));
            while (v >= 0 && next[v] >= 0) {
                int e = next[v];
                v = graph.moveTo()[e];
                out.add(new PathStep(point(pos[v]), types[graph.moveType()[e]]));
            }
            return out;
        }
    }

    /** A binary heap of cells by key, with repeats (the stale ones are skipped). */
    private static final class Heap {
        private int[] node;
        private double[] key;
        private int size;

        Heap(int capacity) {
            node = new int[Math.max(16, capacity)];
            key = new double[node.length];
        }

        boolean empty() {
            return size == 0;
        }

        int top() {
            return node[0];
        }

        double topKey() {
            return key[0];
        }

        void push(int v, double k) {
            if (size == node.length) {
                node = Arrays.copyOf(node, size * 2);
                key = Arrays.copyOf(key, size * 2);
            }
            int i = size++;
            while (i > 0) {
                int parent = (i - 1) >> 1;
                if (key[parent] <= k) {
                    break;
                }
                node[i] = node[parent];
                key[i] = key[parent];
                i = parent;
            }
            node[i] = v;
            key[i] = k;
        }

        void pop() {
            int v = node[--size];
            double k = key[size];
            int i = 0;
            while (true) {
                int c = 2 * i + 1;
                if (c >= size) {
                    break;
                }
                if (c + 1 < size && key[c + 1] < key[c]) {
                    c++;
                }
                if (key[c] >= k) {
                    break;
                }
                node[i] = node[c];
                key[i] = key[c];
                i = c;
            }
            node[i] = v;
            key[i] = k;
        }
    }

    /** A flight, what it saves, and the walk onto the spot it's cast from (none: empty). */
    private record Found(Flight flight, double saving, List<PathStep> walk) {}

    /** A flight so far: in the air at {@code cell} after {@code n} casts. */
    private record State(long cell, int yaw, double yawDeg, int pitch, double pitchDeg, int n,
            double g, double est, State parent, long origin, Search.Check check) {}

    private static final class Search {
        private final ArrayBlockView world;
        private final ToGoal left;
        private final long goal;
        private final double range;
        private final double minSq;
        /** How many flights are kept per box of the map each cast ({@link #SPREADS}). */
        private final Spread spread;

        Search(ArrayBlockView world, MoveGraph graph, ToGoal left, long goal, double range,
                Spread spread) {
            this.spread = spread;
            this.world = world;
            this.left = left;
            this.goal = goal;
            this.range = range;
            this.minSq = TransmitHops.DEFAULT_MIN_LENGTH * TransmitHops.DEFAULT_MIN_LENGTH;
        }

        private long start;
        /** Standing spots on the way from the flight's start to the goal, to aim casts at. */
        private long[] targets = new long[0];
        private Found best;
        private double bestTotal;
        /** Casts checked in full and aimed, for {@link #STATS}. */
        private long airCasts, aimedCasts;
        /** The best landing in each part of the map ({@link #part}), with its total. */
        private final Map<Long, Found> byPart = new HashMap<>();
        private final Map<Long, Double> partTotal = new HashMap<>();
        /**
         * The way the player faces, just landed from a flight, in degrees (NaN: any): the
         * first cast turns no further than {@link #MAX_FLOOR_TURN} from it.
         */
        double facing = Double.NaN;
        /** From the last {@link #best}: the best landing in each other part, if it saves. */
        List<Found> alts = List.of();

        /**
         * The flight from standing spot {@code at}, step {@code i} of {@code route}, that saves
         * the most over going on the cheapest way, by at least {@link #MIN_SAVING}; or null.
         */
        Found best(long at, List<PathStep> route, int i, boolean walkIn) {
            try {
                return search(at, route, i, walkIn);
            } finally {
                STATS.add(airCasts, aimedCasts);
                airCasts = aimedCasts = 0;
            }
        }

        private Found search(long at, List<PathStep> route, int i, boolean walkIn) {
            this.start = at;
            int sx = Pos.x(at), sy = Pos.y(at), sz = Pos.z(at);
            if (!TransmitHops.standing(world, sx, sy, sz)) {
                return null;
            }
            double now = left.left(at);
            if (!(now < Double.POSITIVE_INFINITY)) {
                return null;
            }
            best = null;
            bestTotal = now - MIN_SAVING;
            byPart.clear();
            partTotal.clear();
            alts = List.of();
            int ahead = i;
            while (ahead + 1 < route.size()
                    && dist(at, route.get(ahead + 1).pos().pack()) <= LOOK_AHEAD) {
                ahead++;
            }
            long toward = ahead == i ? goal : route.get(ahead).pos().pack();
            List<Long> on = new ArrayList<>();
            for (int k = i + 1; k < route.size(); k++) {
                long c = route.get(k).pos().pack();
                if (TransmitHops.standing(world, Pos.x(c), Pos.y(c), Pos.z(c))) {
                    on.add(c);
                }
            }
            targets = on.stream().mapToLong(Long::longValue).toArray();
            List<State> layer = new ArrayList<>();
            int[] cell = new int[3];
            Map<Long, Walk> walks = walkIn ? walksFrom(at) : Map.of(at, new Walk(0, -1, -1));
            for (Map.Entry<Long, Walk> w : walks.entrySet()) {
                long o = w.getKey();
                int ox = Pos.x(o), oy = Pos.y(o), oz = Pos.z(o);
                if (walkIn && o == at) {
                    continue; // tried already, without the walk
                }
                if (o != at && !TransmitHops.standing(world, ox, oy, oz)) {
                    continue;
                }
                int base = yawIndex(yawTo(o, toward));
                double g0 = w.getValue().cost() + TransmitHops.DEFAULT_COST;
                for (int dy = -FIRST_CONE_STEPS; dy <= FIRST_CONE_STEPS; dy++) {
                    int yi = Math.floorMod(base + dy, YAWS);
                    if (turnsBack(yaw(yi))) {
                        continue;
                    }
                    for (int pi = 0; pi < PITCHES; pi++) {
                        double p = pitch(pi, 1);
                        int kind = TransmitHops.chainStep(world, ox + 0.5, oy, oz + 0.5,
                                yaw(yi), p, range, null, cell);
                        reached(kind, cell, yaw(yi), pi, p, 1, g0, null, layer, o);
                    }
                }
                aimed(ox, oy, oz, 0, Double.NaN, 0, 1, g0, null, layer, o);
            }
            this.walks = walks;
            Map<Long, Double> seen = new HashMap<>();
            for (int n = 2; n <= MAX_CASTS && !layer.isEmpty(); n++) {
                layer.sort(Comparator.comparingDouble(State::est));
                List<State> keep = new ArrayList<>();
                Map<Long, Integer> boxes = new HashMap<>();
                for (State st : layer) {
                    if (st.est() >= bestTotal + BEAM_SLACK) {
                        break;
                    }
                    Double had = seen.get(st.cell());
                    if (had != null && had <= st.g()) {
                        continue;
                    }
                    int sh = spread.shift();
                    long box = Pos.pack(Pos.x(st.cell()) >> sh, Pos.y(st.cell()) >> sh,
                            Pos.z(st.cell()) >> sh);
                    if (spread.per() < Integer.MAX_VALUE
                            && boxes.getOrDefault(box, 0) >= spread.per()) {
                        continue;
                    }
                    if (st.check() != null && !st.check().sure()) {
                        continue;
                    }
                    boxes.merge(box, 1, Integer::sum);
                    seen.put(st.cell(), st.g());
                    keep.add(st);
                    if (keep.size() >= BEAM) {
                        break;
                    }
                }
                List<State> next = new ArrayList<>();
                // Every cast from every state kept, worked out on all cores at once (each is
                // a pure look at the blocks), then taken in the same order as one at a time,
                // so the flight found is the same.
                List<int[]> casts = new ArrayList<>();
                List<double[]> views = new ArrayList<>();
                for (int k = 0; k < keep.size(); k++) {
                    State st = keep.get(k);
                    for (int dy = -AIR_TURN_STEPS; dy <= AIR_TURN_STEPS; dy++) {
                        int yi = Math.floorMod(st.yaw() + dy, YAWS);
                        for (int pi = 0; pi < PITCHES; pi++) {
                            // Mid-air the view turns quicker than on the ground, and the
                            // player falls while it does: the next click waits for the turn.
                            double guess = pitch(pi, TransmitHops.CHAIN_WAIT);
                            double turn = HandTurn.angle(st.yawDeg(), st.pitchDeg(), yaw(yi),
                                    guess);
                            if (turn > MAX_AIR_TURN) {
                                continue;
                            }
                            int wait = HandTurn.chainWait(turn);
                            casts.add(new int[] {k, yi, pi, wait});
                            views.add(new double[] {pitch(pi, wait), turn});
                        }
                    }
                }
                int[][] cells = new int[casts.size()][3];
                int[] kinds = new int[casts.size()];
                // Only the middle line of each cast here: the others (a nudge of the view, the
                // player a tick early or late) are checked when what the cast leads to is
                // about to be used (see Check), which most never are.
                Check[] checks = new Check[casts.size()];
                java.util.stream.IntStream.range(0, casts.size()).parallel().forEach(c -> {
                    int[] cast = casts.get(c);
                    State st = keep.get(cast[0]);
                    double fx = Pos.x(st.cell()) + 0.5, fy = Pos.y(st.cell()),
                            fz = Pos.z(st.cell()) + 0.5;
                    double[] falls = TransmitHops.chainFallsShared(cast[3]);
                    kinds[c] = TransmitHops.teleport(world, fx, fy - falls[0], fz,
                            yaw(cast[1]), views.get(c)[0], range, TransmitHops.FEET_BELOW[0],
                            cells[c]);
                    if (kinds[c] != TransmitHops.FAILED) {
                        checks[c] = new Check(fx, fy, fz, yaw(cast[1]), views.get(c)[0], falls);
                    }
                });
                int c = 0;
                for (int k = 0; k < keep.size(); k++) {
                    State st = keep.get(k);
                    for (; c < casts.size() && casts.get(c)[0] == k; c++) {
                        int[] cast = casts.get(c);
                        double p = views.get(c)[0], turn = views.get(c)[1];
                        check = checks[c];
                        reached(kinds[c], cells[c], yaw(cast[1]), cast[2], p, n,
                                st.g() + (cast[3] + 1) * TransmitHops.SPRINT_PER_TICK
                                        + turn * TURN_COST + turnCharge(st.yawDeg(), yaw(cast[1])),
                                st, next, st.origin());
                    }
                    check = null;
                    int x = Pos.x(st.cell()), y = Pos.y(st.cell()), z = Pos.z(st.cell());
                    aimed(x, y, z, st.yawDeg(), st.pitchDeg(), 1, n, st.g(), st, next,
                            st.origin());
                }
                layer = next;
            }
            if (best == null) {
                return null;
            }
            List<Found> others = new ArrayList<>();
            long mine = part(best.flight().land());
            for (Map.Entry<Long, Found> e : byPart.entrySet()) {
                double total = partTotal.get(e.getKey());
                if (e.getKey() != mine && total < now - MIN_SAVING
                        && total < bestTotal + BEAM_SLACK) {
                    Flight f = e.getValue().flight();
                    others.add(new Found(f, now - total, walkTo(f.from())));
                }
            }
            alts = others;
            return new Found(best.flight(), now - bestTotal, walkTo(best.flight().from()));
        }

        /**
         * A cast of the flight got the feet into block {@code cell} ({@code kind} as {@link
         * TransmitHops#chainStep}): note the landing it makes if the player stops there, and
         * add the flight to {@code out} if it can go on from there.
         */
        private void reached(int kind, int[] cell, double yawDeg, int pi, double p, int n, double g,
                State parent, List<State> out, long origin) {
            if (kind == TransmitHops.FAILED) {
                return;
            }
            int x = cell[0], y = cell[1], z = cell[2];
            long c = Pos.pack(x, y, z);
            long src = parent == null ? origin : parent.cell();
            double len = dist(src, c);
            int fell = kind == TransmitHops.HIT ? 0 : TransmitHops.fall(world, x, y, z);
            double stop = Double.POSITIVE_INFINITY;
            if (fell >= 0) {
                long ground = Pos.pack(x, y - fell, z);
                double cost = g + TransmitHops.fallTicks(fell) * TransmitHops.SPRINT_PER_TICK;
                // A long drop at the end reads as flying up only to come down again (and
                // lands blind, often against a wall): a player keeps the chain low instead.
                cost += FALL_EXTRA * Math.max(0, fell - 2);
                if (kind == TransmitHops.HIT && len < range - 1) {
                    // Cut short against a block: a player keeps casts clear of walls and floors
                    // unless there's no other way.
                    cost += SHORT_HIT;
                }
                stop = cost + left.left(ground);
                landing(ground, fell, yawDeg, p, n, cost, stop, parent, c, origin);
            }
            boolean floor = fell == 0 && TransmitHops.standing(world, x, y, z);
            if (kind == TransmitHops.HIT && floor && len < range - 1) {
                // Cut short against a block onto a floor: it goes on from there, at the price
                // of the hit.
                g += SHORT_HIT;
            }
            if ((kind == TransmitHops.OPEN || floor) && n < MAX_CASTS && safeBelow(x, y, z)
                    && world.inBounds(x, y - 1, z)
                    && (world.blockAt(x, y - 1, z) == BlockType.AIR || floor)) {
                // How good a flight in the air here looks: stopping here, or if there's
                // nowhere to stop, flying on straight for the goal. A cast onto a floor goes
                // on too: a player clicks again as soon as it shows, as mid-air.
                double est = Math.min(stop, g + FLY_RATE * dist(c, goal) + NO_FLOOR);
                out.add(new State(c, yawIndex(yawDeg), yawDeg, pi, p, n, g, est, parent,
                        origin, check));
            }
        }

        /**
         * The rest of the lines of a cast in the air whose middle line alone has been
         * followed ({@link TransmitHops#chainStep}), checked once, when first needed: the cast
         * counts only if every one ends in the same block.
         */
        final class Check {
            private final double fx, fy, fz, yaw, pitch;
            private final double[] falls;
            private int sure;

            Check(double fx, double fy, double fz, double yaw, double pitch, double[] falls) {
                this.fx = fx;
                this.fy = fy;
                this.fz = fz;
                this.yaw = yaw;
                this.pitch = pitch;
                this.falls = falls;
            }

            boolean sure() {
                if (sure == 0) {
                    airCasts++;
                    sure = TransmitHops.chainStep(world, fx, fy, fz, yaw, pitch, range, falls,
                            new int[3]) == TransmitHops.FAILED ? -1 : 1;
                }
                return sure > 0;
            }
        }

        /** The unchecked lines of the cast {@link #reached} is being told of; null if none. */
        private Check check;

        /** Whether a first cast at {@code yaw} turns back on the way the player faces. */
        private boolean turnsBack(double yaw) {
            if (Double.isNaN(facing)) {
                return false;
            }
            double d = Math.abs(((yaw - facing) % 360 + 540) % 360 - 180);
            return d > MAX_FLOOR_TURN;
        }

        /** Blocks under a spot mid-chain that must be clear of lava, fire and the like. */
        private static final int SAFE_DROP = 4;

        /**
         * Whether a player waiting in block (x, y, z) for the next click can't fall into
         * anything that hurts: the first block under it within {@link #SAFE_DROP}, if any,
         * isn't lava, fire, magma or the like.
         */
        private boolean safeBelow(int x, int y, int z) {
            for (int d = 1; d <= SAFE_DROP; d++) {
                if (!world.inBounds(x, y - d, z)) {
                    return true;
                }
                BlockType b = world.blockAt(x, y - d, z);
                if (b != BlockType.AIR) {
                    return b != BlockType.HAZARD && b != BlockType.MAGMA
                            && b != BlockType.CACTUS && b != BlockType.BERRY_BUSH
                            && b != BlockType.VOID;
                }
            }
            return true;
        }

        /** Walked to a spot a flight may start from: its price, and the move that got there. */
        private record Walk(double cost, int from, int move) {}

        /** The walks from the last {@link #best} call, by spot. */
        private Map<Long, Walk> walks = Map.of();

        /**
         * Spots a few blocks' walk from {@code at} a flight may start from instead (a player
         * walks a little further to cast once where the route would cast twice): {@code at}
         * itself, and up to {@link #WALK_IN_SPOTS} spots up to {@link #WALK_IN} blocks walked
         * away, at least {@link #WALK_IN_GAP} apart, with the walk to each.
         */
        private Map<Long, Walk> walksFrom(long at) {
            MoveGraph g = left.graph;
            int[] start = g.moveStart();
            int[] dest = g.moveTo();
            double[] price = g.moveCost();
            byte[] type = g.moveType();
            long[] pos = g.positions();
            int from = g.cell(at);
            Map<Integer, Walk> reached = new HashMap<>();
            reached.put(from, new Walk(0, -1, -1));
            PriorityQueue<double[]> open = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
            open.add(new double[] {0, from});
            int transmit = MoveType.TRANSMIT.ordinal(), warp = MoveType.WARP.ordinal();
            while (!open.isEmpty()) {
                double[] top = open.poll();
                int u = (int) top[1];
                if (top[0] > reached.get(u).cost()) {
                    continue;
                }
                for (int e = start[u]; e < start[u + 1]; e++) {
                    if (type[e] == transmit || type[e] == warp) {
                        continue;
                    }
                    double c = top[0] + price[e];
                    Walk had = reached.get(dest[e]);
                    if (c <= WALK_IN && (had == null || c < had.cost())) {
                        reached.put(dest[e], new Walk(c, u, e));
                        open.add(new double[] {c, dest[e]});
                    }
                }
            }
            Map<Long, Walk> out = new java.util.LinkedHashMap<>();
            Map<Long, Walk> all = new HashMap<>();
            for (Map.Entry<Integer, Walk> e : reached.entrySet()) {
                all.put(pos[e.getKey()], e.getValue());
            }
            out.put(at, all.get(at));
            List<Map.Entry<Integer, Walk>> far = new ArrayList<>(reached.entrySet());
            far.sort(Comparator.comparingDouble(e -> -e.getValue().cost()));
            List<Long> picked = new ArrayList<>();
            for (Map.Entry<Integer, Walk> e : far) {
                if (picked.size() >= WALK_IN_SPOTS) {
                    break;
                }
                long p = pos[e.getKey()];
                if (sq(p, goal) > sq(at, goal)) {
                    continue; // not back the way it came
                }
                boolean near = sq(p, at) < WALK_IN_GAP * WALK_IN_GAP;
                for (long q : picked) {
                    near |= sq(p, q) < WALK_IN_GAP * WALK_IN_GAP;
                }
                if (!near) {
                    picked.add(p);
                    out.put(p, e.getValue());
                }
            }
            walkCells = all;
            return out;
        }

        /** Every spot reached by the last {@link #walksFrom}, by spot. */
        private Map<Long, Walk> walkCells = Map.of();

        /** The walk onto {@code to} from the spot the last search started at; empty if none. */
        private List<PathStep> walkTo(long to) {
            if (to == start) {
                return List.of();
            }
            List<PathStep> steps = new ArrayList<>();
            long[] pos = left.graph.positions();
            byte[] type = left.graph.moveType();
            MoveType[] types = MoveType.values();
            long c = to;
            Walk w = walkCells.get(c);
            while (w != null && w.from() >= 0) {
                steps.add(0, new PathStep(point(c), types[type[w.move()]]));
                c = pos[w.from()];
                w = walkCells.get(c);
            }
            return steps;
        }

        /**
         * Casts aimed straight at spots on the way, at most, and spots tried for them: the
         * furthest on first.
         */
        private static final int AIMS = 3;
        private static final int AIM_TRIES = 8;
        private static final int FLOOR_AIM_TRIES = 32;
        /** Moves looked along the cheapest way on for spots to aim at. */
        private static final int AHEAD_MOVES = 24;
        /**
         * How far the view may turn for an aimed cast from a floor, standing: degrees. A player
         * turns a corner mid-chain, never back the way they came.
         */
        private static final double MAX_FLOOR_TURN = 90;

        /**
         * Casts from block (x, y, z) aimed straight at spots on the way to the goal ({@link
         * #targets}) up to a cast's reach: the view onto the floor there, so the cast stops on
         * it whatever the distance (a steeper cast goes less far), or for a spot a full cast
         * away, onto it. The spots furthest on (least left to the goal) are tried. {@code air}
         * 1: cast mid-air after a turn from view ({@code yaw0}, {@code pitch0}) at a hand's
         * pace, the click waiting for the turn; 0: the first cast, from the ground.
         */
        private void aimed(int x, int y, int z, double yaw0, double pitch0, int air, int n,
                double g, State parent, List<State> out, long origin) {
            double reach = range - 0.3;
            List<long[]> near = new ArrayList<>();
            // The spots on the way: the route's, and the cheapest way on from where the
            // player comes down here (round a corner the route may not take).
            int down = TransmitHops.fall(world, x, y, z);
            long[] onward = down < 0 ? new long[0]
                    : left.ahead(Pos.pack(x, y - down, z), AHEAD_MOVES);
            java.util.Set<Long> had = new java.util.HashSet<>();
            for (long[] list : new long[][] {targets, onward}) {
                for (long t : list) {
                    if (!had.add(t)) {
                        continue;
                    }
                    double dx = Pos.x(t) - x, dy = Pos.y(t) - y, dz = Pos.z(t) - z;
                    double d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 <= (reach + 1) * (reach + 1) && d2 >= 9) {
                        near.add(new long[] {t});
                    }
                }
            }
            near.sort(Comparator.comparingDouble(a -> left.left(a[0])));
            int[] cell = new int[3];
            boolean standing = air == 1 && TransmitHops.standing(world, x, y, z);
            int landed = 0;
            int tries = standing || air == 0 ? FLOOR_AIM_TRIES : AIM_TRIES;
            for (int k = 0; k < Math.min(tries, near.size()) && landed < AIMS; k++) {
                long t = near.get(k)[0];
                double tx = Pos.x(t) + 0.5, ty = Pos.y(t), tz = Pos.z(t) + 0.5;
                double yaw = Math.toDegrees(Math.atan2(-(tx - x - 0.5), tz - z - 0.5));
                int wait = 0;
                double turn = 0;
                double p = 0;
                // The fall while turning changes the pitch a little, and so the turn: twice.
                for (int it = 0; it < 2; it++) {
                    // Standing on a floor, the player doesn't fall while turning.
                    double fell = air == 1 && !standing ? HandTurn.fall(wait) : 0;
                    double eye = y - fell + TransmitHops.FEET_BELOW[0];
                    double h = Math.hypot(tx - x - 0.5, tz - z - 0.5);
                    double dist = Math.hypot(h, ty - eye);
                    // Onto the floor there if that's within reach; else a full cast along
                    // that line, which puts the feet 1.62 under its end.
                    p = dist <= reach ? Math.toDegrees(Math.atan2(eye - ty, h))
                            : Math.toDegrees(Math.atan2(eye - (ty + TransmitHops.FEET_BELOW[0]),
                                    h));
                    if (air == 1) {
                        turn = HandTurn.angle(yaw0, pitch0, yaw, p);
                        wait = HandTurn.chainWait(turn);
                    }
                }
                if (air == 1 && turn > (standing ? MAX_FLOOR_TURN : MAX_AIR_TURN) || p < -90
                        || p > 90 || air == 0 && turnsBack(yaw)) {
                    continue;
                }
                aimedCasts++;
                int kind = TransmitHops.chainStep(world, x + 0.5, y, z + 0.5, yaw, p, range,
                        air == 0 ? null : standing ? new double[] {0}
                                : TransmitHops.chainFallsShared(wait), cell);
                if (kind == TransmitHops.FAILED) {
                    continue;
                }
                landed++;
                double cost = air == 1 ? g + (wait + 1) * TransmitHops.SPRINT_PER_TICK
                        + turn * TURN_COST + turnCharge(yaw0, yaw) : g;
                reached(kind, cell, yaw, -1, p, n, cost, parent, out, origin);
            }
        }

        private void landing(long ground, int fell, double yawDeg, double p, int n, double cost,
                double total, State parent, long lastCell, long origin) {
            if (sq(ground, origin) < minSq) {
                return;
            }
            long part = part(ground);
            Double had = partTotal.get(part);
            boolean better = total < bestTotal;
            boolean other = total < bestTotal + BEAM_SLACK && (had == null || total < had);
            if (!better && !other) {
                return;
            }
            if (check != null && !check.sure()) {
                return;
            }
            if (!TransmitHops.standing(world, Pos.x(ground), Pos.y(ground), Pos.z(ground))) {
                return;
            }
            if (better) {
                bestTotal = total;
            }
            float[] yaws = new float[n], pitches = new float[n];
            List<Long> through = new ArrayList<>();
            yaws[n - 1] = (float) yawDeg;
            pitches[n - 1] = (float) p;
            int m = n - 2;
            // The turn charges are for picking flights only: out of the time it takes.
            double charged = 0;
            double after = yawDeg;
            for (State s = parent; s != null; s = s.parent(), m--) {
                charged += turnCharge(s.yawDeg(), after);
                after = s.yawDeg();
                yaws[m] = (float) s.yawDeg();
                pitches[m] = (float) s.pitchDeg();
                through.add(0, s.cell());
            }
            through.add(lastCell);
            if (fell > 0) {
                through.add(ground);
            }
            Found f = new Found(new Flight(origin, ground, yaws, pitches, through,
                    cost - charged), 0, null);
            if (had == null || total < had) {
                byPart.put(part, f);
                partTotal.put(part, total);
            }
            if (better) {
                best = f;
            }
        }
    }

    /**
     * Where the time of {@link #straighten} goes, summed over calls since the last {@link
     * Stats#reset}: for the route tool's report.
     */
    public static final class Stats {
        public long plans;
        public long searches;
        public long toGoalNs;
        public long searchNs;
        public long airCasts;
        public long aimedCasts;

        synchronized void add(long air, long aimed) {
            searches++;
            airCasts += air;
            aimedCasts += aimed;
        }

        public void reset() {
            plans = searches = toGoalNs = searchNs = airCasts = aimedCasts = 0;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "%d plans: cost-to-goal %.0f ms, %d"
                    + " flight searches %.0f ms; %,d casts checked in the air, %,d aimed",
                    plans, toGoalNs / 1e6, searches, searchNs / 1e6, airCasts, aimedCasts);
        }
    }

    /** Not thread-safe counts, for the route tool's report only. */
    public static final Stats STATS = new Stats();

    static double yaw(int yi) {
        return yi * 360.0 / YAWS - 180;
    }

    static int yawIndex(double yaw) {
        return Math.floorMod((int) Math.round((yaw + 180) * YAWS / 360.0), YAWS);
    }

    /** The yaw from block {@code a} toward block {@code b}. */
    static double yawTo(long a, long b) {
        double dx = Pos.x(b) - Pos.x(a), dz = Pos.z(b) - Pos.z(a);
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    private static double dist(long a, long b) {
        return Math.sqrt(sq(a, b));
    }

    private static double sq(long a, long b) {
        double dx = Pos.x(a) - Pos.x(b), dy = Pos.y(a) - Pos.y(b), dz = Pos.z(a) - Pos.z(b);
        return dx * dx + dy * dy + dz * dz;
    }
}

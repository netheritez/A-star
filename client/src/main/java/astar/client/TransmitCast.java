package astar.client;

import astar.movement.Keys;
import astar.pathing.HandTurn;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * Casts one Instant Transmission, or a chain of them through the air, a tick at a time, once
 * /goto has walked to where it's cast from. Ported from the old pathfinder's PathFollower:
 *
 * <ul>
 *   <li>Come to rest on the spot, turning the view onto the cast meanwhile: let go if that
 *       stops the player within {@link #LINE_UP_MOST} of its middle and the cast lands from
 *       there; else one step, a key held for just as long as stops nearest the middle; only
 *       if steps don't do, walk and brake taps to within {@link #LINE_UP} (never sneaking).
 *   <li>Check the cast from where the feet really are, on the map, and nudge the view if it
 *       wouldn't put them in the block the route says; turn onto it at a hand's pace, hold it
 *       for {@link #AIM_SETTLE_TICKS} ticks within {@link #AIM_TOLERANCE} degrees, click.
 *   <li>An air chain: as soon as each teleport shows, turn quickly onto the next cast's view,
 *       check it from where the player now is (nudging it if it misses), and click the tick
 *       after it settles: clicking away while flying on, with no gap forced between clicks.
 *   <li>After the last click, turn on toward what's next while landing.
 *   <li>A cast from where the last transmission just landed, with no walk between, goes on
 *       as a chain does: no lining up, a quick turn, clicked the tick after it settles.
 * </ul>
 *
 * <p>In single player, where there's no Aspect of the Void, the click is a {@code /tp} onto that
 * spot instead, to try routes out.
 */
final class TransmitCast implements HopCast {

    /** How near the middle of the spot to come to rest before casting, in blocks. */
    static final double LINE_UP = 0.05;
    /** Ticks to line up before casting from wherever the player is. */
    private static final int LINE_UP_TICKS = 40;
    /**
     * How far from the middle of the spot the player may come to rest and cast, if the cast
     * lands from there: the casts are planned sure from anywhere this near.
     */
    private static final double LINE_UP_MOST = 0.3;
    /**
     * Steps onto the spot before falling back on taps: each holds one way for as many ticks
     * as bring the player to rest nearest the middle, then lets go, as a player would.
     */
    private static final int STEPS = 2;
    /** Ticks a step holds its keys, at most, and brakes (the opposite keys) after. */
    private static final int STEP_MOST = 16;
    private static final int BRAKE_MOST = 3;
    /** Ticks to turn onto the view before giving up. */
    private static final int AIM_TICKS = 40;
    /** The view counts as on the cast within this many degrees. */
    private static final float AIM_TOLERANCE = 1F;
    /** Ticks the view is held on a cast from the ground before the click. */
    private static final int AIM_SETTLE_TICKS = 3;
    /** Ticks the view is held on a cast mid-air before the click. */
    private static final int CHAIN_SETTLE_TICKS = 1;
    /** Ticks to wait for a cast from the ground to show before clicking again, and clicks. */
    private static final int TELEPORT_TICKS = 20;
    private static final int CLICKS = 3;
    /**
     * Ticks to wait for each teleport of a chain to show after its click. The server casts it
     * as soon as the click arrives; only word of it can be late, when the game lags.
     */
    private static final int CHAIN_TICKS = 20;
    /** Ticks to turn onto a chain's next cast before giving up. */
    private static final int CHAIN_AIM_TICKS = 40;
    /** Views worked out again for one cast of a chain, at most. */
    private static final int REAIMS = 2;
    /** Ticks to wait for the fall after the teleport. */
    private static final int FALL_TICKS = 60;
    /** Prints each change of phase, to see where a cast waits. */
    private static final boolean TRACE = Boolean.getBoolean("astar.trace.casts");

    /** Checks casts on the map the route was planned on; positions in the world. */
    interface Aimer {
        /**
         * Whether (yaw, pitch) puts the feet in the block cast {@code k} of the hop should,
         * from each of {@code feet}; in the air ({@code air}) or from the ground.
         */
        boolean lands(double[][] feet, float yaw, float pitch, int k, boolean air);

        /** The nearest view to (yaw, pitch) that does; null if there's none. */
        float[] view(double[][] feet, float yaw, float pitch, int k, boolean air);

        /**
         * A view that lands cast {@code k} from the ground at each of {@code feet}, facing
         * the block it should land in, near {@code pitch}; null if there's none.
         */
        float[] from(double[][] feet, float pitch, int k);
    }

    private enum Phase {
        LINE_UP, AIM, SHOW, CHAIN, FALL
    }

    private final Navigator.GameController controller;
    private final BlockPos spot;
    /** Each cast's view, in order. */
    private final float[] yaws;
    private final float[] pitches;
    /** Casts in a row. */
    private final int chain;
    /** Casts checked on the map; null if the map isn't there. */
    private final Aimer aimer;
    /** The middle of the spot it's cast from, in the world. */
    private final Vec3 from;
    /** The view to turn toward once clicked (the next hop or walk), or null to hold still. */
    private final float[] after;
    /** The view the current cast is to be clicked at. */
    private float wantYaw;
    private float wantPitch;
    private Phase phase = Phase.LINE_UP;
    /**
     * Cast from where the last transmission just landed, with no walk between: it goes on
     * like an air chain, clicked as soon as the view is on it, without lining up.
     */
    private final boolean goesOn;
    /** Letting go to come to rest where the line-up has got to, near enough. */
    private boolean settling;
    /**
     * The step under way: its keys (one of {@link #PRESSES}) and the yaw it was planned at,
     * which the view keeps until it's done so the keys push the way planned; ticks still to
     * hold them, then the opposite keys; and steps taken.
     */
    private int stepPress;
    private float stepYaw;
    private int stepTicks;
    private int brakeTicks;
    private int steps;
    /** Ticks the line-up held keys, for the trace. */
    private int keyTicks;
    private int ticks;
    /** Clicks for the current cast, all clicks, and teleports seen. */
    private int castClicks;
    private int clicks;
    private int teleports;
    private int settled;
    /**
     * Ticks from a teleport showing to the next click of the chain: the user's own rhythm,
     * {@link HandTurn#chainWait}, as the route was planned with.
     */
    private int clickAfter;
    private int reaims;
    /** Where the player was, and going, a tick ago: a teleport is a jump off that. */
    private Vec3 lastPos;
    private Vec3 lastVel;
    private final HandAim hand = new HandAim();
    private final HandAim onward = new HandAim();
    private String reason = "";

    /**
     * @param spot the block the feet should end in, in the world
     * @param yaws each cast's yaw, in order (one: a single cast)
     * @param pitches each cast's pitch
     */
    TransmitCast(Navigator.GameController controller, BlockPos spot, float[] yaws,
            float[] pitches, float[] after, Aimer aimer, Vec3 from, boolean goesOn) {
        this.goesOn = goesOn;
        this.yaws = yaws;
        this.pitches = pitches;
        this.chain = yaws.length;
        this.aimer = aimer;
        this.from = from;
        this.controller = controller;
        this.after = after;
        this.spot = spot;
        this.wantYaw = yaws[0];
        this.wantPitch = pitches[0];
    }

    @Override
    public String reason() {
        return reason;
    }

    @Override
    public Status tick(Minecraft client) {
        LocalPlayer player = client.player;
        ticks++;
        float nowYaw = player.getYRot();
        float nowPitch = player.getXRot();
        Vec3 pos = player.position();
        boolean jumped = lastPos != null && pos.distanceTo(lastPos.add(lastVel)) > 1.0;
        lastPos = pos;
        lastVel = player.getDeltaMovement();
        switch (phase) {
            case LINE_UP -> {
                if (goesOn) {
                    checkAim(new double[][] {{pos.x, pos.y, pos.z}}, 0, false);
                    hand.reset();
                    next(Phase.AIM);
                    clickAfter = HandTurn.chainWait(HandTurn.angle(nowYaw, nowPitch, wantYaw,
                            wantPitch));
                    float[] v = hand.step(nowYaw, nowPitch, wantYaw, wantPitch, true);
                    controller.tick(Keys.NONE, v[0], v[1]);
                    return Status.RUNNING;
                }
                // The view holds still through a step, so its keys push the way planned, and
                // turns onto the cast after.
                boolean stepping = stepTicks > 0 || brakeTicks > 0;
                float[] v = stepping ? new float[] {stepYaw, nowPitch}
                        : hand.step(nowYaw, nowPitch, wantYaw, wantPitch, false);
                double dx = pos.x - from.x, dz = pos.z - from.z;
                Vec3 vel = player.getDeltaMovement();
                boolean still = vel.x * vel.x + vel.z * vel.z < 1e-6;
                // Let go as soon as that stops the player near enough; else one step that
                // stops nearest the middle, not a string of taps edging onto it.
                Keys keys = Keys.NONE;
                if (player.onGround() && !settling) {
                    if (stepTicks == 0 && brakeTicks == 0 && steps < STEPS) {
                        planStep(client, player, nowYaw);
                        if (stepTicks > 0 || brakeTicks > 0) {
                            v = new float[] {stepYaw, nowPitch};
                            hand.reset();
                        }
                    }
                    if (stepTicks > 0) {
                        stepTicks--;
                        keys = press(stepPress, 1);
                    } else if (brakeTicks > 0) {
                        brakeTicks--;
                        keys = press(stepPress, -1);
                    } else if (!settling) {
                        keys = lineUpKeys(client, player, v[0]);
                    }
                }
                if (player.onGround() && still && keys.equals(Keys.NONE)
                        && (dx * dx + dz * dz < LINE_UP * LINE_UP || settling)
                        || ticks > LINE_UP_TICKS && player.onGround()) {
                    controller.tick(Keys.NONE, v[0], v[1]);
                    if (dx * dx + dz * dz >= LINE_UP * LINE_UP && !settling) {
                        System.out.printf(java.util.Locale.ROOT,
                                "[astar] transmission: lined up only to %.3f blocks%n",
                                Math.sqrt(dx * dx + dz * dz));
                    }
                    checkAim(new double[][] {{pos.x, pos.y, pos.z}}, 0, false);
                    next(Phase.AIM);
                } else if (ticks > LINE_UP_TICKS * 2) {
                    return fail("couldn't get onto the ground to cast");
                } else {
                    if (!keys.equals(Keys.NONE)) {
                        keyTicks++;
                    }
                    controller.tick(keys, v[0], v[1]);
                }
            }
            case AIM -> {
                // A hand's sweep onto the cast, then a beat with the view still: the server
                // casts along the view it was last sent.
                float[] v = hand.step(nowYaw, nowPitch, wantYaw, wantPitch, goesOn);
                settled = hand.left() < AIM_TOLERANCE ? settled + 1 : 0;
                controller.tick(Keys.NONE, v[0], v[1]);
                boolean ready = goesOn && clicks == 0
                        ? settled >= CHAIN_SETTLE_TICKS && ticks >= clickAfter
                        : settled > AIM_SETTLE_TICKS;
                double[][] here = {{pos.x, pos.y, pos.z}};
                if (ready && clicks == 0 && aimer != null
                        && !aimer.lands(here, nowYaw, nowPitch, 0, false)
                        && nudge(here, nowYaw, nowPitch, 0, false)) {
                    // Nudged onto the cast: clicked next tick.
                } else if (ready && !player.isCrouching()) {
                    if (!click(client, player)) {
                        return fail("there's no Aspect of the Void in the hotbar");
                    }
                    next(Phase.SHOW);
                } else if (ticks > AIM_TICKS) {
                    return fail("couldn't turn onto the cast");
                }
            }
            case SHOW -> {
                // Waiting for the click's teleport: held still if more casts follow, else
                // already turning on to what's next, as a player does once the click is in.
                boolean last = clicks >= chain;
                float[] v = last ? turnOn(nowYaw, nowPitch) : new float[] {nowYaw, nowPitch};
                controller.tick(Keys.NONE, v[0], v[1]);
                if (jumped || teleports >= chain) {
                    teleports = Math.max(teleports, clicks);
                    if (teleports >= chain) {
                        next(Phase.FALL);
                    } else {
                        // Straight on to the next cast, quickly, from where this one put us:
                        // the turn starts this tick.
                        wantYaw = yaws[teleports];
                        wantPitch = pitches[teleports];
                        castClicks = 0;
                        reaims = 0;
                        hand.reset();
                        next(Phase.CHAIN);
                        clickAfter = HandTurn.chainWait(HandTurn.angle(nowYaw, nowPitch, wantYaw,
                                wantPitch));
                        return chainTick(client, player, nowYaw, nowPitch, pos);
                    }
                } else if (teleports == 0 && ticks > TELEPORT_TICKS) {
                    if (castClicks >= CLICKS) {
                        return fail("the transmission to " + spot.toShortString()
                                + " didn't happen (blocks in the way, or out of mana?)");
                    }
                    hand.reset();
                    next(Phase.AIM);
                } else if (teleports > 0 && ticks > CHAIN_TICKS) {
                    return fail("the chain broke after " + teleports + " of " + chain
                            + " casts (blocks in the way, or out of mana?)");
                }
            }
            case CHAIN -> {
                return chainTick(client, player, nowYaw, nowPitch, pos);
            }
            case FALL -> {
                float[] v = turnOn(nowYaw, nowPitch);
                controller.tick(Keys.NONE, v[0], v[1]);
                if (player.onGround()) {
                    BlockPos at = player.blockPosition();
                    // An air chain's last cast, from a player already falling, may land a block
                    // off: the route goes on from there.
                    int off = chain == 1 ? 0 : 1;
                    if (at.getY() == spot.getY() && Math.abs(at.getX() - spot.getX()) <= off
                            && Math.abs(at.getZ() - spot.getZ()) <= off) {
                        return Status.LANDED;
                    }
                    return fail("landed at " + at.toShortString() + ", not "
                            + spot.toShortString());
                }
                if (ticks > FALL_TICKS) {
                    return fail("still falling after the transmission");
                }
            }
        }
        return Status.RUNNING;
    }

    /**
     * One tick of an air chain's next cast: once the view has been on it for
     * {@link #CHAIN_SETTLE_TICKS} (the server has it), check the cast from where the player is
     * and click, or work out a view that lands it and turn onto that; else keep turning.
     */
    private Status chainTick(Minecraft client, LocalPlayer player, float nowYaw, float nowPitch,
            Vec3 pos) {
        if (settled >= CHAIN_SETTLE_TICKS && ticks >= clickAfter) {
            double[][] here = {{pos.x, pos.y, pos.z}};
            boolean lands = aimer == null
                    || aimer.lands(here, nowYaw, nowPitch, teleports, true);
            if (!lands && !player.onGround() && landsSoon(player, nowYaw, nowPitch)) {
                // Clicked as early as the route has it: the view lands it once the player has
                // fallen a little further, as the route's turn would have taken them.
                controller.tick(Keys.NONE, nowYaw, nowPitch);
                return ticks > CHAIN_AIM_TICKS ? fail("couldn't turn onto cast "
                        + (teleports + 1) + " of " + chain) : Status.RUNNING;
            }
            if (!lands && nudge(here, nowYaw, nowPitch, teleports, true)) {
                return Status.RUNNING;
            }
            if (lands || reaims >= REAIMS || !reaimInAir(player, reaims++ == 0)) {
                controller.tick(Keys.NONE, nowYaw, nowPitch);
                if (!click(client, player)) {
                    return fail("the Aspect of the Void went from the hotbar");
                }
                next(Phase.SHOW);
                return Status.RUNNING;
            }
            settled = 0;
        }
        float[] v = hand.step(nowYaw, nowPitch, wantYaw, wantPitch, true);
        controller.tick(Keys.NONE, v[0], v[1]);
        settled = hand.left() < AIM_TOLERANCE ? settled + 1 : 0;
        if (ticks > CHAIN_AIM_TICKS) {
            return fail("couldn't turn onto cast " + (teleports + 1) + " of " + chain);
        }
        return Status.RUNNING;
    }

    /** Degrees the view is nudged at most, the tick before a click. */
    private static final float NUDGE_MOST = 3F;
    /** Nudges for one cast, at most. */
    private static final int NUDGES = 2;
    private int nudges;

    /**
     * When the view is near but not quite on a cast that lands from {@code here} (the hand
     * stops within {@link #AIM_TOLERANCE}, and a cast 12 blocks out can land a block off by
     * less), a last small move of the mouse onto it; clicked next tick. Whether it nudged.
     */
    private boolean nudge(double[][] here, float nowYaw, float nowPitch, int k, boolean air) {
        if (aimer == null || nudges >= NUDGES) {
            return false;
        }
        float[] v = aimer.lands(here, wantYaw, wantPitch, k, air)
                ? new float[] {wantYaw, wantPitch}
                : aimer.view(here, nowYaw, nowPitch, k, air);
        if (v == null || HandTurn.angle(nowYaw, nowPitch, v[0], v[1]) > NUDGE_MOST) {
            return false;
        }
        nudges++;
        wantYaw = v[0];
        wantPitch = v[1];
        controller.tick(Keys.NONE, v[0], v[1]);
        return true;
    }

    /**
     * Keeps the planned view for cast {@code k} if it lands right from {@code feet}, else the
     * nearest view that does; the planned one if none does.
     */
    private void checkAim(double[][] feet, int k, boolean air) {
        if (aimer == null || aimer.lands(feet, wantYaw, wantPitch, k, air)) {
            return;
        }
        float[] v = aimer.view(feet, wantYaw, wantPitch, k, air);
        if (v != null) {
            wantYaw = v[0];
            wantPitch = v[1];
        } else {
            System.out.printf("[astar] transmission %d/%d: no view lands it from here%n", k + 1,
                    chain);
        }
    }

    /** Ticks ahead a chain's next click may wait for the fall to bring its cast right. */
    private static final int WAIT_AHEAD = 6;

    /**
     * Whether this view lands the chain's next cast from where the player will be, falling,
     * one of the next {@link #WAIT_AHEAD} ticks.
     */
    private boolean landsSoon(LocalPlayer player, float yaw, float pitch) {
        if (aimer == null) {
            return false;
        }
        Vec3 p = player.position();
        Vec3 v = player.getDeltaMovement();
        double x = p.x, y = p.y, z = p.z, vx = v.x, vy = v.y, vz = v.z;
        for (int t = 1; t <= WAIT_AHEAD; t++) {
            x += vx;
            y += vy;
            z += vz;
            vx *= 0.91;
            vz *= 0.91;
            vy = (vy - 0.08) * 0.98;
            if (aimer.lands(new double[][] {{x, y, z}}, yaw, pitch, teleports, true)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Mid-air, the view for the next cast worked out from where the player will be once the
     * view has moved onto it (falling meanwhile); false if there's none.
     */
    private boolean reaimInAir(LocalPlayer player, boolean log) {
        Vec3 p = player.position();
        Vec3 v = player.getDeltaMovement();
        int n = (int) Math.ceil(HandTurn.turnTicks(3, true)) + CHAIN_SETTLE_TICKS;
        double[][] feet = new double[3][];
        double x = p.x, y = p.y, z = p.z, vx = v.x, vy = v.y, vz = v.z;
        if (player.onGround()) {
            // Standing on a floor mid-chain: no fall to allow for.
            feet = new double[][] {{x, y, z}};
            n = -1;
        }
        for (int t = 1; t <= n + 1; t++) {
            x += vx;
            y += vy;
            z += vz;
            vx *= 0.91;
            vz *= 0.91;
            vy = (vy - 0.08) * 0.98;
            if (t >= n - 1) {
                feet[t - (n - 1)] = new double[] {x, y, z};
            }
        }
        float[] view = aimer.view(feet, wantYaw, wantPitch, teleports, true);
        if (view == null) {
            if (log) {
                System.out.printf("[astar] transmission %d/%d: no view lands it from here%n",
                        teleports + 1, chain);
            }
            return false;
        }
        wantYaw = view[0];
        wantPitch = view[1];
        return true;
    }

    /** Key presses to try each tick of the line-up: forward/back and left/right, or nothing. */
    private static final int[][] PRESSES = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1},
        {1, -1}, {-1, 1}, {-1, -1}};
    /** Ticks looked ahead in the line-up. */
    private static final int LOOK_AHEAD = 3;

    /**
     * This tick's keys to come to rest on the spot: of every run of presses over the next
     * {@link #LOOK_AHEAD} ticks (then letting go), the first of the one that stops nearest
     * the middle, preferring fewer and earlier presses; nothing once letting go stops near
     * enough. Walking only, never sneaking: the game's own ground movement, simulated.
     */
    private Keys lineUpKeys(Minecraft client, LocalPlayer player, float yaw) {
        var level = client.level;
        BlockPos below = player.getBlockPosBelowThatAffectsMyMovement();
        double f = level.getBlockState(below).getBlock().getFriction();
        double push = player.getSpeed() * 0.98 * (0.21600002 / (f * f * f));
        double slide = f * 0.91;
        Vec3 p = player.position();
        Vec3 v = player.getDeltaMovement();
        double px = p.x - from.x, pz = p.z - from.z;
        double[] rest = new double[2];
        restOf(px, pz, v.x, v.z, slide, rest);
        if (Math.hypot(rest[0], rest[1]) < LINE_UP * 0.8) {
            return Keys.NONE;
        }
        double sin = Math.sin(Math.toRadians(yaw)), cos = Math.cos(Math.toRadians(yaw));
        double[][] dirs = new double[PRESSES.length][2];
        for (int i = 1; i < PRESSES.length; i++) {
            double fw = PRESSES[i][0], st = PRESSES[i][1];
            double n = Math.hypot(fw, st);
            fw /= n;
            st /= n;
            dirs[i][0] = push * (st * cos - fw * sin);
            dirs[i][1] = push * (fw * cos + st * sin);
        }
        int best = 0;
        double bestCost = Double.MAX_VALUE;
        int runs = (int) Math.pow(PRESSES.length, LOOK_AHEAD);
        for (int run = 0; run < runs; run++) {
            double x = px, z = pz, vx = v.x, vz = v.z;
            int r = run, presses = 0, lastPress = 0, first = 0;
            for (int t = 0; t < LOOK_AHEAD; t++) {
                int k = r % PRESSES.length;
                r /= PRESSES.length;
                if (t == 0) {
                    first = k;
                }
                if (k != 0) {
                    presses++;
                    lastPress = t;
                }
                vx = Math.abs(vx) < 0.003 ? 0 : vx;
                vz = Math.abs(vz) < 0.003 ? 0 : vz;
                vx += dirs[k][0];
                vz += dirs[k][1];
                x += vx;
                z += vz;
                vx *= slide;
                vz *= slide;
            }
            restOf(x, z, vx, vz, slide, rest);
            double cost = Math.hypot(rest[0], rest[1]) + 0.002 * presses + 0.001 * lastPress;
            if (cost < bestCost) {
                bestCost = cost;
                best = first;
            }
        }
        int fw = PRESSES[best][0], st = PRESSES[best][1];
        return new Keys(fw > 0, st > 0, fw < 0, st < 0, false, false, false);
    }

    /** A way to come to rest: press {@code i} for {@code n} ticks, braked for {@code b}. */
    private record Stop(int i, int n, int b, double x, double z, double off) {}

    /** Views worked out for places to stop, at most, each time a stop is planned. */
    private static final int STOP_CHECKS = 40;
    /** How far from the spot's middle it may stop and cast from, in blocks. */
    private static final double STOP_MOST = 1.5;

    /**
     * Plans how to come to rest and cast, quickest first: letting go now, or one step (of
     * the eight ways the keys push, held for up to {@link #STEP_MOST} ticks, braked with the
     * opposite keys for up to {@link #BRAKE_MOST}, then let go), taking the first place it
     * stops from which a view lands the cast in the block the route has, which becomes the
     * view to cast at. The spot's middle needn't be reached: the cast is aimed afresh from
     * wherever the player stops. If no place checked will do, the step that stops nearest
     * the middle.
     */
    private void planStep(Minecraft client, LocalPlayer player, float yaw) {
        BlockPos below = player.getBlockPosBelowThatAffectsMyMovement();
        double f = client.level.getBlockState(below).getBlock().getFriction();
        // The speed read now has the sprint boost in it while sprinting, which lasts as long
        // as forward is held.
        double push = player.getSpeed() * 0.98 * (0.21600002 / (f * f * f));
        boolean sprinting = player.isSprinting();
        double walkPush = sprinting ? push / 1.3 : push;
        double slide = f * 0.91;
        Vec3 p = player.position();
        Vec3 v = player.getDeltaMovement();
        double[] rest = new double[2];
        List<Stop> stops = new ArrayList<>();
        restOf(p.x, p.z, v.x, v.z, slide, rest);
        stops.add(new Stop(0, 0, 0, rest[0], rest[1], Math.hypot(rest[0] - from.x,
                rest[1] - from.z)));
        for (int i = 1; i < PRESSES.length; i++) {
            double[] d = pushOf(yaw, i);
            double on = sprinting && PRESSES[i][0] > 0 ? push : walkPush;
            for (int n = 0; n <= STEP_MOST; n++) {
                for (int b = n == 0 ? 1 : 0; b <= BRAKE_MOST; b++) {
                    double x = p.x, z = p.z, vx = v.x, vz = v.z;
                    for (int t = 0; t < n + b; t++) {
                        double sign = t < n ? on : -walkPush;
                        vx = Math.abs(vx) < 0.003 ? 0 : vx;
                        vz = Math.abs(vz) < 0.003 ? 0 : vz;
                        vx += sign * d[0];
                        vz += sign * d[1];
                        x += vx;
                        z += vz;
                        vx *= slide;
                        vz *= slide;
                    }
                    restOf(x, z, vx, vz, slide, rest);
                    stops.add(new Stop(i, n, b, rest[0], rest[1],
                            Math.hypot(rest[0] - from.x, rest[1] - from.z)));
                }
            }
        }
        Stop nearest = stops.stream().min(Comparator.comparingDouble(
                st -> st.off() + 0.002 * (st.n() + st.b()))).orElseThrow();
        Stop chosen = null;
        float[] view = null;
        if (aimer != null) {
            stops.sort(Comparator.comparingInt((Stop st) -> st.n() + st.b())
                    .thenComparingDouble(Stop::off));
            int checked = 0;
            for (Stop st : stops) {
                if (st.off() > STOP_MOST) {
                    continue;
                }
                if (++checked > STOP_CHECKS) {
                    break;
                }
                double[][] feet = {{st.x(), p.y, st.z()}};
                view = aimer.from(feet, pitches[0], 0);
                if (view != null) {
                    chosen = st;
                    break;
                }
            }
        } else if (nearest.off() < LINE_UP_MOST) {
            chosen = nearest;
        }
        stepYaw = yaw;
        if (chosen != null && view != null) {
            wantYaw = view[0];
            wantPitch = view[1];
        }
        Stop go = chosen != null ? chosen : nearest;
        if (go.n() + go.b() == 0) {
            // Letting go does, or nothing does better.
            settling = chosen != null;
            stepTicks = 0;
            brakeTicks = 0;
        } else {
            steps++;
            stepPress = go.i();
            stepTicks = go.n();
            brakeTicks = go.b();
        }
        if (TRACE) {
            System.out.printf(java.util.Locale.ROOT,
                    "[astar] transmission stop: %d ticks and %d braking, rests %.3f off%s"
                            + " (going %.4f %.4f, push %.4f, sprinting %b)%n", go.n(), go.b(),
                    go.off(), chosen != null ? ", casts from there" : ", nearest", v.x, v.z,
                    push, sprinting);
        }
    }

    /** Unit way in the world that press {@code i} of {@link #PRESSES} pushes, facing yaw. */
    private static double[] pushOf(float yaw, int i) {
        double sin = Math.sin(Math.toRadians(yaw)), cos = Math.cos(Math.toRadians(yaw));
        double fw = PRESSES[i][0], st = PRESSES[i][1];
        double n = Math.hypot(fw, st);
        fw /= n;
        st /= n;
        return new double[] {st * cos - fw * sin, fw * cos + st * sin};
    }

    /** The keys of press {@code i} of {@link #PRESSES}, or the opposite ones (sign -1). */
    private static Keys press(int i, int sign) {
        int fw = PRESSES[i][0] * sign, st = PRESSES[i][1] * sign;
        return new Keys(fw > 0, st > 0, fw < 0, st < 0, false, false, false);
    }

    /** Where a player at (x, z) going (vx, vz) on the ground comes to rest, letting go. */
    private static void restOf(double x, double z, double vx, double vz, double slide,
            double[] out) {
        for (int t = 0; t < 60 && (vx != 0 || vz != 0); t++) {
            vx = Math.abs(vx) < 0.003 ? 0 : vx;
            vz = Math.abs(vz) < 0.003 ? 0 : vz;
            x += vx;
            z += vz;
            vx *= slide;
            vz *= slide;
        }
        out[0] = x;
        out[1] = z;
    }

    /** Turns at a hand's pace toward what comes after the cast; holds still if nothing. */
    private float[] turnOn(float nowYaw, float nowPitch) {
        if (after == null) {
            return new float[] {nowYaw, nowPitch};
        }
        return onward.step(nowYaw, nowPitch, after[0], after[1], false);
    }

    private void next(Phase p) {
        if (TRACE) {
            LocalPlayer pl = Minecraft.getInstance().player;
            Vec3 at = pl.position();
            System.out.printf(java.util.Locale.ROOT,
                    "[astar] transmission %s -> %s after %d ticks, %.3f off, speed %.4f,"
                            + " ground %b, keys held %d ticks in %d steps%n",
                    phase, p, ticks, Math.hypot(at.x - from.x, at.z - from.z),
                    pl.getDeltaMovement().horizontalDistance(), pl.onGround(), keyTicks, steps);
        }
        phase = p;
        ticks = 0;
        settled = 0;
        nudges = 0;
    }

    private Status fail(String why) {
        reason = why;
        return Status.FAILED;
    }

    /** Right-clicks the Aspect of the Void; false if there's none to click. */
    private boolean click(Minecraft client, LocalPlayer player) {
        clicks++;
        castClicks++;
        Vec3 p = player.position();
        System.out.printf(java.util.Locale.ROOT,
                "[astar] transmission click %d/%d at %.2f %.2f %.2f%s view %.1f %.1f%n",
                clicks, chain, p.x, p.y, p.z, clicks == 1 ? String.format(java.util.Locale.ROOT,
                        " (%.3f off the spot)", Math.hypot(p.x - from.x, p.z - from.z)) : "",
                player.getYRot(), player.getXRot());
        int slot = WarpCast.aotvSlot(player);
        if (slot >= 0) {
            player.getInventory().setSelectedSlot(slot);
            client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        } else if (client.hasSingleplayerServer()) {
            // Trying routes out in single player: straight onto the spot the cast falls to
            // (the whole of a chain at once).
            teleports = chain;
            client.getConnection().sendCommand(String.format(java.util.Locale.ROOT,
                    "tp @s %.3f %.3f %.3f", spot.getX() + 0.5, (double) spot.getY(),
                    spot.getZ() + 0.5));
        } else {
            return false;
        }
        return true;
    }
}

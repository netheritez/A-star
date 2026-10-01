package astar.client;

import astar.movement.Keys;
import astar.movement.trace.BlockRecord;
import astar.movement.trace.TickRecord;
import astar.movement.trace.Trace;
import astar.movement.trace.TraceEvent;
import astar.movement.trace.TraceHeader;
import astar.movement.trace.TraceWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Writes one trace: the player's inputs and movement every tick, and the blocks around them.
 *
 * <p>{@link #startTick} runs before the game ticks the player and {@link #endTick} after, so
 * each tick line holds the state before, the inputs used, and the state after.
 */
final class TraceRecorder {

    /** How far around the player blocks are recorded, sideways and up or down. */
    private static final int RADIUS = 12;
    private static final int HEIGHT = 6;
    /** Blocks are scanned again this often, and whenever the player moves this far. */
    private static final int RESCAN_TICKS = 10;
    private static final int RESCAN_DISTANCE = 4;
    /** A position change between ticks bigger than this is a teleport, not rounding. */
    private static final double TELEPORT = 1e-7;

    private static final List<Holder<Attribute>> ATTRIBUTES = List.of(
            Attributes.MOVEMENT_SPEED, Attributes.JUMP_STRENGTH, Attributes.STEP_HEIGHT,
            Attributes.GRAVITY, Attributes.SNEAKING_SPEED, Attributes.MOVEMENT_EFFICIENCY,
            Attributes.WATER_MOVEMENT_EFFICIENCY, Attributes.SAFE_FALL_DISTANCE,
            Attributes.FALL_DAMAGE_MULTIPLIER, Attributes.SCALE,
            Attributes.BLOCK_INTERACTION_RANGE, Attributes.FRICTION_MODIFIER,
            Attributes.AIR_DRAG_MODIFIER);

    private final Path file;
    private final TraceWriter out;
    private final LocalPlayer player;
    private final Map<Long, BlockState> recorded = new HashMap<>();
    private long tick;
    private Vec3 posBefore;
    private Vec3 velBefore;
    private float yaw;
    private float pitch;
    private Vec3 lastPos;
    private BlockPos lastScan;
    private int ticksSinceScan;

    private TraceRecorder(Path file, TraceWriter out, LocalPlayer player) {
        this.file = file;
        this.out = out;
        this.player = player;
    }

    /** Opens a new trace file in {@code dir} and writes its header and the nearby blocks. */
    static TraceRecorder start(Path dir, String scenario, Minecraft client)
            throws IOException {
        Files.createDirectories(dir);
        String stamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String base = (scenario.isEmpty() ? "manual" : scenario) + "-" + stamp;
        Path file = dir.resolve(base + ".jsonl");
        for (int i = 2; Files.exists(file); i++) {
            file = dir.resolve(base + "-" + i + ".jsonl");
        }
        TraceWriter out = new TraceWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8));
        TraceRecorder r = new TraceRecorder(file, out, client.player);
        r.out.header(new TraceHeader(Trace.FORMAT, SharedConstants.getCurrentVersion().name(),
                scenario, OffsetDateTime.now().toString(), r.attributes(client.options)));
        r.scan();
        return r;
    }

    Path file() {
        return file;
    }

    LocalPlayer player() {
        return player;
    }

    long ticks() {
        return tick;
    }

    void startTick() throws IOException {
        posBefore = player.position();
        velBefore = player.getDeltaMovement();
        yaw = player.getYRot();
        pitch = player.getXRot();
        if (lastPos != null && posBefore.distanceToSqr(lastPos) > TELEPORT * TELEPORT) {
            out.event(new TraceEvent(tick - 1, "moved between ticks from " + lastPos + " to "
                    + posBefore + " (a teleport or server correction)"));
        }
    }

    void endTick() throws IOException {
        if (posBefore == null) {
            return;
        }
        Input in = player.input.keyPresses;
        Keys keys = new Keys(in.forward(), in.left(), in.backward(), in.right(), in.jump(),
                in.shift(), in.sprint());
        Vec3 pos = player.position();
        out.tick(new TickRecord(tick, keys, player.xxa, player.zza, yaw, pitch,
                vec(posBefore), vec(velBefore), vec(pos), vec(player.getDeltaMovement()),
                player.onGround(), player.horizontalCollision, player.verticalCollision,
                player.isSprinting(), player.isShiftKeyDown(), player.isInWater(),
                player.onClimbable(), player.fallDistance));
        tick++;
        lastPos = pos;
        ticksSinceScan++;
        if (ticksSinceScan >= RESCAN_TICKS
                || !player.blockPosition().closerThan(lastScan, RESCAN_DISTANCE)) {
            scan();
        }
    }

    void event(String text) throws IOException {
        out.event(new TraceEvent(tick - 1, text));
    }

    void close() throws IOException {
        out.close();
    }

    /**
     * Records every block near the player that isn't plain air and hasn't been recorded yet,
     * or has changed since.
     */
    private void scan() throws IOException {
        Level world = player.level();
        BlockPos centre = player.blockPosition();
        CollisionContext context = CollisionContext.of(player);
        int changed = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = centre.getY() - HEIGHT; y <= centre.getY() + HEIGHT; y++) {
            for (int x = centre.getX() - RADIUS; x <= centre.getX() + RADIUS; x++) {
                for (int z = centre.getZ() - RADIUS; z <= centre.getZ() + RADIUS; z++) {
                    p.set(x, y, z);
                    BlockState state = world.getBlockState(p);
                    long key = p.asLong();
                    BlockState before = recorded.get(key);
                    if (before == state || (before == null && state.isAir()
                            && state.getFluidState().isEmpty())) {
                        continue;
                    }
                    if (before != null) {
                        changed++;
                    }
                    recorded.put(key, state);
                    out.block(block(world, p, state, context));
                }
            }
        }
        if (changed > 0 && tick > 0) {
            event(changed + " recorded block" + (changed == 1 ? "" : "s") + " changed");
        }
        lastScan = centre;
        ticksSinceScan = 0;
    }

    private static BlockRecord block(Level world, BlockPos p, BlockState state,
            CollisionContext context) {
        List<BlockRecord.Box> boxes = new ArrayList<>();
        VoxelShape shape = state.getCollisionShape(world, p, context);
        for (AABB b : shape.toAabbs()) {
            boxes.add(new BlockRecord.Box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ));
        }
        List<Double> pointsY = shape.isEmpty() ? List.of()
                : List.copyOf(shape.getCoords(Direction.Axis.Y));
        FluidState fluid = state.getFluidState();
        String fluidName = fluid.isEmpty() ? ""
                : fluid.is(FluidTags.WATER) ? "water"
                : fluid.is(FluidTags.LAVA) ? "lava" : "other";
        double fluidHeight = fluid.isEmpty() ? 0 : fluid.getHeight(world, p);
        return new BlockRecord(p.getX(), p.getY(), p.getZ(),
                BlockStateParser.serialize(state), boxes,
                state.getBlock().getFriction(), state.getBlock().getSpeedFactor(),
                state.getBlock().getJumpFactor(), fluidName, fluidHeight,
                state.is(BlockTags.CLIMBABLE),
                state.getBlock() instanceof FenceGateBlock ? "gate"
                        : state.is(BlockTags.WALLS) ? "wall"
                        : state.is(BlockTags.FENCES) ? "fence" : "", pointsY);
    }

    private Map<String, Double> attributes(Options options) {
        Map<String, Double> a = new LinkedHashMap<>();
        // Base values: the sprint boost comes and goes with sprinting, so it's left out.
        for (Holder<Attribute> e : ATTRIBUTES) {
            a.put(e.getRegisteredName(), player.getAttributeBaseValue(e));
        }
        // What the next tick starts from, which the tick lines only give after each tick.
        a.put("state:on_ground", player.onGround() ? 1.0 : 0.0);
        a.put("state:horizontal_collision", player.horizontalCollision ? 1.0 : 0.0);
        a.put("state:sprinting", player.isSprinting() ? 1.0 : 0.0);
        a.put("state:sneak_pose", player.isCrouching() ? 1.0 : 0.0);
        a.put("state:crouching", player.getPose() == Pose.CROUCHING ? 1.0 : 0.0);
        a.put("state:sneak_key", player.input.keyPresses.shift() ? 1.0 : 0.0);
        a.put("state:fall_distance", player.fallDistance);
        a.put("state:movement_speed", (double) player.getSpeed());
        a.put("state:food", (double) player.getFoodData().getFoodLevel());
        a.put("option:auto_jump", options.autoJump().get() ? 1.0 : 0.0);
        a.put("option:toggle_sprint", options.toggleSprint().get() ? 1.0 : 0.0);
        a.put("option:toggle_sneak", options.toggleCrouch().get() ? 1.0 : 0.0);
        a.put("option:mouse_sensitivity", options.sensitivity().get());
        a.put("option:sprint_window", (double) options.sprintWindow().get());
        return a;
    }

    private static astar.movement.trace.Vec3 vec(Vec3 v) {
        return new astar.movement.trace.Vec3(v.x, v.y, v.z);
    }
}

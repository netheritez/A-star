package astar.movement.sim;

import astar.movement.Keys;

/**
 * A copy of Minecraft 26.3's player movement, one game tick at a time, with no game.
 *
 * <p>It follows the client's own code path for the local player: {@code LocalPlayer.aiStep}
 * (crouching, input, sprint rules), {@code LivingEntity.aiStep} (jumping, {@code
 * travelInAir}), {@code Entity.move} (backing off edges while sneaking, box collision,
 * stepping up, landing) and {@code Player.updatePlayerPose}. Comments name the game's methods
 * in Mojang's names, as 26.3 ships them; a few older comments still use the Yarn names of
 * 1.21.6, which this was first written against. Float and
 * double arithmetic is kept as the game does it, including its sine table, so that traces
 * recorded in the game replay exactly.
 *
 * <p>Covered: walking, sprinting, sneaking, jumping, falling, stepping up, collisions, and
 * blocks that change friction, speed or jumps (ice, soul sand, honey). Not covered yet: water,
 * climbing, cobwebs and powder snow, bouncy blocks, flying, riding, status effects, and other
 * entities. When the player meets one of those, {@link #unsupported()} says what.
 */
public final class SimulatedPlayer {

    private static final float WIDTH = 0.6F;
    private static final float STANDING_HEIGHT = 1.8F;
    private static final float CROUCHING_HEIGHT = 1.5F;
    private static final double SPRINT_BOOST = 0.3F;
    private static final float DEGREES = (float) (Math.PI / 180.0);

    /**
     * The player's attributes, as base values without the sprint boost.
     *
     * @param movementSpeed 0.1 for a player (as a float, 0.10000000149011612)
     * @param jumpStrength 0.42 (as a float)
     * @param stepHeight 0.6
     * @param gravity 0.08
     * @param sneakingSpeed 0.3
     * @param frictionModifier 1: scales how far block friction is from 1
     * @param airDragModifier 1: scales how far air drag is from 1
     */
    public record Attributes(double movementSpeed, double jumpStrength, double stepHeight,
            double gravity, double sneakingSpeed, double frictionModifier,
            double airDragModifier) {

        public static final Attributes PLAYER = new Attributes(0.1F, 0.42F, 0.6, 0.08, 0.3);

        /** With the default friction and air drag. */
        public Attributes(double movementSpeed, double jumpStrength, double stepHeight,
                double gravity, double sneakingSpeed) {
            this(movementSpeed, jumpStrength, stepHeight, gravity, sneakingSpeed, 1.0, 1.0);
        }
    }

    private final SimWorld world;
    private final Attributes attributes;
    private final int food;

    private double x, y, z;
    private double vx, vy, vz;
    private float yaw, pitch;
    private boolean onGround;
    private boolean horizontalCollision;
    private boolean verticalCollision;
    private boolean collidedSoftly;
    private boolean sprinting;
    private boolean crouching;
    private boolean inSneakingPose;
    private boolean sneakKey;
    private boolean hasForwardMovement;
    private double fallDistance;
    private int jumpingCooldown;
    private int ticksLeftToDoubleTapSprint;
    private float sidewaysSpeed, forwardSpeed;
    private int[] supportingBlock;
    private boolean forceUpdateSupportingBlock;
    private String unsupported;

    /**
     * A player standing still at a position, with a full food bar.
     *
     * @param onGround whether it starts on the ground. A player at rest on the ground still has
     *     the downward velocity of one tick's gravity (about -0.0784), as the game keeps it;
     *     without it the first tick would count as in the air.
     */
    public SimulatedPlayer(SimWorld world, Attributes attributes, double x, double y, double z,
            float yaw, boolean onGround) {
        this(world, attributes, 20, new State(x, y, z, 0,
                onGround ? -attributes.gravity() * 0.98F : 0, 0, yaw, 0, onGround, false, false,
                false, false, 0));
    }

    /** A player that starts in a given state, such as the start of a recorded trace. */
    public SimulatedPlayer(SimWorld world, Attributes attributes, int food, State start) {
        this.world = world;
        this.attributes = attributes;
        this.food = food;
        set(start);
    }

    /**
     * What the next tick starts from.
     *
     * @param sneakKey whether sneak was held on the tick before
     * @param crouching whether the player's pose is crouching (a 1.5-high box)
     */
    public record State(double x, double y, double z, double vx, double vy, double vz,
            float yaw, float pitch, boolean onGround, boolean horizontalCollision,
            boolean sprinting, boolean crouching, boolean sneakKey, double fallDistance) {}

    /** Puts the player into a state, as if it had just been observed there. */
    public void set(State s) {
        x = s.x;
        y = s.y;
        z = s.z;
        vx = s.vx;
        vy = s.vy;
        vz = s.vz;
        yaw = s.yaw;
        pitch = s.pitch;
        onGround = s.onGround;
        horizontalCollision = s.horizontalCollision;
        sprinting = s.sprinting;
        crouching = s.crouching;
        sneakKey = s.sneakKey;
        fallDistance = s.fallDistance;
        collidedSoftly = false;
        forceUpdateSupportingBlock = false;
        supportingBlock = null;
        updateSupportingBlock(onGround, null);
    }

    /**
     * Sets what the double tap of forward goes by, which {@link State} leaves out: whether
     * forward was held last tick, and how many ticks are left to press it again and sprint.
     */
    public void setDoubleTap(boolean forwardHeld, int ticksLeft) {
        hasForwardMovement = forwardHeld;
        ticksLeftToDoubleTapSprint = ticksLeft;
    }

    /** Whether the last tick's bump into a wall was a glancing one, which keeps a sprint. */
    public boolean softCollision() {
        return collidedSoftly;
    }

    /** Sets whether the last tick's bump was a glancing one, which {@link State} leaves out. */
    public void setSoftCollision(boolean soft) {
        collidedSoftly = soft;
    }

    /** Whether forward was held in the last tick. */
    public boolean forwardHeld() {
        return hasForwardMovement;
    }

    /** How many ticks are left to press forward again and start sprinting. */
    public int doubleTapTicks() {
        return ticksLeftToDoubleTapSprint;
    }

    /**
     * Moves the player and sets its velocity, keeping everything else (on the ground, sprinting,
     * what it stands on) as it was.
     */
    public void setPositionAndVelocity(double x, double y, double z, double vx, double vy,
            double vz) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
    }

    /** Runs one game tick with these keys held and the camera at this yaw and pitch. */
    public void tick(Keys keys, float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
        unsupported = null;

        // ClientPlayerEntity.tickMovement, up to super.tickMovement().
        if (ticksLeftToDoubleTapSprint > 0) {
            ticksLeftToDoubleTapSprint--;
        }
        boolean oldSneak = sneakKey;
        boolean oldForward = hasForwardMovement;
        inSneakingPose = fits(CROUCHING_HEIGHT) && (oldSneak || !fits(STANDING_HEIGHT));
        sneakKey = keys.sneak();
        float moveX = multiplier(keys.left(), keys.right());
        float moveZ = multiplier(keys.forward(), keys.back());
        float length = (float) Math.sqrt(moveX * moveX + moveZ * moveZ);
        if (length < 1.0E-4F) {
            moveX = 0.0F;
            moveZ = 0.0F;
        } else {
            moveX /= length;
            moveZ /= length;
        }
        hasForwardMovement = moveZ > 1.0E-5F;
        if (oldSneak || keys.back()) {
            ticksLeftToDoubleTapSprint = 0;
        }
        if (canStartSprinting()) {
            if (!oldForward) {
                if (ticksLeftToDoubleTapSprint > 0) {
                    sprinting = true;
                } else {
                    ticksLeftToDoubleTapSprint = 7;
                }
            }
            if (keys.sprint()) {
                sprinting = true;
            }
        }
        if (sprinting && (!hasForwardMovement || food <= 6
                || (horizontalCollision && !collidedSoftly))) {
            sprinting = false;
        }
        checkModel();

        // LivingEntity.tickMovement.
        if (jumpingCooldown > 0) {
            jumpingCooldown--;
        }
        if (vx * vx + vz * vz < 9.0E-6) {
            vx = 0.0;
            vz = 0.0;
        }
        if (Math.abs(vy) < 0.003) {
            vy = 0.0;
        }
        applyMovementSpeedFactors(moveX, moveZ);
        if (keys.jump()) {
            if (onGround && jumpingCooldown == 0) {
                jump();
                jumpingCooldown = 10;
            }
        } else {
            jumpingCooldown = 0;
        }
        travel();

        // PlayerEntity.updatePose.
        float wanted = sneakKey ? CROUCHING_HEIGHT : STANDING_HEIGHT;
        if (fits(wanted)) {
            crouching = sneakKey;
        } else if (fits(CROUCHING_HEIGHT)) {
            crouching = true;
        } else {
            unsupported = "squeezed into a gap lower than a crouch (swimming pose)";
        }
    }

    // ---- state ----

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public double vx() {
        return vx;
    }

    public double vy() {
        return vy;
    }

    public double vz() {
        return vz;
    }

    public float yaw() {
        return yaw;
    }

    public boolean onGround() {
        return onGround;
    }

    public boolean horizontalCollision() {
        return horizontalCollision;
    }

    public boolean verticalCollision() {
        return verticalCollision;
    }

    public boolean sprinting() {
        return sprinting;
    }

    /** Whether the sneak key is held (which is what the game calls sneaking). */
    public boolean sneaking() {
        return sneakKey;
    }

    public boolean crouching() {
        return crouching;
    }

    public double fallDistance() {
        return fallDistance;
    }

    public Attributes attributes() {
        return attributes;
    }

    /** {@code PlayerEntity.getMovementSpeed}: the attribute, with the sprint boost when sprinting. */
    public float movementSpeed() {
        return (float) (sprinting
                ? attributes.movementSpeed() * (1.0 + SPRINT_BOOST)
                : attributes.movementSpeed());
    }

    /** The movement input the last tick used, after the game's scaling: sideways, forward. */
    public float sidewaysSpeed() {
        return sidewaysSpeed;
    }

    public float forwardSpeed() {
        return forwardSpeed;
    }

    /**
     * What the last tick met that this copy doesn't model (water, a ladder...), or null. The
     * tick's result can't be trusted when this is set.
     */
    public String unsupported() {
        return unsupported;
    }

    public State state() {
        return new State(x, y, z, vx, vy, vz, yaw, pitch, onGround, horizontalCollision,
                sprinting, crouching, sneakKey, fallDistance);
    }

    public Aabb box() {
        return boxAt(x, y, z, crouching ? CROUCHING_HEIGHT : STANDING_HEIGHT);
    }

    // ---- ClientPlayerEntity ----

    private static float multiplier(boolean positive, boolean negative) {
        if (positive == negative) {
            return 0.0F;
        }
        return positive ? 1.0F : -1.0F;
    }

    private boolean canStartSprinting() {
        return !sprinting && hasForwardMovement && food > 6 && !inSneakingPose
                && !touchingWater();
    }

    /** {@code ClientPlayerEntity.applyMovementSpeedFactors}, into the movement input. */
    private void applyMovementSpeedFactors(float mx, float mz) {
        if (mx * mx + mz * mz == 0.0F) {
            sidewaysSpeed = mx;
            forwardSpeed = mz;
            return;
        }
        mx *= 0.98F;
        mz *= 0.98F;
        if (inSneakingPose) {
            float f = (float) attributes.sneakingSpeed();
            mx *= f;
            mz *= f;
        }
        float length = (float) Math.sqrt(mx * mx + mz * mz);
        if (length <= 0.0F) {
            sidewaysSpeed = mx;
            forwardSpeed = mz;
            return;
        }
        float scale = 1.0F / length;
        float ux = mx * scale;
        float uz = mz * scale;
        float ax = Math.abs(ux);
        float az = Math.abs(uz);
        float ratio = az > ax ? ax / az : az / ax;
        float boost = (float) Math.sqrt(1.0F + ratio * ratio);
        float h = Math.min(length * boost, 1.0F);
        sidewaysSpeed = ux * h;
        forwardSpeed = uz * h;
    }

    private boolean hasCollidedSoftly(double mx, double mz) {
        float f = yaw * DEGREES;
        double d = GameMath.sin(f);
        double e = GameMath.cos(f);
        double g = sidewaysSpeed * e - forwardSpeed * d;
        double h = forwardSpeed * e + sidewaysSpeed * d;
        double i = g * g + h * h;
        double j = mx * mx + mz * mz;
        if (!(i < 1.0E-5F) && !(j < 1.0E-5F)) {
            double k = g * mx + h * mz;
            double l = Math.acos(k / Math.sqrt(i * j));
            return l < 0.13962634F;
        }
        return false;
    }

    // ---- LivingEntity ----

    private void jump() {
        float f = (float) attributes.jumpStrength() * 1.0F * jumpVelocityMultiplier() + 0.0F;
        if (!(f <= 1.0E-5F)) {
            vy = Math.max(f, vy);
            if (sprinting) {
                float g = yaw * DEGREES;
                vx += -GameMath.sin(g) * 0.2;
                vz += GameMath.cos(g) * 0.2;
            }
        }
    }

    /** {@code LivingEntity.travelMidAir}: not in water, not gliding. */
    private void travel() {
        float slip = onGround ? modifiedFriction(block(posWithYOffset(0.500001F)).slipperiness(),
                (float) attributes.frictionModifier()) : 1.0F;
        // LivingEntity.getFrictionInfluencedSpeed.
        float speed = onGround
                ? (slip > 0.6 ? movementSpeed() * (0.21600002F / (slip * slip * slip))
                        : movementSpeed())
                : (sprinting ? 0.025999999F : 0.02F);
        addInput(speed);
        move(vx, vy, vz);
        double d = vy - attributes.gravity();
        float airDrag = modifiedFriction(0.91F, (float) attributes.airDragModifier());
        float drag = slip * airDrag;
        float verticalDrag = modifiedFriction(0.98F, (float) attributes.airDragModifier());
        vx *= drag;
        vy = d * verticalDrag;
        vz *= drag;
    }

    /** {@code LivingEntity.computeModifiedFriction}: friction scaled by an attribute. */
    private static float modifiedFriction(float friction, float modifier) {
        float f = 1.0F - (1.0F - friction) * modifier;
        return f < 0.0F ? 0.0F : Math.min(f, 1.0F);
    }

    /** {@code Entity.updateVelocity} with the movement input (sideways, 0, forward). */
    private void addInput(float speed) {
        double ix = sidewaysSpeed;
        double iz = forwardSpeed;
        double lengthSquared = ix * ix + iz * iz;
        if (lengthSquared < 1.0E-7) {
            return;
        }
        if (lengthSquared > 1.0) {
            double length = Math.sqrt(lengthSquared);
            if (length < 1.0E-5F) {
                return;
            }
            ix /= length;
            iz /= length;
        }
        ix *= speed;
        iz *= speed;
        float sin = GameMath.sin(yaw * DEGREES);
        float cos = GameMath.cos(yaw * DEGREES);
        vx += ix * cos - iz * sin;
        vz += iz * cos + ix * sin;
    }

    private float jumpVelocityMultiplier() {
        float f = block(blockPos()).jumpMultiplier();
        float g = block(posWithYOffset(0.500001F)).jumpMultiplier();
        return f == 1.0 ? g : f;
    }

    private float velocityMultiplier() {
        SimBlock here = block(blockPos());
        float f = here.velocityMultiplier();
        if (here.water()) {
            return f;
        }
        return f == 1.0 ? block(posWithYOffset(0.500001F)).velocityMultiplier() : f;
    }

    // ---- Entity.move ----

    private void move(double mx, double my, double mz) {
        double[] m = adjustForSneaking(mx, my, mz);
        mx = m[0];
        my = m[1];
        mz = m[2];
        double[] v = adjustForCollisions(mx, my, mz);
        double d = v[0] * v[0] + v[1] * v[1] + v[2] * v[2];
        if (d > 1.0E-7 || mx * mx + my * my + mz * mz - d < 1.0E-7) {
            x += v[0];
            y += v[1];
            z += v[2];
        }
        boolean blockedX = !approximatelyEquals(mx, v[0]);
        boolean blockedZ = !approximatelyEquals(mz, v[2]);
        horizontalCollision = blockedX || blockedZ;
        verticalCollision = my != v[1];
        onGround = verticalCollision && my < 0.0;
        updateSupportingBlock(onGround, v);
        collidedSoftly = horizontalCollision && hasCollidedSoftly(v[0], v[2]);
        // Entity.fall.
        if (!touchingWater() && v[1] < 0.0) {
            fallDistance -= (float) v[1];
        }
        if (onGround) {
            fallDistance = 0.0;
        }
        if (horizontalCollision) {
            if (blockedX) {
                vx = 0.0;
            }
            if (blockedZ) {
                vz = 0.0;
            }
        }
        if (my != v[1]) {
            // Block.onEntityLand: most blocks stop the fall (slime and beds bounce).
            vy = 0.0;
        }
        float f = velocityMultiplier();
        vx *= f;
        vz *= f;
    }

    private static boolean approximatelyEquals(double a, double b) {
        return Math.abs(b - a) < 1.0E-5F;
    }

    /** {@code PlayerEntity.adjustMovementForSneaking}: sneaking stops at edges. */
    private double[] adjustForSneaking(double mx, double my, double mz) {
        float step = (float) attributes.stepHeight();
        if (my > 0.0 || !sneakKey || !(onGround || fallDistance < step
                && !spaceAroundEmpty(0.0, 0.0, step - fallDistance))) {
            return new double[] {mx, my, mz};
        }
        double d = mx;
        double e = mz;
        double h = Math.signum(d) * 0.05;
        double i = Math.signum(e) * 0.05;
        while (d != 0.0 && spaceAroundEmpty(d, 0.0, step)) {
            if (Math.abs(d) <= 0.05) {
                d = 0.0;
                break;
            }
            d -= h;
        }
        while (e != 0.0 && spaceAroundEmpty(0.0, e, step)) {
            if (Math.abs(e) <= 0.05) {
                e = 0.0;
                break;
            }
            e -= i;
        }
        while (d != 0.0 && e != 0.0 && spaceAroundEmpty(d, e, step)) {
            if (Math.abs(d) <= 0.05) {
                d = 0.0;
            } else {
                d -= h;
            }
            if (Math.abs(e) <= 0.05) {
                e = 0.0;
            } else {
                e -= i;
            }
        }
        return new double[] {d, my, e};
    }

    private boolean spaceAroundEmpty(double offsetX, double offsetZ, double depth) {
        Aabb b = box();
        return Collisions.isSpaceEmpty(world, new Aabb(b.minX() + 1.0E-7 + offsetX,
                b.minY() - depth - 1.0E-7, b.minZ() + 1.0E-7 + offsetZ,
                b.maxX() - 1.0E-7 + offsetX, b.minY(), b.maxZ() - 1.0E-7 + offsetZ));
    }

    /** {@code Entity.adjustMovementForCollisions}, with stepping up. */
    private double[] adjustForCollisions(double mx, double my, double mz) {
        Aabb box = box();
        double[] v = mx * mx + my * my + mz * mz == 0.0 ? new double[] {mx, my, mz}
                : Collisions.collide(mx, my, mz, box,
                        Collisions.shapes(world, box.stretch(mx, my, mz)));
        boolean bx = mx != v[0];
        boolean by = my != v[1];
        boolean bz = mz != v[2];
        boolean landed = by && my < 0.0;
        float step = (float) attributes.stepHeight();
        if (step > 0.0F && (landed || onGround) && (bx || bz)) {
            Aabb box2 = landed ? box.offset(0.0, v[1], 0.0) : box;
            Aabb box3 = box2.stretch(mx, step, mz);
            if (!landed) {
                box3 = box3.stretch(0.0, -1.0E-5F, 0.0);
            }
            var shapes = Collisions.shapes(world, box3);
            float[] heights = Collisions.stepHeights(box2, shapes, step, (float) v[1]);
            for (float g : heights) {
                double[] v2 = Collisions.collide(mx, g, mz, box2, shapes);
                if (v2[0] * v2[0] + v2[2] * v2[2] > v[0] * v[0] + v[2] * v[2]) {
                    double d = box.minY() - box2.minY();
                    return new double[] {v2[0], v2[1] - d, v2[2]};
                }
            }
        }
        return v;
    }

    // ---- supporting block and positions ----

    private void updateSupportingBlock(boolean ground, double[] movement) {
        if (ground) {
            Aabb b = box();
            Aabb below = new Aabb(b.minX(), b.minY() - 1.0E-6, b.minZ(), b.maxX(), b.minY(),
                    b.maxZ());
            int[] found = Collisions.supportingBlock(world, below, x, y, z);
            if (found != null || forceUpdateSupportingBlock) {
                supportingBlock = found;
            } else if (movement != null) {
                found = Collisions.supportingBlock(world,
                        below.offset(-movement[0], 0.0, -movement[2]), x, y, z);
                supportingBlock = found;
            }
            forceUpdateSupportingBlock = found == null;
        } else {
            forceUpdateSupportingBlock = false;
            supportingBlock = null;
        }
    }

    /** {@code Entity.getPosWithYOffset}: the block that counts as underfoot. */
    private int[] posWithYOffset(float offset) {
        if (supportingBlock != null) {
            if (!(offset > 1.0E-5F)) {
                return supportingBlock;
            }
            String kind = block(supportingBlock).kind();
            boolean fence = kind.equals("fence");
            boolean wallOrGate = kind.equals("wall") || kind.equals("gate");
            return (!(offset <= 0.5) || !fence) && !wallOrGate
                    ? new int[] {supportingBlock[0], floor(y - offset), supportingBlock[2]}
                    : supportingBlock;
        }
        return new int[] {floor(x), floor(y - offset), floor(z)};
    }

    private int[] blockPos() {
        return new int[] {floor(x), floor(y), floor(z)};
    }

    private SimBlock block(int[] p) {
        return world.block(p[0], p[1], p[2]);
    }

    private static int floor(double d) {
        return (int) Math.floor(d);
    }

    private static Aabb boxAt(double x, double y, double z, float height) {
        float half = WIDTH / 2.0F;
        return new Aabb(x - half, y, z - half, x + half, y + height, z + half);
    }

    /** {@code PlayerEntity.canChangeIntoPose}: whether a box of this height fits here. */
    private boolean fits(float height) {
        return Collisions.isSpaceEmpty(world, boxAt(x, y, z, height).contract(1.0E-7));
    }

    // ---- what isn't modelled ----

    private boolean touchingWater() {
        Aabb b = box().contract(0.001);
        for (int bx = floor(b.minX()); bx <= floor(b.maxX()); bx++) {
            for (int by = floor(b.minY()); by <= floor(b.maxY()); by++) {
                for (int bz = floor(b.minZ()); bz <= floor(b.maxZ()); bz++) {
                    if (world.block(bx, by, bz).water()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void checkModel() {
        if (touchingWater()) {
            unsupported = "in water";
        } else if (block(blockPos()).climbable()) {
            unsupported = "on a ladder or vine";
        }
    }
}

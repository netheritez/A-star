package astar.movement.trace;

/** A position or velocity in blocks (per tick, for velocities). */
public record Vec3(double x, double y, double z) {

    public static final Vec3 ZERO = new Vec3(0, 0, 0);

    public Vec3 minus(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }
}

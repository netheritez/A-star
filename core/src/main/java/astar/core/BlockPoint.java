package astar.core;

/** A block position in readable form. {@code y} is up; the search itself uses {@link Pos}. */
public record BlockPoint(int x, int y, int z) {

    /**
     * @throws IllegalArgumentException if the point is outside what {@link Pos} can hold,
     *     rather than silently wrapping round to the other side of the world
     */
    public long pack() {
        if (x < -(1 << 25) || x >= 1 << 25 || z < -(1 << 25) || z >= 1 << 25
                || y < -2048 || y > 2047) {
            throw new IllegalArgumentException("Out of range for a packed position: " + this);
        }
        return Pos.pack(x, y, z);
    }

    public BlockPoint offset(int dx, int dy, int dz) {
        return new BlockPoint(x + dx, y + dy, z + dz);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}

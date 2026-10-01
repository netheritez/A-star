package astar.movement.sim;

/** The blocks the simulated player moves among. Anything not given is air. */
public interface SimWorld {

    SimBlock block(int x, int y, int z);
}

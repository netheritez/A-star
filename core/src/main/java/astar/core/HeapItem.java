package astar.core;

/**
 * Something that can live in a {@link MinHeap}. The item stores its own position in the heap's
 * array, which is what lets the heap find it and lower its key without a linear search.
 */
public interface HeapItem<T> extends Comparable<T> {
    int getHeapIndex();

    void setHeapIndex(int index);
}

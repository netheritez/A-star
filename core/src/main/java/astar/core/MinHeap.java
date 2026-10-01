package astar.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * An array-backed binary min-heap.
 *
 * <p>The array is a complete binary tree stored level by level: the children of index {@code i}
 * are at {@code 2i + 1} and {@code 2i + 2}, and its parent is at {@code (i - 1) / 2}. Every parent
 * is less than or equal to its children, so the minimum is always at index 0.
 *
 * <ul>
 *   <li>{@link #add}: O(log n). Put the item at the end, then sift it up.
 *   <li>{@link #poll}: O(log n). Take the root, move the last item to the root, sift it down.
 *   <li>{@link #update}: O(log n). After an item's key has decreased, sift it up.
 *   <li>{@link #contains}: O(1). Each item remembers its own index.
 * </ul>
 */
public final class MinHeap<T extends HeapItem<T>> {
    private static final int DEFAULT_CAPACITY = 64;

    private Object[] items;
    private int size;

    public MinHeap() {
        this(DEFAULT_CAPACITY);
    }

    public MinHeap(int initialCapacity) {
        items = new Object[Math.max(1, initialCapacity)];
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void add(T item) {
        if (contains(item)) {
            throw new IllegalArgumentException("Item is already in the heap");
        }
        if (size == items.length) {
            items = Arrays.copyOf(items, items.length * 2);
        }
        place(item, size);
        size++;
        siftUp(item.getHeapIndex());
    }

    /** Removes and returns the smallest item. */
    public T poll() {
        if (size == 0) {
            throw new NoSuchElementException("Heap is empty");
        }
        T min = at(0);
        size--;
        if (size > 0) {
            place(at(size), 0);
            siftDown(0);
        }
        items[size] = null;
        min.setHeapIndex(-1);
        return min;
    }

    public T peek() {
        if (size == 0) {
            throw new NoSuchElementException("Heap is empty");
        }
        return at(0);
    }

    /** Call after an item already in the heap has had its key lowered. */
    public void update(T item) {
        if (!contains(item)) {
            throw new IllegalArgumentException("Item is not in the heap");
        }
        siftUp(item.getHeapIndex());
    }

    public boolean contains(T item) {
        int i = item.getHeapIndex();
        return i >= 0 && i < size && items[i] == item;
    }

    public void clear() {
        for (int i = 0; i < size; i++) {
            at(i).setHeapIndex(-1);
            items[i] = null;
        }
        size = 0;
    }

    /** A snapshot of the items in heap-array order (not sorted). */
    public List<T> toList() {
        List<T> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(at(i));
        }
        return list;
    }

    private void siftUp(int i) {
        T item = at(i);
        while (i > 0) {
            int parent = (i - 1) / 2;
            T p = at(parent);
            if (item.compareTo(p) >= 0) {
                break;
            }
            place(p, i);
            i = parent;
        }
        place(item, i);
    }

    private void siftDown(int i) {
        T item = at(i);
        while (true) {
            int left = 2 * i + 1;
            if (left >= size) {
                break;
            }
            int right = left + 1;
            int smaller = right < size && at(right).compareTo(at(left)) < 0 ? right : left;
            if (item.compareTo(at(smaller)) <= 0) {
                break;
            }
            place(at(smaller), i);
            i = smaller;
        }
        place(item, i);
    }

    private void place(T item, int i) {
        items[i] = item;
        item.setHeapIndex(i);
    }

    @SuppressWarnings("unchecked")
    private T at(int i) {
        return (T) items[i];
    }
}

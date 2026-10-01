package astar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MinHeapTest {

    /** A minimal heap item with a mutable key. */
    private static final class Item implements HeapItem<Item> {
        int key;
        int index = -1;

        Item(int key) {
            this.key = key;
        }

        @Override
        public int getHeapIndex() {
            return index;
        }

        @Override
        public void setHeapIndex(int i) {
            index = i;
        }

        @Override
        public int compareTo(Item o) {
            return Integer.compare(key, o.key);
        }
    }

    @Test
    void pollsInSortedOrderAndGrows() {
        Random rng = new Random(42);
        MinHeap<Item> heap = new MinHeap<>(2); // tiny capacity forces several resizes
        List<Integer> keys = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            int k = rng.nextInt(500);
            keys.add(k);
            heap.add(new Item(k));
        }
        assertEquals(1000, heap.size());
        Collections.sort(keys);
        for (int expected : keys) {
            assertEquals(expected, heap.poll().key);
        }
        assertTrue(heap.isEmpty());
    }

    @Test
    void updateAfterDecreaseMovesItemToTop() {
        MinHeap<Item> heap = new MinHeap<>();
        Item a = new Item(5), b = new Item(10), c = new Item(20);
        heap.add(a);
        heap.add(b);
        heap.add(c);
        assertSame(a, heap.peek());

        c.key = 1;
        heap.update(c);
        assertSame(c, heap.peek());
        assertEquals(3, heap.size());
        assertSame(c, heap.poll());
        assertSame(a, heap.poll());
        assertSame(b, heap.poll());
    }

    @Test
    void containsTracksMembership() {
        MinHeap<Item> heap = new MinHeap<>();
        Item a = new Item(1), b = new Item(2), outsider = new Item(0);
        heap.add(a);
        heap.add(b);
        assertTrue(heap.contains(a));
        assertTrue(heap.contains(b));
        assertFalse(heap.contains(outsider));

        heap.poll();
        assertFalse(heap.contains(a));
        assertEquals(-1, a.getHeapIndex());
        assertTrue(heap.contains(b));

        heap.clear();
        assertFalse(heap.contains(b));
        assertTrue(heap.isEmpty());
    }

    @Test
    void rejectsMisuse() {
        MinHeap<Item> heap = new MinHeap<>();
        Item a = new Item(1);
        assertThrows(NoSuchElementException.class, heap::poll);
        assertThrows(IllegalArgumentException.class, () -> heap.update(a));
        heap.add(a);
        assertThrows(IllegalArgumentException.class, () -> heap.add(a));
    }

    @Test
    void randomUpdatesKeepHeapOrder() {
        Random rng = new Random(7);
        MinHeap<Item> heap = new MinHeap<>();
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            Item it = new Item(1000 + rng.nextInt(1000));
            items.add(it);
            heap.add(it);
        }
        for (int i = 0; i < 300; i++) {
            Item it = items.get(rng.nextInt(items.size()));
            it.key -= rng.nextInt(50);
            heap.update(it);
        }
        int prev = Integer.MIN_VALUE;
        while (!heap.isEmpty()) {
            int k = heap.poll().key;
            assertTrue(k >= prev, "out of order: " + prev + " then " + k);
            prev = k;
        }
    }
}

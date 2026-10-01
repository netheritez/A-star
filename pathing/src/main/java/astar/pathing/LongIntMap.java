package astar.pathing;

import java.util.Arrays;

/**
 * A map from packed positions to small ints, with open addressing and no boxing. Keys must not
 * be {@link Long#MIN_VALUE}, which marks an empty slot (no position in the world packs to it).
 */
final class LongIntMap {
    private static final long EMPTY = Long.MIN_VALUE;

    private long[] keys;
    private int[] values;
    private int size;
    private int mask;
    private int shift; // 64 - log2(capacity)

    LongIntMap(int expected) {
        int cap = Integer.highestOneBit(Math.max(4, expected * 2 - 1)) << 1;
        keys = new long[cap];
        values = new int[cap];
        Arrays.fill(keys, EMPTY);
        mask = cap - 1;
        shift = 64 - Integer.numberOfTrailingZeros(cap);
    }

    private LongIntMap(LongIntMap other) {
        keys = other.keys.clone();
        values = other.values.clone();
        size = other.size;
        mask = other.mask;
        shift = other.shift;
    }

    /** An independent copy. */
    LongIntMap copy() {
        return new LongIntMap(this);
    }

    int size() {
        return size;
    }

    /** The value for {@code key}, or {@code missing} if there is none. */
    int get(long key, int missing) {
        for (int i = slot(key); ; i = (i + 1) & mask) {
            long k = keys[i];
            if (k == key) {
                return values[i];
            }
            if (k == EMPTY) {
                return missing;
            }
        }
    }

    void put(long key, int value) {
        if ((size + 1) * 2 > keys.length) {
            grow();
        }
        for (int i = slot(key); ; i = (i + 1) & mask) {
            if (keys[i] == key) {
                values[i] = value;
                return;
            }
            if (keys[i] == EMPTY) {
                keys[i] = key;
                values[i] = value;
                size++;
                return;
            }
        }
    }

    private int slot(long key) {
        // The top bits of the product depend on every bit of the key; the low bits (used before)
        // left most of a packed position's z out, so a page's cells piled into few slots.
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> shift);
    }

    private void grow() {
        long[] oldKeys = keys;
        int[] oldValues = values;
        keys = new long[oldKeys.length * 2];
        values = new int[oldKeys.length * 2];
        Arrays.fill(keys, EMPTY);
        mask = keys.length - 1;
        shift = 64 - Integer.numberOfTrailingZeros(keys.length);
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }
}

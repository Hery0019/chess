package engine;

/**
 * Fixed-size transposition table keyed by Zobrist hash. Each slot holds the
 * full 64-bit key (so index collisions are detected, not silently trusted),
 * a packed info word (score, depth, bound type, search age) and the best
 * move found at that node.
 *
 * Replacement: a slot is overwritten unless it holds a DEEPER entry written
 * by the CURRENT search — entries from earlier searches are always fair
 * game, which keeps the table fresh across moves without a costly clear.
 *
 * Scores must be stored ply-independent: {@link Search} converts mate
 * scores to "distance from root" before storing and back after probing.
 *
 * Not thread-safe; one table per {@link Search} instance.
 */
public final class TranspositionTable {

    public static final int EXACT = 0, LOWER = 1, UPPER = 2;

    private final long[] keys;
    private final long[] info;
    private final Move[] moves;
    private final int mask;
    private int age;
    private int hit = -1;   // slot of the last successful probe

    /** @param bits table size as a power of two (20 bits = 1M entries, ~20 MB). */
    public TranspositionTable(int bits) {
        int size = 1 << bits;
        keys = new long[size];
        info = new long[size];
        moves = new Move[size];
        mask = size - 1;
    }

    /** Call once per root search: stamps new entries so old ones lose replacement fights. */
    public void newSearch() { age = (age + 1) & 0xFF; }

    public void clear() {
        java.util.Arrays.fill(keys, 0L);
        java.util.Arrays.fill(info, 0L);
        java.util.Arrays.fill(moves, null);
    }

    /** True if an entry for {@code key} exists; its fields are then readable via the hit* accessors. */
    public boolean probe(long key) {
        int i = (int) (key & mask);
        if (info[i] != 0L && keys[i] == key) { hit = i; return true; }
        hit = -1;
        return false;
    }

    public int  hitScore() { return (int) info[hit]; }
    public int  hitDepth() { return depthOf(info[hit]); }
    public int  hitFlag()  { return (int) ((info[hit] >>> 40) & 3); }
    public Move hitMove()  { return moves[hit]; }

    public void store(long key, int depth, int flag, int score, Move best) {
        int i = (int) (key & mask);
        long old = info[i];
        boolean sameKey = old != 0L && keys[i] == key;
        if (old != 0L && !sameKey && ageOf(old) == age && depthOf(old) > depth) return;
        // Keep a known best move when the new entry has none (fail-low nodes).
        Move keep = best != null ? best : (sameKey ? moves[i] : null);
        keys[i] = key;
        moves[i] = keep;
        info[i] = (score & 0xFFFFFFFFL)
                | ((long) (depth & 0xFF) << 32)
                | ((long) flag << 40)
                | ((long) age << 42)
                | (1L << 50);   // "occupied" marker keeps a zero-score, zero-depth entry non-zero
    }

    private static int depthOf(long info) { return (int) ((info >>> 32) & 0xFF); }
    private static int ageOf(long info)   { return (int) ((info >>> 42) & 0xFF); }
}

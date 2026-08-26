package engine;

/**
 * Mutable undo record filled by {@link Board#makeMove} and consumed by
 * {@link Board#unmakeMove}. Deliberately a bag of public fields, not a record:
 * instances are preallocated (one per ply in the search) and overwritten,
 * so per-node allocation is zero.
 *
 * The full prior Zobrist hash is stored rather than re-deriving it on unmake;
 * 8 bytes per ply buys unconditional correctness.
 */
public final class Undo {
    public int captured;        // full piece code, EMPTY if none
    public int castlingRights;  // prior rights bitmask
    public int epSquare;        // prior en-passant square, -1 if none
    public int halfmoveClock;   // prior halfmove counter
    public long zobrist;        // prior full hash
}

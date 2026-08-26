package engine;

import java.util.SplittableRandom;

/**
 * Zobrist hashing. The key incorporates piece placement, side to move,
 * castling rights, and en-passant file — the four components FIDE's
 * repetition rule cares about.
 *
 * KNOWN LIMITATION (documented, accepted for v1): the en-passant file is
 * hashed whenever an ep square is set, even if no enemy pawn can actually
 * capture en passant. Strict FIDE repetition treats such positions as
 * identical to their no-ep twins; this implementation may therefore fail to
 * detect a repetition one occurrence early in rare lines. It never falsely
 * declares one, which is the safe direction to err.
 *
 * The seed is fixed so hashes are reproducible across runs (test stability).
 */
public final class Zobrist {
    /** [pieceCode 0..15][square 0..63]; unused codes are just dead entries. */
    static final long[][] PIECE = new long[16][64];
    /** Indexed by the full 4-bit castling-rights mask. */
    static final long[] CASTLING = new long[16];
    /** Indexed by en-passant file. */
    static final long[] EP_FILE = new long[8];
    static final long SIDE_TO_MOVE;

    static {
        SplittableRandom rng = new SplittableRandom(0x5EEDC0FFEEL);
        for (int p = 0; p < 16; p++)
            for (int sq = 0; sq < 64; sq++)
                PIECE[p][sq] = rng.nextLong();
        for (int i = 0; i < 16; i++) CASTLING[i] = rng.nextLong();
        for (int f = 0; f < 8; f++) EP_FILE[f] = rng.nextLong();
        SIDE_TO_MOVE = rng.nextLong();
    }

    private Zobrist() {}

    /**
     * Full recomputation from board state. Used only by tests to assert that
     * the incremental updates in makeMove/unmakeMove are exact, and by FEN
     * parsing to initialise the key.
     */
    public static long computeFromScratch(Board b) {
        long h = 0L;
        for (int sq = 0; sq < 64; sq++) {
            int p = b.pieceAt(sq);
            if (p != Pieces.EMPTY) h ^= PIECE[p][sq];
        }
        h ^= CASTLING[b.castlingRights()];
        if (b.epSquare() != -1) h ^= EP_FILE[b.epSquare() & 7];
        if (b.sideToMove() == Pieces.BLACK) h ^= SIDE_TO_MOVE;
        return h;
    }
}

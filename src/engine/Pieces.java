package engine;

/**
 * Int-based piece encoding: {@code piece = type | (color << 3)}.
 *
 * DESIGN TRADE-OFF (reviewed and approved): plain ints instead of enums.
 * The search touches piece codes millions of times per move; ints pack into
 * undo records and Zobrist table indices with zero indirection. The cost is
 * that {@link #colorOf(int)} is meaningless for EMPTY — callers must check
 * emptiness first. All such call sites in this codebase do.
 */
public final class Pieces {
    public static final int EMPTY = 0;
    public static final int PAWN = 1, KNIGHT = 2, BISHOP = 3, ROOK = 4, QUEEN = 5, KING = 6;
    public static final int WHITE = 0, BLACK = 1;

    /** Values indexed by piece TYPE (centipawns). Index 0 unused. */
    public static final int[] VALUE = {0, 100, 320, 330, 500, 900, 0};

    private Pieces() {}

    public static int make(int type, int color) { return type | (color << 3); }
    public static int typeOf(int piece)         { return piece & 7; }
    /** Only valid for non-empty pieces. */
    public static int colorOf(int piece)        { return (piece >>> 3) & 1; }

    public static char toChar(int piece) {
        if (piece == EMPTY) return '.';
        char c = ".pnbrqk".charAt(typeOf(piece));
        return colorOf(piece) == WHITE ? Character.toUpperCase(c) : c;
    }
}

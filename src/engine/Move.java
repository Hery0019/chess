package engine;

/**
 * An immutable move.
 *
 * DESIGN TRADE-OFF (reviewed and approved): a record rather than a packed int.
 * Real engines pack moves into 16-32 bits; at this project's node budget
 * (~10^5-10^6 nodes/move) the allocation cost is tolerable and the
 * debuggability win is large. The record gives value-based equals/hashCode,
 * which the UI (matching a clicked from/to pair against the legal list) and
 * tests rely on.
 *
 * @param from      source square, 0..63 (a1 = 0, h8 = 63)
 * @param to        destination square
 * @param piece     the full piece code that moves (type | color bit)
 * @param captured  full piece code of the captured piece, or EMPTY.
 *                  For en passant this is the enemy pawn even though it does
 *                  not sit on {@code to}.
 * @param promotion piece TYPE promoted to (QUEEN..KNIGHT), or 0
 * @param flags     bitwise OR of CASTLE / EN_PASSANT / DOUBLE_PUSH
 */
public record Move(int from, int to, int piece, int captured, int promotion, int flags) {

    public static final int NONE = 0, CASTLE = 1, EN_PASSANT = 2, DOUBLE_PUSH = 4;

    public boolean isCastle()     { return (flags & CASTLE) != 0; }
    public boolean isEnPassant()  { return (flags & EN_PASSANT) != 0; }
    public boolean isDoublePush() { return (flags & DOUBLE_PUSH) != 0; }
    public boolean isCapture()    { return captured != Pieces.EMPTY; }

    public static String squareName(int sq) {
        return "" + (char) ('a' + (sq & 7)) + (char) ('1' + (sq >>> 3));
    }

    /** Long algebraic notation, e.g. {@code e2e4}, {@code e7e8q}. */
    @Override
    public String toString() {
        String s = squareName(from) + squareName(to);
        if (promotion != 0) s += ".pnbrqk".charAt(promotion);
        return s;
    }
}

package engine;

import static engine.Pieces.*;

/**
 * Static exchange evaluation (SEE): the material outcome, in centipawns,
 * of playing out every capture on one square — each side always
 * recapturing with its least valuable piece and free to stop — without
 * searching. Answers "is this capture winning, even or losing?", which the
 * quiescence search uses to skip captures that lose material.
 *
 * X-ray attackers are handled: a piece that has captured no longer blocks
 * the ray it came from, so a rook behind a rook, or a queen behind a
 * bishop, joins the exchange when its blocker leaves. Pins are ignored
 * (the usual simplification), so the value is an estimate, never a proof
 * — the search still verifies whatever it plays.
 */
public final class StaticExchange {

    /** Piece values for the swap; the king is priced so that "losing" it dominates any sequence. */
    private static final int[] SWAP_VALUE = {0, 100, 320, 330, 500, 900, 20_000};

    private StaticExchange() {}

    /**
     * @return the exchange balance from the mover's point of view: positive
     *         wins material, 0 is an even trade, negative loses material.
     *         A quiet move yields 0 or the loss of the moved piece.
     */
    public static int see(Board b, Move m) {
        int to = m.to();
        int mover = colorOf(m.piece());
        long gone = 1L << m.from();                       // squares whose piece has already moved
        int captured = m.isEnPassant() ? PAWN : typeOf(m.captured());
        if (m.isEnPassant()) gone |= 1L << (to + (mover == WHITE ? -8 : 8));

        // gain[d]: balance for the side capturing at level d if the exchange
        // stops right after its capture. Each entry is written speculatively
        // before looking for the attacker that would make it; the last one is
        // therefore dropped, and the final pass lets each side stop whenever
        // continuing would cost it material.
        int[] gain = new int[32];
        int d = 0;
        gain[0] = SWAP_VALUE[captured];
        int onSquare = m.promotion() != 0 ? m.promotion() : typeOf(m.piece());   // what the next capture wins
        int side = mover ^ 1;
        while (d < 31) {
            d++;
            gain[d] = SWAP_VALUE[onSquare] - gain[d - 1];
            int from = leastValuableAttacker(b, to, side, gone);
            if (from < 0) break;
            gone |= 1L << from;
            onSquare = typeOf(b.pieceAt(from));
            side ^= 1;
        }
        while (--d > 0) gain[d - 1] = -Math.max(-gain[d - 1], gain[d]);
        return gain[0];
    }

    /**
     * Square of the cheapest piece of {@code color} attacking {@code sq},
     * ignoring pieces on {@code gone} squares (they have left the board
     * for this exchange, which is what reveals x-rays), or -1.
     */
    private static int leastValuableAttacker(Board b, int sq, int color, long gone) {
        int file = sq & 7;
        int pawn = make(PAWN, color);
        if (color == WHITE) {
            if (file > 0 && sq >= 9 && present(b, sq - 9, pawn, gone)) return sq - 9;
            if (file < 7 && sq >= 7 && present(b, sq - 7, pawn, gone)) return sq - 7;
        } else {
            if (file > 0 && sq <= 56 && present(b, sq + 7, pawn, gone)) return sq + 7;
            if (file < 7 && sq <= 54 && present(b, sq + 9, pawn, gone)) return sq + 9;
        }
        int knight = make(KNIGHT, color);
        for (int t : Board.KNIGHT_TARGETS[sq]) if (present(b, t, knight, gone)) return t;

        int bishop = -1, rook = -1, queen = -1;
        int r0 = sq >>> 3, f0 = sq & 7;
        for (int[] dir : Board.BISHOP_DIRS) {
            int hit = firstAlongRay(b, r0, f0, dir, gone);
            if (hit < 0) continue;
            int p = b.pieceAt(hit);
            if (colorOf(p) != color) continue;
            if (typeOf(p) == BISHOP) { bishop = hit; break; }
            if (typeOf(p) == QUEEN && queen < 0) queen = hit;
        }
        if (bishop >= 0) return bishop;
        for (int[] dir : Board.ROOK_DIRS) {
            int hit = firstAlongRay(b, r0, f0, dir, gone);
            if (hit < 0) continue;
            int p = b.pieceAt(hit);
            if (colorOf(p) != color) continue;
            if (typeOf(p) == ROOK) { rook = hit; break; }
            if (typeOf(p) == QUEEN && queen < 0) queen = hit;
        }
        if (rook >= 0) return rook;
        if (queen >= 0) return queen;

        int king = make(KING, color);
        for (int t : Board.KING_TARGETS[sq]) if (present(b, t, king, gone)) return t;
        return -1;
    }

    private static boolean present(Board b, int sq, int piece, long gone) {
        return (gone & (1L << sq)) == 0 && b.pieceAt(sq) == piece;
    }

    /** First piece along the ray from (r0, f0), skipping squares in {@code gone}; -1 if the ray is empty. */
    private static int firstAlongRay(Board b, int r0, int f0, int[] dir, long gone) {
        int r = r0 + dir[0], f = f0 + dir[1];
        while (r >= 0 && r < 8 && f >= 0 && f < 8) {
            int t = r * 8 + f;
            if ((gone & (1L << t)) == 0 && b.pieceAt(t) != EMPTY) return t;
            r += dir[0];
            f += dir[1];
        }
        return -1;
    }
}

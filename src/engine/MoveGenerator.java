package engine;

import java.util.ArrayList;
import java.util.List;
import static engine.Pieces.*;

/**
 * Move generation. Strategy: generate pseudo-legal moves, then filter with
 * make / inCheck / unmake. Not the fastest scheme (a legal generator with pin
 * detection avoids the make/unmake round trip) but it is by far the easiest
 * to get *provably* right, and correctness was the non-negotiable requirement.
 * Perft validates the whole pipeline.
 *
 * Stateless: safe to share one instance, or create freely.
 */
public final class MoveGenerator {

    /** All strictly legal moves for the side to move. */
    public List<Move> generateLegal(Board b) {
        List<Move> pseudo = new ArrayList<>(48);
        generatePseudo(b, pseudo, false);
        addCastlingMoves(b, pseudo);
        List<Move> legal = new ArrayList<>(pseudo.size());
        Undo u = new Undo();
        int us = b.sideToMove();
        for (Move m : pseudo) {
            b.makeMove(m, u);
            if (!b.inCheck(us)) legal.add(m);
            b.unmakeMove(m, u);
        }
        return legal;
    }

    /**
     * Pseudo-legal captures and promotions, for quiescence search. The caller
     * is responsible for the legality (own-king-safety) filter — quiescence
     * does it inline to avoid a second make/unmake pass.
     */
    public List<Move> generateCaptures(Board b) {
        List<Move> list = new ArrayList<>(16);
        generatePseudo(b, list, true);
        return list;
    }

    private void generatePseudo(Board b, List<Move> out, boolean capturesOnly) {
        int us = b.sideToMove(), them = us ^ 1;
        for (int sq = 0; sq < 64; sq++) {
            int p = b.pieceAt(sq);
            if (p == EMPTY || colorOf(p) != us) continue;
            switch (typeOf(p)) {
                case PAWN   -> pawnMoves(b, sq, p, us, them, out, capturesOnly);
                case KNIGHT -> leaperMoves(b, sq, p, us, Board.KNIGHT_TARGETS[sq], out, capturesOnly);
                case KING   -> leaperMoves(b, sq, p, us, Board.KING_TARGETS[sq], out, capturesOnly);
                case BISHOP -> sliderMoves(b, sq, p, us, Board.BISHOP_DIRS, out, capturesOnly);
                case ROOK   -> sliderMoves(b, sq, p, us, Board.ROOK_DIRS, out, capturesOnly);
                case QUEEN  -> {
                    sliderMoves(b, sq, p, us, Board.BISHOP_DIRS, out, capturesOnly);
                    sliderMoves(b, sq, p, us, Board.ROOK_DIRS, out, capturesOnly);
                }
                default -> throw new IllegalStateException("corrupt board");
            }
        }
    }

    private void pawnMoves(Board b, int sq, int piece, int us, int them,
                           List<Move> out, boolean capturesOnly) {
        int dir = us == WHITE ? 8 : -8;
        int startRank = us == WHITE ? 1 : 6;
        int promoRank = us == WHITE ? 7 : 0;
        int rank = sq >>> 3, file = sq & 7;

        int fwd = sq + dir;
        if (b.pieceAt(fwd) == EMPTY) {
            if ((fwd >>> 3) == promoRank) {
                // Promotions are tactically decisive: included even in
                // captures-only generation so quiescence sees them.
                addPromotions(out, sq, fwd, piece, EMPTY);
            } else if (!capturesOnly) {
                out.add(new Move(sq, fwd, piece, EMPTY, 0, Move.NONE));
                if (rank == startRank && b.pieceAt(sq + 2 * dir) == EMPTY) {
                    out.add(new Move(sq, sq + 2 * dir, piece, EMPTY, 0, Move.DOUBLE_PUSH));
                }
            }
        }

        for (int dc = -1; dc <= 1; dc += 2) {
            int nf = file + dc;
            if (nf < 0 || nf > 7) continue;
            int t = sq + dir + dc;
            int target = b.pieceAt(t);
            if (target != EMPTY && colorOf(target) == them) {
                if ((t >>> 3) == promoRank) addPromotions(out, sq, t, piece, target);
                else out.add(new Move(sq, t, piece, target, 0, Move.NONE));
            } else if (t == b.epSquare()) {
                out.add(new Move(sq, t, piece, make(PAWN, them), 0, Move.EN_PASSANT));
            }
        }
    }

    private void addPromotions(List<Move> out, int from, int to, int piece, int captured) {
        // All four pieces enter the search tree (approved decision #6):
        // underpromotion is almost never best but costs a trivial branching
        // factor increase, and omitting it would make generation incomplete.
        out.add(new Move(from, to, piece, captured, QUEEN, Move.NONE));
        out.add(new Move(from, to, piece, captured, ROOK, Move.NONE));
        out.add(new Move(from, to, piece, captured, BISHOP, Move.NONE));
        out.add(new Move(from, to, piece, captured, KNIGHT, Move.NONE));
    }

    private void leaperMoves(Board b, int sq, int piece, int us, int[] targets,
                             List<Move> out, boolean capturesOnly) {
        for (int t : targets) {
            int target = b.pieceAt(t);
            if (target == EMPTY) {
                if (!capturesOnly) out.add(new Move(sq, t, piece, EMPTY, 0, Move.NONE));
            } else if (colorOf(target) != us) {
                out.add(new Move(sq, t, piece, target, 0, Move.NONE));
            }
        }
    }

    private void sliderMoves(Board b, int sq, int piece, int us, int[][] dirs,
                             List<Move> out, boolean capturesOnly) {
        int r0 = sq >>> 3, f0 = sq & 7;
        for (int[] d : dirs) {
            int r = r0 + d[0], f = f0 + d[1];
            while (r >= 0 && r < 8 && f >= 0 && f < 8) {
                int t = r * 8 + f;
                int target = b.pieceAt(t);
                if (target == EMPTY) {
                    if (!capturesOnly) out.add(new Move(sq, t, piece, EMPTY, 0, Move.NONE));
                } else {
                    if (colorOf(target) != us) out.add(new Move(sq, t, piece, target, 0, Move.NONE));
                    break;
                }
                r += d[0]; f += d[1];
            }
        }
    }

    /**
     * Castling. All FIDE conditions checked here except "king ends up in
     * check", which the generic legality filter catches (the pass-through
     * squares must still be checked here, since the filter only sees the
     * final position):
     *  - the right has not been forfeited (rights bitmask),
     *  - squares between king and rook are empty,
     *  - the king is not currently in check,
     *  - the square the king passes through is not attacked.
     * Note: b1/b8 being attacked does NOT forbid queenside castling — the
     * king never touches it. A classic implementation bug, covered by a test.
     */
    private void addCastlingMoves(Board b, List<Move> out) {
        int us = b.sideToMove(), them = us ^ 1;
        int rights = b.castlingRights();
        int kingFrom = us == WHITE ? 4 : 60;
        int king = make(KING, us);
        if (b.kingSquare(us) != kingFrom) return;   // fast bail; rights imply this anyway
        boolean canK = (rights & (us == WHITE ? Board.WK_CASTLE : Board.BK_CASTLE)) != 0;
        boolean canQ = (rights & (us == WHITE ? Board.WQ_CASTLE : Board.BQ_CASTLE)) != 0;
        if (!canK && !canQ) return;
        if (b.inCheck(us)) return;

        if (canK
                && b.pieceAt(kingFrom + 1) == EMPTY
                && b.pieceAt(kingFrom + 2) == EMPTY
                && !b.isSquareAttacked(kingFrom + 1, them)
                && !b.isSquareAttacked(kingFrom + 2, them)) {
            out.add(new Move(kingFrom, kingFrom + 2, king, EMPTY, 0, Move.CASTLE));
        }
        if (canQ
                && b.pieceAt(kingFrom - 1) == EMPTY
                && b.pieceAt(kingFrom - 2) == EMPTY
                && b.pieceAt(kingFrom - 3) == EMPTY
                && !b.isSquareAttacked(kingFrom - 1, them)
                && !b.isSquareAttacked(kingFrom - 2, them)) {
            out.add(new Move(kingFrom, kingFrom - 2, king, EMPTY, 0, Move.CASTLE));
        }
    }
}

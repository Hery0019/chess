package engine;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static engine.Pieces.*;

/**
 * Minimax with alpha-beta pruning, implemented in the negamax formulation
 * (approved decision #3: algebraically identical to explicit min/max
 * branches, one code path, half the surface for sign bugs).
 *
 * Features per approved scope:
 *  - fixed depth, quiescence search (captures + promotions) at the horizon
 *    to suppress the horizon effect; quiescence searches full evasions when
 *    in check so it never stands pat out of a checkmate,
 *  - MVV-LVA capture ordering + promotions first (checks-first ordering
 *    dropped per review: at this depth it costs more than it prunes),
 *  - mate scores adjusted by ply so the search prefers the FASTEST mate
 *    (and the slowest loss) — without this the engine shuffles pieces while
 *    a mate-in-1 sits on the board,
 *  - the fifty-move rule inside the tree (halfmove clock travels with the
 *    board, so this is free and stops the search chasing phantom wins),
 *  - cooperative cancellation, polled every 2048 nodes, for timeout /
 *    new-game while the AI worker is thinking.
 *
 * KNOWN LIMITATIONS (approved v2 items, restated): no transposition table,
 * no repetition detection inside the tree, no iterative deepening.
 *
 * NOT thread-safe: one Search instance per worker, operating on a private
 * Board copy.
 */
public final class Search {

    public static final int MATE_SCORE = 100_000;
    /** Any score beyond this bound is a forced mate. */
    public static final int MATE_THRESHOLD = MATE_SCORE - 1000;
    private static final int INFINITY = MATE_SCORE + 1;
    private static final int CANCEL_CHECK_MASK = 2047;

    public record Result(Move bestMove, int score, long nodes) {}

    private static final class Cancelled extends RuntimeException {
        Cancelled() { super(null, null, false, false); }
    }

    private final MoveGenerator gen = new MoveGenerator();
    private final Evaluator eval = new Evaluator();
    private AtomicBoolean cancel;
    private long nodes;

    /**
     * @param cancel checked cooperatively; when set, the search aborts and
     *               returns null (caller must discard the result).
     * @return best move + score, or null if cancelled. Never called on a
     *         terminal position by this codebase; if it is, bestMove is null.
     */
    public Result findBest(Board board, int depth, AtomicBoolean cancel) {
        if (depth < 1) throw new IllegalArgumentException("depth >= 1 required");
        this.cancel = cancel;
        this.nodes = 0;

        List<Move> moves = gen.generateLegal(board);
        orderMoves(moves);
        Move best = null;
        int alpha = -INFINITY, beta = INFINITY;
        Undo u = new Undo();
        try {
            for (Move m : moves) {
                board.makeMove(m, u);
                int score = -negamax(board, depth - 1, -beta, -alpha, 1);
                board.unmakeMove(m, u);
                if (score > alpha || best == null) {
                    alpha = score;
                    best = m;
                }
            }
        } catch (Cancelled c) {
            return null;
        }
        return new Result(best, alpha, nodes);
    }

    private int negamax(Board b, int depth, int alpha, int beta, int ply) {
        if ((++nodes & CANCEL_CHECK_MASK) == 0 && cancel != null && cancel.get())
            throw new Cancelled();

        if (b.halfmoveClock() >= 100) return 0;
        if (depth == 0) return quiescence(b, alpha, beta, ply);

        List<Move> moves = gen.generateLegal(b);
        if (moves.isEmpty()) {
            // Ply-adjusted mate score: closer mates score higher.
            return b.inCheck(b.sideToMove()) ? -(MATE_SCORE - ply) : 0;
        }
        orderMoves(moves);

        Undo u = new Undo();
        for (Move m : moves) {
            b.makeMove(m, u);
            int score = -negamax(b, depth - 1, -beta, -alpha, ply + 1);
            b.unmakeMove(m, u);
            if (score > alpha) alpha = score;
            if (alpha >= beta) break;   // beta cutoff
        }
        return alpha;
    }

    /**
     * Quiescence: resolve captures/promotions until the position is quiet, so
     * the evaluator is never consulted mid-exchange. When in check, all
     * evasions are searched and stand-pat is disallowed (standing pat while
     * mated would return a garbage score).
     */
    private int quiescence(Board b, int alpha, int beta, int ply) {
        if ((++nodes & CANCEL_CHECK_MASK) == 0 && cancel != null && cancel.get())
            throw new Cancelled();

        int us = b.sideToMove();
        boolean inCheck = b.inCheck(us);
        List<Move> moves;

        if (inCheck) {
            moves = gen.generateLegal(b);
            if (moves.isEmpty()) return -(MATE_SCORE - ply);
        } else {
            int stand = eval.evaluate(b);
            if (stand >= beta) return stand;
            if (stand > alpha) alpha = stand;
            moves = gen.generateCaptures(b);   // pseudo-legal; filtered below
        }
        orderMoves(moves);

        Undo u = new Undo();
        for (Move m : moves) {
            b.makeMove(m, u);
            if (!inCheck && b.inCheck(us)) {   // legality filter for pseudo-legal captures
                b.unmakeMove(m, u);
                continue;
            }
            int score = -quiescence(b, -beta, -alpha, ply + 1);
            b.unmakeMove(m, u);
            if (score > alpha) alpha = score;
            if (alpha >= beta) break;
        }
        return alpha;
    }

    /**
     * In-place ordering: promotions first (queen ahead of underpromotions via
     * piece value), then captures by MVV-LVA (most valuable victim, least
     * valuable attacker), then quiet moves in generation order.
     */
    private void orderMoves(List<Move> moves) {
        moves.sort((a, b2) -> Integer.compare(moveScore(b2), moveScore(a)));
    }

    private static int moveScore(Move m) {
        int s = 0;
        if (m.promotion() != 0) s += 10_000 + VALUE[m.promotion()];
        if (m.isCapture()) s += 1_000 + VALUE[typeOf(m.captured())] * 10 - VALUE[typeOf(m.piece())];
        return s;
    }
}

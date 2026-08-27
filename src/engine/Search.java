package engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static engine.Pieces.*;

/**
 * Minimax with alpha-beta pruning, implemented in the negamax formulation
 * (approved decision #3: algebraically identical to explicit min/max
 * branches, one code path, half the surface for sign bugs).
 *
 * Features:
 *  - <b>iterative deepening</b> with an optional wall-clock budget: depths
 *    1, 2, ... up to {@code maxDepth}; the result of the last COMPLETED
 *    iteration is returned when time runs out, and an iteration is not
 *    started when the previous one already used half the budget,
 *  - <b>transposition table</b> ({@link TranspositionTable}) giving exact /
 *    bound cutoffs and, more importantly, the best move from the previous
 *    iteration as the first move to try,
 *  - <b>repetition detection inside the tree</b>: a position that already
 *    occurred on the search path or in the game history scores as a draw,
 *    so a winning engine no longer shuffles into a threefold and a losing
 *    one can seek a perpetual,
 *  - quiescence search (captures + promotions) at the horizon to suppress
 *    the horizon effect; it searches full evasions when in check so it never
 *    stands pat out of a checkmate,
 *  - check extension (a node in check is searched one ply deeper),
 *  - move ordering: TT move, promotions, captures by MVV-LVA, two killer
 *    moves per ply, then quiet moves by history heuristic,
 *  - mate scores adjusted by ply so the search prefers the FASTEST mate
 *    (and the slowest loss),
 *  - the fifty-move rule inside the tree (halfmove clock travels with the
 *    board, so this is free and stops the search chasing phantom wins),
 *  - cooperative cancellation, polled every 2048 nodes, for timeout /
 *    new-game while the AI worker is thinking.
 *
 * Threading: {@link #findBest} is synchronized and works on a private copy
 * of the board, so one instance (and its table) can be kept for a whole game
 * and handed to successive worker threads; a late-cancelled worker simply
 * finishes before the next one starts.
 */
public final class Search {

    public static final int MATE_SCORE = 100_000;
    /** Any score beyond this bound is a forced mate. */
    public static final int MATE_THRESHOLD = MATE_SCORE - 1000;
    public static final int DRAW_SCORE = 0;
    private static final int INFINITY = MATE_SCORE + 1;
    private static final int MAX_PLY = 100;
    private static final int POLL_MASK = 2047;
    private static final int HISTORY_MAX = 30_000;

    /**
     * @param bestMove best move, or null on a terminal position
     * @param score    centipawns from the side to move's view (mate: ±(MATE_SCORE - plies))
     * @param nodes    nodes searched over all iterations
     * @param depth    depth of the last completed iteration
     * @param pv       principal variation of that iteration
     * @param millis   wall time used
     */
    public record Result(Move bestMove, int score, long nodes, int depth, List<Move> pv, long millis) {
        public boolean isMate() { return Math.abs(score) > MATE_THRESHOLD; }

        /** Full moves to mate: positive when the side to move mates, negative when it is mated; 0 if none. */
        public int mateIn() {
            if (!isMate()) return 0;
            int plies = MATE_SCORE - Math.abs(score);
            int moves = (plies + 1) / 2;
            return score > 0 ? moves : -moves;
        }
    }

    private static final class Cancelled extends RuntimeException {
        Cancelled() { super(null, null, false, false); }
    }

    private final MoveGenerator gen = new MoveGenerator();
    private final Evaluator eval = new Evaluator();
    private final TranspositionTable tt;
    private final Move[][] killers = new Move[MAX_PLY + 2][2];
    private final int[][] history = new int[16][64];          // [piece code][to]
    private final Move[][] pvTable = new Move[MAX_PLY + 2][MAX_PLY + 2];
    private final int[] pvLength = new int[MAX_PLY + 2];
    private final Move[] orderBuf = new Move[256];
    private final int[] orderScore = new int[256];
    /** Game history keys followed by the current search path, indexed by rootIndex + ply. */
    private long[] keyStack = new long[MAX_PLY + 2];
    private int rootIndex;

    private AtomicBoolean cancel;
    private long nodes;
    private long deadlineNanos;
    private boolean timeAbortAllowed;

    public Search() { this(20); }

    /** @param ttBits transposition table size as a power of two. */
    public Search(int ttBits) { tt = new TranspositionTable(ttBits); }

    /** Fixed-depth search with no time limit and no game history. */
    public Result findBest(Board board, int depth, AtomicBoolean cancel) {
        return findBest(board, depth, 0L, null, cancel);
    }

    /**
     * Iteratively deepened search.
     *
     * @param board      position to search; never modified (a private copy is used)
     * @param maxDepth   deepest iteration to attempt (>= 1)
     * @param timeMillis wall-clock budget, or 0 for none. Depth 1 always
     *                   completes; afterwards the search aborts at the deadline
     *                   and returns the last completed iteration.
     * @param priorKeys  Zobrist keys of every earlier position of the game,
     *                   oldest first, EXCLUDING the current one — for repetition
     *                   detection; may be null
     * @param cancel     checked cooperatively; when set, the search aborts and
     *                   returns null (caller must discard the result)
     * @return best move + score, or null if cancelled. On a terminal position
     *         {@code bestMove} is null and the score is the terminal value.
     */
    public synchronized Result findBest(Board board, int maxDepth, long timeMillis,
                                        long[] priorKeys, AtomicBoolean cancel) {
        if (maxDepth < 1) throw new IllegalArgumentException("depth >= 1 required");
        Board b = board.copy();
        this.cancel = cancel;
        this.nodes = 0;
        long start = System.nanoTime();
        this.deadlineNanos = timeMillis > 0 ? start + timeMillis * 1_000_000L : Long.MAX_VALUE;

        int n = priorKeys == null ? 0 : priorKeys.length;
        if (keyStack.length < n + MAX_PLY + 2) keyStack = new long[n + MAX_PLY + 2];
        if (n > 0) System.arraycopy(priorKeys, 0, keyStack, 0, n);
        rootIndex = n;
        keyStack[rootIndex] = b.zobristKey();

        tt.newSearch();
        for (Move[] k : killers) { k[0] = null; k[1] = null; }
        for (int[] h : history) Arrays.fill(h, 0);

        List<Move> rootMoves = gen.generateLegal(b);
        if (rootMoves.isEmpty()) {
            int s = b.inCheck(b.sideToMove()) ? -MATE_SCORE : DRAW_SCORE;
            return new Result(null, s, 0, 0, List.of(), 0);
        }

        Result best = null;
        for (int d = 1; d <= maxDepth; d++) {
            timeAbortAllowed = d > 1;
            int score;
            try {
                score = searchRoot(b, rootMoves, d);
            } catch (Cancelled c) {
                break;
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            best = new Result(pvTable[0][0], score, nodes, d, principalVariation(), elapsedMs);
            if (Math.abs(score) > MATE_THRESHOLD) break;              // forced mate found: deeper cannot help
            if (timeMillis > 0 && elapsedMs * 2 >= timeMillis) break; // next iteration would overrun
        }
        if (cancel != null && cancel.get()) return null;
        return best;
    }

    // ---- root ----

    private int searchRoot(Board b, List<Move> moves, int depth) {
        pvLength[0] = 0;
        long key = b.zobristKey();
        Move ttMove = tt.probe(key) ? tt.hitMove() : null;
        order(moves, ttMove, 0);

        int alpha = -INFINITY, beta = INFINITY;
        Move best = null;
        Undo u = new Undo();
        for (Move m : moves) {
            b.makeMove(m, u);
            keyStack[rootIndex + 1] = b.zobristKey();
            int score = -negamax(b, depth - 1, -beta, -alpha, 1);
            b.unmakeMove(m, u);
            if (score > alpha || best == null) {
                alpha = score;
                best = m;
                updatePv(0, m);
            }
        }
        tt.store(key, depth, TranspositionTable.EXACT, toTT(alpha, 0), best);
        return alpha;
    }

    // ---- main search ----

    private int negamax(Board b, int depth, int alpha, int beta, int ply) {
        pvLength[ply] = 0;
        poll();
        if (ply >= MAX_PLY) return eval.evaluate(b);
        if (b.halfmoveClock() >= 100) return DRAW_SCORE;
        if (isRepetition(b, ply)) return DRAW_SCORE;

        boolean inCheck = b.inCheck(b.sideToMove());
        if (inCheck) depth++;                                   // check extension
        if (depth <= 0) return quiescence(b, alpha, beta, ply);

        long key = b.zobristKey();
        Move ttMove = null;
        if (tt.probe(key)) {
            ttMove = tt.hitMove();
            if (tt.hitDepth() >= depth) {
                int s = fromTT(tt.hitScore(), ply);
                int flag = tt.hitFlag();
                if (flag == TranspositionTable.EXACT
                        || (flag == TranspositionTable.LOWER && s >= beta)
                        || (flag == TranspositionTable.UPPER && s <= alpha)) {
                    return s;
                }
            }
        }

        List<Move> moves = gen.generateLegal(b);
        if (moves.isEmpty()) {
            // Ply-adjusted mate score: closer mates score higher.
            return inCheck ? -(MATE_SCORE - ply) : DRAW_SCORE;
        }
        order(moves, ttMove, ply);

        int alphaOrig = alpha;
        int bestScore = -INFINITY;
        Move best = null;
        Undo u = new Undo();
        for (Move m : moves) {
            b.makeMove(m, u);
            keyStack[rootIndex + ply + 1] = b.zobristKey();
            int score = -negamax(b, depth - 1, -beta, -alpha, ply + 1);
            b.unmakeMove(m, u);
            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
            if (score > alpha) {
                alpha = score;
                updatePv(ply, m);
                if (alpha >= beta) {                             // beta cutoff
                    if (!m.isCapture() && m.promotion() == 0) rememberQuietCutoff(m, ply, depth);
                    break;
                }
            }
        }
        int flag = bestScore <= alphaOrig ? TranspositionTable.UPPER
                 : bestScore >= beta      ? TranspositionTable.LOWER
                 : TranspositionTable.EXACT;
        tt.store(key, depth, flag, toTT(bestScore, ply), best);
        return bestScore;
    }

    /**
     * Quiescence: resolve captures/promotions until the position is quiet, so
     * the evaluator is never consulted mid-exchange. When in check, all
     * evasions are searched and stand-pat is disallowed (standing pat while
     * mated would return a garbage score).
     */
    private int quiescence(Board b, int alpha, int beta, int ply) {
        pvLength[ply] = 0;
        poll();
        int us = b.sideToMove();
        boolean inCheck = b.inCheck(us);
        if (ply >= MAX_PLY) return inCheck ? DRAW_SCORE : eval.evaluate(b);
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
        order(moves, null, ply);

        Undo u = new Undo();
        for (Move m : moves) {
            b.makeMove(m, u);
            if (!inCheck && b.inCheck(us)) {   // legality filter for pseudo-legal captures
                b.unmakeMove(m, u);
                continue;
            }
            int score = -quiescence(b, -beta, -alpha, ply + 1);
            b.unmakeMove(m, u);
            if (score > alpha) {
                alpha = score;
                updatePv(ply, m);
            }
            if (alpha >= beta) break;
        }
        return alpha;
    }

    // ---- helpers ----

    private void poll() {
        if ((++nodes & POLL_MASK) != 0) return;
        if (cancel != null && cancel.get()) throw new Cancelled();
        if (timeAbortAllowed && System.nanoTime() >= deadlineNanos) throw new Cancelled();
    }

    /**
     * Has the position at {@code ply} already occurred on the path to it or
     * in the game history? Only positions with the same side to move and
     * within the halfmove clock can match, so the scan is short. A single
     * earlier occurrence is treated as a draw — the standard engine
     * convention: whoever could repeat once can repeat twice.
     */
    private boolean isRepetition(Board b, int ply) {
        int idx = rootIndex + ply;
        long key = keyStack[idx];
        int limit = Math.max(0, idx - b.halfmoveClock());
        for (int i = idx - 2; i >= limit; i -= 2) if (keyStack[i] == key) return true;
        return false;
    }

    private void rememberQuietCutoff(Move m, int ply, int depth) {
        if (!m.equals(killers[ply][0])) {
            killers[ply][1] = killers[ply][0];
            killers[ply][0] = m;
        }
        int h = history[m.piece()][m.to()] + depth * depth;
        history[m.piece()][m.to()] = Math.min(h, HISTORY_MAX);
    }

    /** Mate scores are stored relative to the node so they stay valid at any ply. */
    private static int toTT(int score, int ply) {
        if (score > MATE_THRESHOLD) return score + ply;
        if (score < -MATE_THRESHOLD) return score - ply;
        return score;
    }

    private static int fromTT(int score, int ply) {
        if (score > MATE_THRESHOLD) return score - ply;
        if (score < -MATE_THRESHOLD) return score + ply;
        return score;
    }

    private void updatePv(int ply, Move m) {
        pvTable[ply][0] = m;
        int len = pvLength[ply + 1];
        System.arraycopy(pvTable[ply + 1], 0, pvTable[ply], 1, len);
        pvLength[ply] = len + 1;
    }

    private List<Move> principalVariation() {
        List<Move> pv = new ArrayList<>(pvLength[0]);
        for (int i = 0; i < pvLength[0]; i++) pv.add(pvTable[0][i]);
        return pv;
    }

    /**
     * In-place ordering (insertion sort on parallel arrays — move lists are
     * short): TT move, promotions (queen ahead of underpromotions via piece
     * value), captures by MVV-LVA (most valuable victim, least valuable
     * attacker), killer moves, then quiet moves by history score.
     */
    private void order(List<Move> moves, Move ttMove, int ply) {
        int n = moves.size();
        for (int i = 0; i < n; i++) {
            orderBuf[i] = moves.get(i);
            orderScore[i] = moveScore(orderBuf[i], ttMove, ply);
        }
        for (int i = 1; i < n; i++) {
            Move m = orderBuf[i];
            int s = orderScore[i];
            int j = i - 1;
            while (j >= 0 && orderScore[j] < s) {
                orderBuf[j + 1] = orderBuf[j];
                orderScore[j + 1] = orderScore[j];
                j--;
            }
            orderBuf[j + 1] = m;
            orderScore[j + 1] = s;
        }
        for (int i = 0; i < n; i++) moves.set(i, orderBuf[i]);
    }

    private int moveScore(Move m, Move ttMove, int ply) {
        if (m.equals(ttMove)) return 1_000_000;
        int s = 0;
        if (m.promotion() != 0) s += 100_000 + VALUE[m.promotion()];
        if (m.isCapture()) return s + 50_000 + VALUE[typeOf(m.captured())] * 10 - VALUE[typeOf(m.piece())];
        if (s > 0) return s;
        if (m.equals(killers[ply][0])) return 40_000;
        if (m.equals(killers[ply][1])) return 39_000;
        return history[m.piece()][m.to()];
    }
}

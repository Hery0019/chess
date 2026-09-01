package engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static engine.Pieces.*;

/**
 * Minimax with alpha-beta pruning, implemented in the negamax formulation
 * (approved decision #3: algebraically identical to explicit min/max
 * branches, one code path, half the surface for sign bugs), extended with
 * the selectivity techniques of modern engines. Each of them keeps the
 * principal variation searched at full depth and full window and only spends
 * less on moves the search has reason to believe are worse:
 *
 *  - <b>iterative deepening</b> with an optional wall-clock budget: depths
 *    1, 2, ... up to {@code maxDepth}; the result of the last COMPLETED
 *    iteration is returned when time runs out, and an iteration is not
 *    started when the previous one already used half the budget,
 *  - <b>aspiration windows</b>: from depth 4 an iteration starts with a
 *    narrow window around the previous score and widens it on failure,
 *  - <b>principal variation search</b> (PVS): after the first move of a
 *    node, every move is searched with a null window (alpha, alpha + 1) —
 *    cheap proof that it is not better — and only re-searched at full window
 *    when that proof fails,
 *  - <b>null-move pruning</b>: if passing the turn (illegal, but informative)
 *    at reduced depth still scores above beta, the node is cut. Off when in
 *    check, near mate scores, and without non-pawn material (zugzwang),
 *  - <b>late move reductions</b> (LMR): quiet moves late in the order are
 *    searched at reduced depth; any that beat alpha get the full depth,
 *  - <b>futility pruning</b>: at depth 1-2, quiet non-checking moves are
 *    skipped when the static evaluation plus a margin cannot reach alpha;
 *    <b>reverse futility</b> cuts a node whose static evaluation beats beta by
 *    a depth-scaled margin,
 *  - <b>transposition table</b> ({@link TranspositionTable}) giving exact /
 *    bound cutoffs and the best move from the previous iteration first,
 *  - <b>repetition detection inside the tree</b>: a position that already
 *    occurred on the search path or in the game history scores as a draw,
 *  - quiescence search (captures + promotions) at the horizon, with delta
 *    pruning and <b>SEE</b> ({@link StaticExchange}): captures that lose
 *    material are not tried; when in check all evasions are searched,
 *  - check extension, mate-distance pruning, mate scores adjusted by ply
 *    (fastest mate preferred), the fifty-move rule inside the tree,
 *  - move ordering: TT move, promotions, captures by MVV-LVA, two killer
 *    moves per ply, then quiet moves by history heuristic (quiet moves that
 *    failed to cut are penalised),
 *  - cooperative cancellation, polled every 2048 nodes.
 *
 * Every technique can be switched off through {@link Options}. That is not
 * a user setting: {@code test.Arena} plays feature sets against each other,
 * which is how each one earned its place (a technique that does not win
 * games at equal time is not kept).
 *
 * Threading: {@link #findBest} is synchronized and works on a private copy
 * of the board, so one instance (and its table) can be kept for a whole game
 * and handed to successive worker threads; a late-cancelled worker simply
 * finishes before the next one starts.
 */
public final class Search {

    /**
     * Search and evaluation features. {@link #ALL} is the engine as shipped;
     * {@link #BASELINE} is plain alpha-beta with the material + PST
     * evaluation (the v2 engine), kept as the reference opponent.
     * {@code lmr} needs {@code pvs} (the reduction lives inside the
     * null-window search) and is ignored without it.
     */
    public record Options(boolean pvs, boolean nullMove, boolean lmr, boolean futility, boolean aspiration,
                          boolean see, boolean pawnStructure, boolean mobility, boolean pesto) {
        public static final Options ALL = new Options(true, true, true, true, true, true, true, true, true);
        public static final Options BASELINE = new Options(false, false, false, false, false, false, false, false, false);
    }

    public static final int MATE_SCORE = 100_000;
    /** Any score beyond this bound is a forced mate. */
    public static final int MATE_THRESHOLD = MATE_SCORE - 1000;
    public static final int DRAW_SCORE = 0;
    private static final int INFINITY = MATE_SCORE + 1;
    private static final int MAX_PLY = 100;
    private static final int POLL_MASK = 2047;
    private static final int HISTORY_MAX = 30_000;
    private static final int ASPIRATION_WINDOW = 30;
    /** Reverse futility: cut when static eval - margin * depth >= beta, at depths 1..3. */
    private static final int REVERSE_FUTILITY_MARGIN = 120;
    private static final int REVERSE_FUTILITY_MAX_DEPTH = 3;
    /** Futility margins by depth: a quiet move at this depth is assumed unable to gain more. */
    private static final int[] FUTILITY_MARGIN = {0, 150, 300};
    /** Quiescence delta pruning: a capture that cannot lift the stand-pat to alpha even with this bonus is skipped. */
    private static final int DELTA_MARGIN = 200;
    /** LMR reductions by depth and move number (log formula, as in most open-source engines). */
    private static final int[][] LMR_TABLE = new int[MAX_PLY + 2][64];
    static {
        for (int d = 1; d < LMR_TABLE.length; d++) {
            for (int m = 1; m < 64; m++) LMR_TABLE[d][m] = (int) (0.75 + Math.log(d) * Math.log(m) / 2.25);
        }
    }

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

    private final Options options;
    private final MoveGenerator gen = new MoveGenerator();
    private final Evaluator eval;
    private final TranspositionTable tt;
    private final Move[][] killers = new Move[MAX_PLY + 2][2];
    private final int[][] history = new int[16][64];          // [piece code][to]
    private final Move[][] triedQuiets = new Move[MAX_PLY + 2][256];
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
    private volatile java.util.function.Consumer<Result> onIteration;

    public Search() { this(20, Options.ALL); }

    /** @param ttBits transposition table size as a power of two. */
    public Search(int ttBits) { this(ttBits, Options.ALL); }

    public Search(int ttBits, Options options) {
        this.options = options;
        this.tt = new TranspositionTable(ttBits);
        this.eval = new Evaluator(options.pawnStructure(), options.mobility(), options.pesto());
    }

    public Options options() { return options; }

    /**
     * Called on the searching thread after every completed iteration with
     * that iteration's result (the UCI "info" lines, and the answer to give
     * when a search is stopped before it returns). Null to disable.
     */
    public void setIterationListener(java.util.function.Consumer<Result> listener) { this.onIteration = listener; }

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
        long start = System.nanoTime();
        prepare(b, priorKeys, cancel, timeMillis > 0 ? start + timeMillis * 1_000_000L : Long.MAX_VALUE);

        List<Move> rootMoves = gen.generateLegal(b);
        if (rootMoves.isEmpty()) {
            int s = b.inCheck(b.sideToMove()) ? -MATE_SCORE : DRAW_SCORE;
            return new Result(null, s, 0, 0, List.of(), 0);
        }

        Result best = null;
        int guess = 0;
        for (int d = 1; d <= maxDepth; d++) {
            timeAbortAllowed = d > 1;
            int score;
            try {
                score = aspirated(b, rootMoves, d, guess, best != null);
            } catch (Cancelled c) {
                break;
            }
            guess = score;
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            best = new Result(pvTable[0][0], score, nodes, d, principalVariation(), elapsedMs);
            if (onIteration != null) onIteration.accept(best);
            if (Math.abs(score) > MATE_THRESHOLD) break;              // forced mate found: deeper cannot help
            if (timeMillis > 0 && elapsedMs * 2 >= timeMillis) break; // next iteration would overrun
        }
        if (cancel != null && cancel.get()) return null;
        return best;
    }

    /** Exact score of one root move at a fixed depth; see {@link #scoreRootMoves}. */
    public record RootScore(Move move, int score) {}

    /**
     * Scores every legal move at exactly {@code depth} plies with a full
     * window — no time limit, no pruning between root moves — so the
     * caller sees how good each move really is, not just which one is best.
     * {@link Skill} builds its weaker levels on this list.
     *
     * @return one entry per legal move (empty on a terminal position), or
     *         null when cancelled
     */
    public synchronized List<RootScore> scoreRootMoves(Board board, int depth, long[] priorKeys, AtomicBoolean cancel) {
        if (depth < 1) throw new IllegalArgumentException("depth >= 1 required");
        Board b = board.copy();
        prepare(b, priorKeys, cancel, Long.MAX_VALUE);
        timeAbortAllowed = false;
        List<RootScore> out = new ArrayList<>();
        Undo u = new Undo();
        try {
            for (Move m : gen.generateLegal(b)) {
                b.makeMove(m, u);
                keyStack[rootIndex + 1] = b.zobristKey();
                int score = -negamax(b, depth - 1, -INFINITY, INFINITY, 1, true);
                b.unmakeMove(m, u);
                out.add(new RootScore(m, score));
            }
        } catch (Cancelled c) {
            return null;
        }
        if (cancel != null && cancel.get()) return null;   // same contract as findBest: a cancelled call yields nothing
        return out;
    }

    /** Nodes searched by the last call (for reporting). */
    public long lastNodes() { return nodes; }

    /** Per-call state: cancellation, deadline, repetition stack, fresh killers / history, aged table. */
    private void prepare(Board b, long[] priorKeys, AtomicBoolean cancel, long deadlineNanos) {
        this.cancel = cancel;
        this.nodes = 0;
        this.deadlineNanos = deadlineNanos;

        int n = priorKeys == null ? 0 : priorKeys.length;
        if (keyStack.length < n + MAX_PLY + 2) keyStack = new long[n + MAX_PLY + 2];
        if (n > 0) System.arraycopy(priorKeys, 0, keyStack, 0, n);
        rootIndex = n;
        keyStack[rootIndex] = b.zobristKey();

        tt.newSearch();
        for (Move[] k : killers) { k[0] = null; k[1] = null; }
        for (int[] h : history) Arrays.fill(h, 0);
    }

    // ---- root ----

    /**
     * One iteration, inside an aspiration window around the previous
     * iteration's score when there is one: most iterations land inside it
     * and search far fewer nodes; a fail outside it widens the window and
     * retries, so the final answer is always an exact score.
     */
    private int aspirated(Board b, List<Move> moves, int depth, int guess, boolean haveGuess) {
        if (!options.aspiration() || !haveGuess || depth < 4 || Math.abs(guess) > MATE_THRESHOLD) {
            return searchRoot(b, moves, depth, -INFINITY, INFINITY);
        }
        int delta = ASPIRATION_WINDOW;
        int alpha = Math.max(-INFINITY, guess - delta), beta = Math.min(INFINITY, guess + delta);
        while (true) {
            int score = searchRoot(b, moves, depth, alpha, beta);
            if (score <= alpha)     alpha = Math.max(-INFINITY, score - delta);
            else if (score >= beta) beta = Math.min(INFINITY, score + delta);
            else return score;
            delta *= 2;
            if (delta > 1000 || Math.abs(score) > MATE_THRESHOLD) { alpha = -INFINITY; beta = INFINITY; }
        }
    }

    private int searchRoot(Board b, List<Move> moves, int depth, int alpha, int beta) {
        pvLength[0] = 0;
        long key = b.zobristKey();
        int alphaOrig = alpha;
        Move ttMove = tt.probe(key) ? tt.hitMove() : null;
        order(b, moves, ttMove, 0);

        Move best = null;
        int bestScore = -INFINITY, searched = 0;
        Undo u = new Undo();
        for (Move m : moves) {
            b.makeMove(m, u);
            keyStack[rootIndex + 1] = b.zobristKey();
            int score;
            if (searched == 0 || !options.pvs()) {
                score = -negamax(b, depth - 1, -beta, -alpha, 1, true);
            } else {
                score = -negamax(b, depth - 1, -alpha - 1, -alpha, 1, true);
                if (score > alpha && score < beta) score = -negamax(b, depth - 1, -beta, -alpha, 1, true);
            }
            b.unmakeMove(m, u);
            searched++;
            if (score > bestScore) {
                bestScore = score;
                best = m;
                if (score > alpha || searched == 1) updatePv(0, m);
            }
            if (score > alpha) {
                alpha = score;
                if (alpha >= beta) break;
            }
        }
        int flag = bestScore <= alphaOrig ? TranspositionTable.UPPER
                 : bestScore >= beta      ? TranspositionTable.LOWER
                 : TranspositionTable.EXACT;
        tt.store(key, depth, flag, toTT(bestScore, 0), best);
        return bestScore;
    }

    // ---- main search ----

    /**
     * @param nullAllowed false directly after a null move, so two passes in a
     *                    row (which would compare the position with itself)
     *                    never happen
     */
    private int negamax(Board b, int depth, int alpha, int beta, int ply, boolean nullAllowed) {
        pvLength[ply] = 0;
        poll();
        if (ply >= MAX_PLY) return eval.evaluate(b);
        if (b.halfmoveClock() >= 100) return DRAW_SCORE;
        if (isRepetition(b, ply)) return DRAW_SCORE;

        boolean inCheck = b.inCheck(b.sideToMove());
        if (inCheck) depth++;                                   // check extension
        if (depth <= 0) return quiescence(b, alpha, beta, ply);

        // Mate-distance pruning: nothing here can beat a mate already found closer to the root.
        alpha = Math.max(alpha, -(MATE_SCORE - ply));
        beta = Math.min(beta, MATE_SCORE - ply - 1);
        if (alpha >= beta) return alpha;

        boolean pvNode = beta - alpha > 1;
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

        // Static-evaluation based pruning: only off the principal variation,
        // never in check (the eval is meaningless there) and never around
        // mate scores (a margin means nothing next to a mate).
        boolean pruningNode = !pvNode && !inCheck && Math.abs(beta) < MATE_THRESHOLD;
        int staticEval = 0;
        if (pruningNode && (options.futility() || options.nullMove())) {
            staticEval = eval.evaluate(b);

            if (options.futility() && depth <= REVERSE_FUTILITY_MAX_DEPTH
                    && staticEval - REVERSE_FUTILITY_MARGIN * depth >= beta) {
                return staticEval;
            }

            if (options.nullMove() && nullAllowed && depth >= 3 && staticEval >= beta
                    && b.hasNonPawnMaterial(b.sideToMove())) {
                int r = depth >= 6 ? 3 : 2;
                Undo nu = new Undo();
                b.makeNullMove(nu);
                keyStack[rootIndex + ply + 1] = b.zobristKey();
                int score = -negamax(b, depth - 1 - r, -beta, -beta + 1, ply + 1, false);
                b.unmakeNullMove(nu);
                if (score >= beta) return score >= MATE_THRESHOLD ? beta : score;   // never trust a null-move mate
            }
        }

        List<Move> moves = gen.generateLegal(b);
        if (moves.isEmpty()) {
            // Ply-adjusted mate score: closer mates score higher.
            return inCheck ? -(MATE_SCORE - ply) : DRAW_SCORE;
        }
        order(b, moves, ttMove, ply);

        boolean futile = pruningNode && options.futility() && depth <= 2
                && staticEval + FUTILITY_MARGIN[depth] <= alpha;

        int alphaOrig = alpha;
        int bestScore = -INFINITY;
        int searched = 0, quietCount = 0;
        Move best = null;
        Undo u = new Undo();
        for (Move m : moves) {
            boolean quiet = !m.isCapture() && m.promotion() == 0;
            b.makeMove(m, u);
            boolean givesCheck = b.inCheck(b.sideToMove());
            if (futile && quiet && !givesCheck && searched > 0) {   // cannot reach alpha: not worth a search
                b.unmakeMove(m, u);
                continue;
            }
            keyStack[rootIndex + ply + 1] = b.zobristKey();
            int newDepth = depth - 1;
            int score;
            if (searched == 0 || !options.pvs()) {
                score = -negamax(b, newDepth, -beta, -alpha, ply + 1, true);
            } else {
                int r = 0;
                if (options.lmr() && depth >= 3 && searched >= 2 && quiet && !givesCheck
                        && !m.equals(killers[ply][0]) && !m.equals(killers[ply][1])) {
                    r = LMR_TABLE[Math.min(depth, MAX_PLY + 1)][Math.min(searched, 63)];
                    if (pvNode) r--;
                    r = Math.max(0, Math.min(r, newDepth - 1));
                }
                score = -negamax(b, newDepth - r, -alpha - 1, -alpha, ply + 1, true);
                if (r > 0 && score > alpha) score = -negamax(b, newDepth, -alpha - 1, -alpha, ply + 1, true);
                if (score > alpha && score < beta) score = -negamax(b, newDepth, -beta, -alpha, ply + 1, true);
            }
            b.unmakeMove(m, u);
            searched++;
            if (quiet) triedQuiets[ply][quietCount++] = m;
            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
            if (score > alpha) {
                alpha = score;
                updatePv(ply, m);
                if (alpha >= beta) {                             // beta cutoff
                    if (quiet) rememberQuietCutoff(m, ply, depth, quietCount);
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
     * mated would return a garbage score). Otherwise captures that cannot
     * bring the score back to alpha (delta pruning) or that lose material
     * (SEE) are skipped.
     */
    private int quiescence(Board b, int alpha, int beta, int ply) {
        pvLength[ply] = 0;
        poll();
        int us = b.sideToMove();
        boolean inCheck = b.inCheck(us);
        if (ply >= MAX_PLY) return inCheck ? DRAW_SCORE : eval.evaluate(b);
        List<Move> moves;
        int stand = 0;

        if (inCheck) {
            moves = gen.generateLegal(b);
            if (moves.isEmpty()) return -(MATE_SCORE - ply);
        } else {
            stand = eval.evaluate(b);
            if (stand >= beta) return stand;
            if (stand > alpha) alpha = stand;
            moves = gen.generateCaptures(b);   // pseudo-legal; filtered below
        }
        order(b, moves, null, ply);

        Undo u = new Undo();
        for (Move m : moves) {
            if (!inCheck && m.promotion() == 0) {
                if (options.futility() && stand + VALUE[typeOf(m.captured())] + DELTA_MARGIN <= alpha) continue;
                if (options.see() && losingCapture(b, m)) continue;
            }
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

    /**
     * A quiet move just caused a beta cutoff: make it a killer for this ply
     * and raise its history; the quiet moves tried before it at this node
     * were ordered too high, so they lose the same amount.
     */
    private void rememberQuietCutoff(Move m, int ply, int depth, int quietCount) {
        if (!m.equals(killers[ply][0])) {
            killers[ply][1] = killers[ply][0];
            killers[ply][0] = m;
        }
        int bonus = depth * depth;
        bumpHistory(m, bonus);
        for (int i = 0; i < quietCount - 1; i++) bumpHistory(triedQuiets[ply][i], -bonus);
    }

    private void bumpHistory(Move m, int delta) {
        int h = history[m.piece()][m.to()] + delta;
        history[m.piece()][m.to()] = Math.max(-HISTORY_MAX, Math.min(HISTORY_MAX, h));
    }

    /**
     * Cheap test first: taking a piece worth at least the attacker can never
     * lose material (at worst an even trade), so SEE only runs for captures
     * by a more valuable piece.
     */
    private static boolean losingCapture(Board b, Move m) {
        if (VALUE[typeOf(m.captured())] >= VALUE[typeOf(m.piece())]) return false;
        return StaticExchange.see(b, m) < 0;
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
    private void order(Board b, List<Move> moves, Move ttMove, int ply) {
        int n = moves.size();
        for (int i = 0; i < n; i++) {
            orderBuf[i] = moves.get(i);
            orderScore[i] = moveScore(b, orderBuf[i], ttMove, ply);
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

    private int moveScore(Board b, Move m, Move ttMove, int ply) {
        if (m.equals(ttMove)) return 1_000_000;
        if (m.promotion() != 0) {
            return 100_000 + VALUE[m.promotion()] + (m.isCapture() ? VALUE[typeOf(m.captured())] : 0);
        }
        // Captures by MVV-LVA only. Demoting SEE-losing captures below the
        // quiet moves was tried and measured in the arena: no gain (the SEE
        // cost at every node ate what the ordering saved), so it went.
        if (m.isCapture()) return 50_000 + VALUE[typeOf(m.captured())] * 10 - VALUE[typeOf(m.piece())];
        if (m.equals(killers[ply][0])) return 40_000;
        if (m.equals(killers[ply][1])) return 39_000;
        return history[m.piece()][m.to()];
    }
}

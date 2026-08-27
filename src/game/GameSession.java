package game;

import engine.Board;
import engine.Move;
import engine.MoveGenerator;
import engine.Undo;
import engine.Pieces;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static engine.Pieces.*;

/**
 * The authoritative game state: live board, move history, repetition table,
 * and terminal-result adjudication. Owns every rule that lives ABOVE the
 * single-position level (threefold repetition needs history; timeout needs a
 * clock verdict) — the engine deliberately knows nothing about any of this.
 *
 * THREADING CONTRACT: confined to the EDT. The AI worker never touches this
 * object; it receives {@code board().copy()} and hands back a Move, which the
 * EDT applies. Asserted in debug builds (run with -ea to enforce).
 *
 * Draw policy (approved decision #2): all draws are AUTO-DECLARED the moment
 * their condition holds — threefold repetition, the fifty-move rule at
 * exactly 50 full moves (halfmove clock 100), and dead positions
 * (insufficient material). No claim mechanism.
 */
public final class GameSession {

    private final Board board;
    private final MoveGenerator gen = new MoveGenerator();
    private final List<Move> history = new ArrayList<>();
    /** zobrist key -> occurrence count, including the initial position. */
    private final Map<Long, Integer> repetitions = new HashMap<>();
    /** Key of every position so far, oldest first, including the current one. */
    private final List<Long> keyHistory = new ArrayList<>();
    /** SAN of every move in {@link #history}, computed as each move is applied. */
    private final List<String> sanHistory = new ArrayList<>();
    /** Undo record of every move in {@link #history}, so moves can be taken back. */
    private final List<Undo> undos = new ArrayList<>();
    private GameResult result = GameResult.ONGOING;
    private List<Move> cachedLegal;   // invalidated on every applyMove

    public GameSession() { this(Board.startPosition()); }

    /** Test seam: start from an arbitrary position. */
    public GameSession(Board initial) {
        this.board = initial;
        repetitions.put(board.zobristKey(), 1);
        keyHistory.add(board.zobristKey());
        adjudicate();   // the initial position may already be terminal
    }

    public Board board()            { return board; }
    public GameResult result()      { return result; }
    public List<Move> history()     { return List.copyOf(history); }
    public List<String> sanHistory(){ return List.copyOf(sanHistory); }
    public int plyCount()           { return history.size(); }
    public int sideToMove()         { return board.sideToMove(); }
    public Move lastMove()          { return history.isEmpty() ? null : history.get(history.size() - 1); }

    /**
     * Zobrist keys of every position BEFORE the current one, oldest first —
     * what the search needs to detect repetitions against the game so far.
     */
    public long[] priorPositionKeys() {
        long[] keys = new long[keyHistory.size() - 1];
        for (int i = 0; i < keys.length; i++) keys[i] = keyHistory.get(i);
        return keys;
    }

    /** Legal moves in the current position (cached until the next applyMove). */
    public List<Move> legalMoves() {
        assert javax.swing.SwingUtilities.isEventDispatchThread() || !isGuiPresent()
                : "GameSession accessed off the EDT";
        if (cachedLegal == null) cachedLegal = gen.generateLegal(board);
        return cachedLegal;
    }

    /**
     * Applies a move (must be legal — enforced) and re-adjudicates.
     * @throws IllegalArgumentException if the move is not legal here. The UI
     *         only ever submits moves taken from {@link #legalMoves()}; the AI
     *         worker's move is validated too, since it was computed against a
     *         snapshot and this is the last line of defence against a
     *         stale-worker race.
     */
    public void applyMove(Move m) {
        assert javax.swing.SwingUtilities.isEventDispatchThread() || !isGuiPresent()
                : "GameSession accessed off the EDT";
        if (result.isOver()) throw new IllegalStateException("game is over");
        if (!legalMoves().contains(m)) throw new IllegalArgumentException("illegal move: " + m);
        String san = Notation.san(board, m, legalMoves());   // needs the pre-move position
        Undo u = new Undo();
        board.makeMove(m, u);
        history.add(m);
        sanHistory.add(san);
        undos.add(u);
        cachedLegal = null;
        repetitions.merge(board.zobristKey(), 1, Integer::sum);
        keyHistory.add(board.zobristKey());
        adjudicate();
    }

    public boolean canUndo() { return !history.isEmpty(); }

    /**
     * Takes back the last move, restoring the board, the repetition table and
     * the key history exactly, and re-adjudicates (a game that had ended —
     * by mate, draw or timeout — becomes ongoing again from the restored
     * position).
     * @return the move that was taken back
     * @throws IllegalStateException if there is no move to take back
     */
    public Move undoLastMove() {
        assert javax.swing.SwingUtilities.isEventDispatchThread() || !isGuiPresent()
                : "GameSession accessed off the EDT";
        if (history.isEmpty()) throw new IllegalStateException("nothing to undo");
        int last = history.size() - 1;
        repetitions.merge(board.zobristKey(), -1, (a, b) -> a + b <= 0 ? null : a + b);
        keyHistory.remove(keyHistory.size() - 1);
        Move m = history.remove(last);
        board.unmakeMove(m, undos.remove(last));
        sanHistory.remove(last);
        cachedLegal = null;
        result = GameResult.ONGOING;
        adjudicate();
        return m;
    }

    /**
     * Called by the UI when a player's clock hits zero. Per FIDE 6.9: the
     * flag-faller loses UNLESS the opponent cannot possibly deliver mate.
     * A lone king can never mate; K+N / K+B can still helpmate, so those
     * remain wins for the opponent.
     */
    public void timeout(int flagFallerColor) {
        if (result.isOver()) return;   // e.g. mate landed on the same tick — first verdict stands
        int winner = flagFallerColor ^ 1;
        if (hasBareKing(winner)) {
            result = GameResult.DRAW_TIMEOUT_VS_BARE_KING;
        } else {
            result = winner == WHITE ? GameResult.WHITE_WINS_TIMEOUT : GameResult.BLACK_WINS_TIMEOUT;
        }
    }

    /** {@code color} resigns; no effect once the game is over. */
    public void resign(int color) {
        if (result.isOver()) return;
        result = color == WHITE ? GameResult.BLACK_WINS_RESIGNATION : GameResult.WHITE_WINS_RESIGNATION;
    }

    /** Both sides agree to a draw; no effect once the game is over. */
    public void agreeDraw() {
        if (result.isOver()) return;
        result = GameResult.DRAW_AGREED;
    }

    // ---- adjudication ----

    private void adjudicate() {
        if (legalMoves().isEmpty()) {
            if (board.inCheck(board.sideToMove())) {
                result = board.sideToMove() == WHITE
                        ? GameResult.BLACK_WINS_MATE : GameResult.WHITE_WINS_MATE;
            } else {
                result = GameResult.DRAW_STALEMATE;
            }
            return;
        }
        if (isInsufficientMaterial()) { result = GameResult.DRAW_INSUFFICIENT_MATERIAL; return; }
        if (board.halfmoveClock() >= 100) { result = GameResult.DRAW_FIFTY_MOVES; return; }
        if (repetitions.getOrDefault(board.zobristKey(), 0) >= 3) {
            result = GameResult.DRAW_REPETITION;
        }
    }

    /**
     * FIDE dead positions: K vs K, K+B vs K, K+N vs K, and K+B vs K+B with
     * both bishops on the same square colour. Anything else (including two
     * knights) is not auto-declared.
     */
    private boolean isInsufficientMaterial() {
        int minors = 0;
        int[] bishopSquareColor = {-1, -1};   // per side; -1 = no bishop seen
        for (int sq = 0; sq < 64; sq++) {
            int p = board.pieceAt(sq);
            if (p == EMPTY) continue;
            switch (typeOf(p)) {
                case PAWN, ROOK, QUEEN -> { return false; }
                case KNIGHT -> {
                    minors++;
                    bishopSquareColor[colorOf(p)] = -2;   // marker: side has a knight
                }
                case BISHOP -> {
                    minors++;
                    int side = colorOf(p);
                    int sqColor = ((sq >>> 3) + (sq & 7)) & 1;
                    // A second bishop for a side, or bishop + knight: not dead.
                    if (bishopSquareColor[side] != -1) return false;
                    bishopSquareColor[side] = sqColor;
                }
                case KING -> { /* always present */ }
                default -> { }
            }
            if (minors > 2) return false;
        }
        if (minors <= 1) return true;                       // KvK, K+m vs K
        // Exactly two minors: dead only if one bishop each, same square colour.
        return bishopSquareColor[0] >= 0 && bishopSquareColor[0] == bishopSquareColor[1];
    }

    private boolean hasBareKing(int color) {
        for (int sq = 0; sq < 64; sq++) {
            int p = board.pieceAt(sq);
            if (p != EMPTY && colorOf(p) == color && typeOf(p) != KING) return false;
        }
        return true;
    }

    /** True when a display exists — lets the EDT assertion pass in headless tests. */
    private static boolean isGuiPresent() {
        return !java.awt.GraphicsEnvironment.isHeadless();
    }

    /** Human-readable status line for the UI. */
    public String statusText() {
        if (result.isOver()) return result.message();
        String turn = GameConfig.colorName(board.sideToMove()) + " to move";
        return board.inCheck(board.sideToMove()) ? turn + " — check!" : turn;
    }

    public boolean inCheckNow() {
        return !result.isOver() && board.inCheck(board.sideToMove());
    }

    public static int otherColor(int c) { return c ^ 1; }

    public static boolean isWhite(int c) { return c == Pieces.WHITE; }
}

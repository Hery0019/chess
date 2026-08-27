package ui;

import engine.Board;
import engine.Move;
import engine.Pieces;
import game.GameSession;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static engine.Pieces.*;

/**
 * Custom-painted 8x8 board. Pure view + input: owns selection, drag and
 * premove state and the pixel/square mapping, but every accepted move is
 * delegated upward via the {@code moveConsumer} — this panel never mutates
 * the session itself.
 *
 * <h2>Interaction model</h2>
 * Two ways to move, both available at all times (like chess.com):
 * <ul>
 *   <li><b>Click-click</b>: click a piece to select it (legal destinations are
 *       highlighted), click a destination to move. Clicking another own piece
 *       re-selects; clicking elsewhere clears.</li>
 *   <li><b>Drag and drop</b>: press on a piece, drag it (it follows the
 *       cursor) and release on a destination. Releasing back on the origin
 *       keeps the piece selected so a click-click move can still finish it;
 *       releasing anywhere else that is not a destination snaps the piece
 *       back and clears the selection.</li>
 * </ul>
 *
 * <h2>Premoves</h2>
 * While the opponent (AI) is thinking, the human may pre-enter replies:
 * select an own piece and pick any square the piece could reach on an empty
 * board (blockers ignored — the position will change before the move is
 * played). Several premoves can be queued; each later one is entered on the
 * board as it will look once the earlier ones are played (the pieces are
 * painted there, the squares highlighted in red). When the human's turn
 * arrives, {@link GamePanel} calls {@link #consumePremove()}: if a legal
 * move matches the first queued pair it is played (promotion defaults to a
 * queen) and the rest waits for the next turn; otherwise the whole queue is
 * silently dropped. While waiting, clicking anything that is neither an own
 * piece nor a destination clears the queue; a right-click clears it too.
 *
 * <h2>Annotations</h2>
 * Right-click a square to highlight it, right-drag between two squares to
 * draw an arrow; doing the same again removes it. Any left-click or move
 * clears all annotations. Available in every mode, even when the board is
 * not interactive.
 *
 * <h2>Promotion</h2>
 * Moving a pawn to the last rank opens an on-board strip of four pieces
 * (queen, knight, rook, bishop) starting on the promotion square and
 * running towards the middle of the board. Clicking a piece plays that
 * promotion; clicking anywhere else cancels the move.
 *
 * When flipped (initially: human plays Black; toggleable at any time via
 * {@link #setFlipped(boolean)}) the board uses the visual mapping
 * {@code displayIndex = 63 - square} (180° rotation, a-file on the right —
 * the standard flipped orientation).
 */
public final class BoardPanel extends JPanel {

    private static final Color LIGHT_SQ = new Color(0xF0, 0xD9, 0xB5);
    private static final Color DARK_SQ = new Color(0xB5, 0x88, 0x63);
    private static final Color SELECTED = new Color(0x3A, 0x7B, 0xD5, 140);
    private static final Color LAST_MOVE = new Color(0xF7, 0xEC, 0x5D, 110);
    private static final Color CHECK = new Color(0xD9, 0x2B, 0x2B, 150);
    private static final Color PREMOVE = new Color(0xF0, 0x6E, 0x5F, 135);
    private static final Color HOVER = new Color(0xFF, 0xFF, 0xFF, 170);
    private static final Color TARGET_DOT = new Color(0, 0, 0, 70);

    /** Pixels the cursor must travel before a press turns into a drag. */
    private static final int DRAG_THRESHOLD_PX = 4;

    /** What a press on the board means right now. */
    private enum InputMode { NONE, MOVE, PREMOVE }

    /** A candidate destination of the current selection. */
    private record Target(int square, boolean capture) { }

    private final GameSession session;
    private final PieceRenderer renderer;
    private final Consumer<Move> moveConsumer;
    /** Engine color of the human player, or -1 when nobody can premove (AI vs AI). */
    private final int humanColor;
    private boolean flipped;

    private boolean interactionEnabled = false;

    // ---- selection (board coordinates) ----
    private int selectedSquare = -1;
    private final List<Target> targets = new ArrayList<>();

    // ---- drag ----
    private int dragSquare = -1;      // square whose piece was pressed, or -1
    private boolean dragging = false; // true once the cursor left the press point
    private Point pressPoint, dragPoint;

    // ---- premoves: a queue of {from, to}, played one per turn ----
    private final List<int[]> premoves = new ArrayList<>();

    // ---- annotations (right-click) ----
    private static final Color MARK = new Color(0xEB, 0x61, 0x50, 170);
    private static final Color ARROW = new Color(0x15, 0x78, 0x1B, 200);
    private final java.util.Set<Integer> markedSquares = new java.util.LinkedHashSet<>();
    private final List<int[]> arrows = new ArrayList<>();   // {from, to}
    private int annotationFrom = -1;                        // square of the right-button press

    // ---- promotion picker ----
    /** Order of the choices in the on-board strip, top to bottom from the promotion square. */
    private static final int[] PROMOTION_ORDER = {QUEEN, KNIGHT, ROOK, BISHOP};
    private static final Color PICKER_DIM = new Color(0, 0, 0, 90);
    private static final Color PICKER_BG = new Color(0xFF, 0xFF, 0xFF, 235);
    private static final Color PICKER_HOVER = new Color(0xF0, 0x6E, 0x5F, 200);
    private List<Move> promotionChoices;    // pending promotion moves (same from/to), or null
    private boolean promotionDropped;       // entered by drag (no animation) or by click
    private int promotionHover = -1;        // hovered strip cell, or -1

    // ---- animation ----
    /** Duration of a piece slide; GamePanel waits this long before playing a premove. */
    public static final int ANIMATION_MS = 180;
    private Move anim;                 // move being animated (already applied to the board)
    private long animStartNanos;
    private final Timer animTimer = new Timer(15, e -> onAnimationTick());

    public BoardPanel(GameSession session, boolean flipped, int humanColor, Consumer<Move> moveConsumer) {
        this.session = session;
        this.flipped = flipped;
        this.humanColor = humanColor;
        this.moveConsumer = moveConsumer;
        this.renderer = UnicodePieceRenderer.createBest();
        setPreferredSize(new Dimension(560, 560));
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { onPress(e); }
            @Override public void mouseDragged(MouseEvent e)  { onDrag(e); }
            @Override public void mouseReleased(MouseEvent e) { onRelease(e); }
            @Override public void mouseMoved(MouseEvent e)    { updateCursor(e); }
            @Override public void mouseExited(MouseEvent e)   { if (dragSquare < 0) setCursor(Cursor.getDefaultCursor()); }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** Changes the visual orientation. Selection stays valid: it is stored in
     *  board coordinates, only the pixel mapping changes. */
    public void setFlipped(boolean flipped) {
        this.flipped = flipped;
        repaint();
    }

    public boolean isFlipped() { return flipped; }

    /**
     * Enables/disables live moves. Disabling clears the selection (the
     * human's turn is over). Enabling re-derives the targets of a selection
     * that was started as a premove: the same piece can now move for real.
     */
    public void setInteractionEnabled(boolean enabled) {
        interactionEnabled = enabled;
        if (!enabled) clearSelection();
        else if (selectedSquare >= 0) select(selectedSquare, mode());
        repaint();
    }

    public void clearSelection() {
        selectedSquare = -1;
        targets.clear();
        dragSquare = -1;
        dragging = false;
        pressPoint = dragPoint = null;
        promotionChoices = null;
        promotionHover = -1;
    }

    /** True while the promotion strip is waiting for a choice. */
    public boolean isChoosingPromotion() { return promotionChoices != null; }

    // ---- annotation API ----

    /** Number of user marks currently drawn (highlighted squares + arrows). */
    public int annotationCount() { return markedSquares.size() + arrows.size(); }

    // ---- game-over banner ----

    private static final Color BANNER_DIM   = new Color(0, 0, 0, 70);
    private static final Color BANNER_BG    = new Color(0x2B, 0x25, 0x20, 232);
    private static final Color BANNER_EDGE  = new Color(0xF6, 0xC4, 0x53);
    private static final Color BANNER_TITLE = new Color(0xF8, 0xF3, 0xEA);
    private static final Color BANNER_TEXT  = new Color(0xD6, 0xC8, 0xB2);
    private String bannerTitle, bannerSubtitle;

    /**
     * Shows a large centred announcement over the board (checkmate, draw,
     * time out...). It is painted once the current slide has finished and
     * stays until {@link #clearBanner()} or a click on the board.
     */
    public void showBanner(String title, String subtitle) {
        bannerTitle = title;
        bannerSubtitle = subtitle;
        repaint();
    }

    public void clearBanner() {
        if (bannerTitle == null) return;
        bannerTitle = null;
        bannerSubtitle = null;
        repaint();
    }

    /** The banner title currently shown, or null. */
    public String bannerTitle() { return bannerTitle; }

    public void clearAnnotations() {
        markedSquares.clear();
        arrows.clear();
        annotationFrom = -1;
        repaint();
    }

    private void toggleMark(int sq) {
        if (!markedSquares.remove(sq)) markedSquares.add(sq);
    }

    private void toggleArrow(int from, int to) {
        for (int i = 0; i < arrows.size(); i++) {
            if (arrows.get(i)[0] == from && arrows.get(i)[1] == to) { arrows.remove(i); return; }
        }
        arrows.add(new int[]{from, to});
    }

    // ---- animation API (used by GamePanel) ----

    /**
     * Slides the piece of {@code m} from its origin to its destination. The
     * move must already be applied to the session: the board is painted in
     * its new state with the moving piece (and the castling rook) drawn at
     * an interpolated position instead of on its square.
     */
    public void animate(Move m) {
        anim = m;
        animStartNanos = System.nanoTime();
        animTimer.start();
        clearAnnotations();   // marks refer to the previous position
        repaint();
    }

    public boolean isAnimating() { return anim != null; }

    public void stopAnimation() {
        anim = null;
        animTimer.stop();
        repaint();
    }

    private void onAnimationTick() {
        if (animationProgress() >= 1.0) stopAnimation();
        else repaint();
    }

    /** 0..1, eased out so the piece decelerates into its square. */
    private double animationProgress() {
        double t = (System.nanoTime() - animStartNanos) / (ANIMATION_MS * 1_000_000.0);
        t = Math.max(0.0, Math.min(1.0, t));
        return 1.0 - (1.0 - t) * (1.0 - t);
    }

    // ---- premove API (used by GamePanel) ----

    public boolean hasPremove() { return !premoves.isEmpty(); }

    public int premoveCount() { return premoves.size(); }

    /** The queue as {@code e2e4 g1f3 ...}; null when empty. */
    public String premoveText() {
        if (premoves.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int[] pm : premoves) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Move.squareName(pm[0])).append(Move.squareName(pm[1]));
        }
        return sb.toString();
    }

    /** Drops every queued premove. */
    public void cancelPremove() {
        premoves.clear();
        repaint();
    }

    /**
     * Removes the FIRST queued premove and returns the legal move it denotes
     * in the CURRENT position, or null if it has none (piece captured,
     * square no longer reachable, would leave the king in check...) — in
     * which case the rest of the queue is dropped too, since it was planned
     * on top of that move. Among promotion candidates the queen is chosen.
     * Never returns an illegal move.
     */
    public Move consumePremove() {
        if (premoves.isEmpty()) return null;
        int[] pm = premoves.remove(0);
        Move best = null;
        for (Move m : session.legalMoves()) {
            if (m.from() != pm[0] || m.to() != pm[1]) continue;
            if (best == null || m.promotion() == QUEEN) best = m;
        }
        if (best == null) premoves.clear();
        repaint();
        return best;
    }

    /**
     * The board as it will look once the queued premoves are played, applied
     * naively (no legality, captures overwrite, castling hops the rook,
     * pawns reaching the last rank become queens). Later premoves are
     * selected and validated against this picture; a premove whose piece is
     * gone is skipped here and will fail on consumption.
     */
    private int[] virtualSquares() {
        int[] sq = new int[64];
        for (int i = 0; i < 64; i++) sq[i] = session.board().pieceAt(i);
        for (int[] pm : premoves) {
            int p = sq[pm[0]];
            if (p == EMPTY || colorOf(p) != humanColor) continue;
            sq[pm[0]] = EMPTY;
            int type = typeOf(p);
            if (type == PAWN && ((pm[1] >>> 3) == 0 || (pm[1] >>> 3) == 7)) p = Pieces.make(QUEEN, humanColor);
            sq[pm[1]] = p;
            if (type == KING && Math.abs(pm[1] - pm[0]) == 2) {
                int rookFrom = pm[1] > pm[0] ? pm[0] + 3 : pm[0] - 4;
                int rookTo = pm[1] > pm[0] ? pm[0] + 1 : pm[0] - 1;
                sq[rookTo] = sq[rookFrom];
                sq[rookFrom] = EMPTY;
            }
        }
        return sq;
    }

    // ---- input ----

    private InputMode mode() {
        if (session.result().isOver()) return InputMode.NONE;
        if (interactionEnabled) return InputMode.MOVE;
        if (humanColor >= 0 && session.sideToMove() != humanColor) return InputMode.PREMOVE;
        return InputMode.NONE;
    }

    /** The color whose pieces the user may pick up in the given mode. */
    private int ownColor(InputMode mode) {
        return mode == InputMode.MOVE ? session.sideToMove() : humanColor;
    }

    private boolean isOwnPiece(int sq, InputMode mode) {
        int p = mode == InputMode.PREMOVE ? virtualSquares()[sq] : session.board().pieceAt(sq);
        return p != EMPTY && colorOf(p) == ownColor(mode);
    }

    private Target targetAt(int sq) {
        for (Target t : targets) if (t.square() == sq) return t;
        return null;
    }

    private void onPress(MouseEvent e) {
        InputMode mode = mode();
        if (bannerTitle != null) {
            // Any click on the announcement puts it away so the final position can be studied.
            clearBanner();
            return;
        }
        if (SwingUtilities.isRightMouseButton(e)) {
            // Right button: cancel whatever is pending and start an annotation
            // (a mark on release over the same square, an arrow otherwise).
            // Allowed even when the board is not interactive (game over, AI vs AI).
            cancelPremove();
            clearSelection();
            annotationFrom = squareAt(e.getX(), e.getY());
            repaint();
            return;
        }
        if (SwingUtilities.isLeftMouseButton(e) && annotationCount() > 0) clearAnnotations();
        if (mode == InputMode.NONE) return;
        if (promotionChoices != null) {
            // The strip is modal for the board: a cell picks, anything else cancels.
            int cell = SwingUtilities.isLeftMouseButton(e) ? promotionCellAt(e.getX(), e.getY()) : -1;
            List<Move> choices = promotionChoices;
            boolean dropped = promotionDropped;
            clearSelection();
            repaint();
            if (cell >= 0) submit(promotionMove(choices, PROMOTION_ORDER[cell]), dropped);
            return;
        }
        if (!SwingUtilities.isLeftMouseButton(e)) return;
        int sq = squareAt(e.getX(), e.getY());
        if (sq < 0) return;

        // On our own turn any left-click drops premoves that are still queued
        // (the click is a manual move); while waiting, a click on an own piece
        // or a destination extends the queue and a click elsewhere clears it.
        if (hasPremove() && mode == InputMode.MOVE) cancelPremove();

        // A press on a highlighted destination completes the move (click-click).
        if (selectedSquare >= 0 && targetAt(sq) != null) {
            commitMove(selectedSquare, sq, mode, false);
            return;
        }

        if (hasPremove() && !isOwnPiece(sq, mode)) cancelPremove();

        // Otherwise (re)select an own piece and arm a potential drag.
        if (isOwnPiece(sq, mode)) {
            select(sq, mode);
            dragSquare = sq;
            dragging = false;
            pressPoint = dragPoint = e.getPoint();
        } else {
            clearSelection();
        }
        repaint();
    }

    private void onDrag(MouseEvent e) {
        if (dragSquare < 0) return;
        dragPoint = e.getPoint();
        if (!dragging && dragPoint.distance(pressPoint) >= DRAG_THRESHOLD_PX) {
            dragging = true;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        if (dragging) repaint();
    }

    private void onRelease(MouseEvent e) {
        if (annotationFrom >= 0 && e.getButton() == MouseEvent.BUTTON3) {
            int sq = squareAt(e.getX(), e.getY());
            if (sq >= 0) {
                if (sq == annotationFrom) toggleMark(sq);
                else toggleArrow(annotationFrom, sq);
            }
            annotationFrom = -1;
            repaint();
            return;
        }
        if (dragSquare < 0) return;
        int from = dragSquare;
        boolean wasDragging = dragging;
        dragSquare = -1;
        dragging = false;
        pressPoint = dragPoint = null;
        updateCursor(e);

        // A press without movement is a plain click: the selection made on
        // press stands and a second click will finish the move.
        if (!wasDragging) { repaint(); return; }

        InputMode mode = mode();
        int sq = squareAt(e.getX(), e.getY());
        // The mode may have changed mid-drag (the AI replied while the piece
        // was in the air): setInteractionEnabled has already re-derived the
        // targets, or cleared the selection if the piece is gone.
        if (mode == InputMode.NONE || selectedSquare != from) {
            clearSelection();
        } else if (sq == from) {
            // Dropped back where it came from: stay selected (click-click continues).
        } else if (sq >= 0 && targetAt(sq) != null) {
            commitMove(from, sq, mode, true);
            return;
        } else {
            clearSelection();   // snap back
        }
        repaint();
    }

    private void updateCursor(MouseEvent e) {
        InputMode mode = mode();
        if (promotionChoices != null) {
            int cell = promotionCellAt(e.getX(), e.getY());
            if (cell != promotionHover) { promotionHover = cell; repaint(); }
            setCursor(cell >= 0 ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            return;
        }
        int sq = squareAt(e.getX(), e.getY());
        boolean grabbable = mode != InputMode.NONE && sq >= 0 && isOwnPiece(sq, mode);
        setCursor(grabbable ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
    }

    private void select(int sq, InputMode mode) {
        targets.clear();
        if (mode == InputMode.NONE || !isOwnPiece(sq, mode)) {
            selectedSquare = -1;
            return;
        }
        selectedSquare = sq;
        if (mode == InputMode.MOVE) {
            for (Move m : session.legalMoves()) {
                if (m.from() == sq && targetAt(m.to()) == null) targets.add(new Target(m.to(), m.isCapture()));
            }
        } else {
            targets.addAll(premoveTargets(sq));
        }
    }

    /**
     * Records a premove, or resolves and submits a real move. In MOVE mode
     * the (from, to) pair maps to exactly one legal move except for
     * promotions, where the on-board strip asks for the piece first. A move
     * entered by click-click is animated; a dropped piece is already on its
     * square.
     */
    private void commitMove(int from, int to, InputMode mode, boolean dropped) {
        clearSelection();
        if (mode == InputMode.PREMOVE) {
            premoves.add(new int[]{from, to});
            repaint();
            return;
        }
        List<Move> matching = new ArrayList<>();
        for (Move m : session.legalMoves()) if (m.from() == from && m.to() == to) matching.add(m);
        if (matching.size() > 1) {
            // Promotion: keep the candidates, show the strip, wait for a click.
            promotionChoices = matching;
            promotionDropped = dropped;
            promotionHover = -1;
            repaint();
            return;
        }
        repaint();
        if (!matching.isEmpty()) submit(matching.get(0), dropped);
    }

    private void submit(Move chosen, boolean dropped) {
        clearAnnotations();
        moveConsumer.accept(chosen);
        // Animate only if the consumer actually played it (it may refuse).
        if (!dropped && chosen.equals(session.lastMove())) animate(chosen);
    }

    private static Move promotionMove(List<Move> candidates, int pieceType) {
        for (Move m : candidates) if (m.promotion() == pieceType) return m;
        throw new IllegalStateException("promotion candidates incomplete");
    }

    // ---- promotion strip geometry ----

    /** The strip runs from the promotion square towards the middle of the board. */
    private int promotionDirection() {
        return yOf(promotionChoices.get(0).to()) == 0 ? 1 : -1;
    }

    private int promotionCellAt(int px, int py) {
        int s = squareSize();
        if (s == 0) return -1;
        int to = promotionChoices.get(0).to();
        int x = xOf(to), y0 = yOf(to), dir = promotionDirection();
        if (px < x || px >= x + s) return -1;
        for (int i = 0; i < PROMOTION_ORDER.length; i++) {
            int y = y0 + dir * i * s;
            if (py >= y && py < y + s) return i;
        }
        return -1;
    }

    private void paintPromotionStrip(Graphics2D g, int s) {
        g.setColor(PICKER_DIM);
        g.fillRect(0, 0, 8 * s, 8 * s);
        int to = promotionChoices.get(0).to();
        int color = colorOf(promotionChoices.get(0).piece());
        int x = xOf(to), y0 = yOf(to), dir = promotionDirection();
        int top = dir > 0 ? y0 : y0 - 3 * s;
        g.setColor(new Color(0, 0, 0, 80));
        g.fillRoundRect(x + 3, top + 4, s, 4 * s, s / 4, s / 4);
        g.setColor(PICKER_BG);
        g.fillRoundRect(x, top, s, 4 * s, s / 4, s / 4);
        for (int i = 0; i < PROMOTION_ORDER.length; i++) {
            int y = y0 + dir * i * s;
            if (i == promotionHover) {
                g.setColor(PICKER_HOVER);
                g.fillRoundRect(x + 2, y + 2, s - 4, s - 4, s / 5, s / 5);
            }
            renderer.draw(g, Pieces.make(PROMOTION_ORDER[i], color), x, y, s);
        }
    }

    // ---- premove geometry ----

    private static final int[][] KNIGHT_DELTAS = {{1,2},{2,1},{2,-1},{1,-2},{-1,-2},{-2,-1},{-2,1},{-1,2}};
    private static final int[][] KING_DELTAS   = {{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1},{0,-1},{1,-1}};
    private static final int[][] BISHOP_DIRS   = {{1,1},{1,-1},{-1,1},{-1,-1}};
    private static final int[][] ROOK_DIRS     = {{1,0},{-1,0},{0,1},{0,-1}};

    /**
     * Squares the piece on {@code from} could reach on an otherwise empty
     * board — the chess.com premove rule. Blockers are ignored (they may
     * move away), pawn captures are always offered (something may land
     * there), castling is offered while the right exists. Squares holding
     * an own piece are excluded. Legality is re-checked when the premove is
     * consumed, so an optimistic set here is harmless.
     */
    private List<Target> premoveTargets(int from) {
        Board b = session.board();
        int[] v = virtualSquares();
        int p = v[from];
        int c = colorOf(p);
        int r = from >>> 3, f = from & 7;
        List<Integer> squares = new ArrayList<>();
        switch (typeOf(p)) {
            case PAWN -> {
                int d = c == WHITE ? 1 : -1;
                addSquare(squares, r + d, f);
                if (r == (c == WHITE ? 1 : 6)) addSquare(squares, r + 2 * d, f);
                addSquare(squares, r + d, f - 1);
                addSquare(squares, r + d, f + 1);
            }
            case KNIGHT -> { for (int[] k : KNIGHT_DELTAS) addSquare(squares, r + k[0], f + k[1]); }
            case BISHOP -> addRays(squares, r, f, BISHOP_DIRS);
            case ROOK   -> addRays(squares, r, f, ROOK_DIRS);
            case QUEEN  -> { addRays(squares, r, f, BISHOP_DIRS); addRays(squares, r, f, ROOK_DIRS); }
            case KING -> {
                for (int[] k : KING_DELTAS) addSquare(squares, r + k[0], f + k[1]);
                int rights = b.castlingRights();
                int home = c == WHITE ? 4 : 60;
                if (from == home) {
                    if ((rights & (c == WHITE ? Board.WK_CASTLE : Board.BK_CASTLE)) != 0) squares.add(home + 2);
                    if ((rights & (c == WHITE ? Board.WQ_CASTLE : Board.BQ_CASTLE)) != 0) squares.add(home - 2);
                }
            }
            default -> { }
        }
        List<Target> out = new ArrayList<>();
        for (int sq : squares) {
            int occupant = v[sq];
            if (occupant != EMPTY && colorOf(occupant) == c) continue;
            out.add(new Target(sq, occupant != EMPTY));
        }
        return out;
    }

    private static void addSquare(List<Integer> out, int r, int f) {
        if (r >= 0 && r < 8 && f >= 0 && f < 8) out.add(r * 8 + f);
    }

    private static void addRays(List<Integer> out, int r, int f, int[][] dirs) {
        for (int[] d : dirs) {
            int nr = r + d[0], nf = f + d[1];
            while (nr >= 0 && nr < 8 && nf >= 0 && nf < 8) {
                out.add(nr * 8 + nf);
                nr += d[0];
                nf += d[1];
            }
        }
    }

    // ---- geometry ----

    private int squareSize() { return Math.min(getWidth(), getHeight()) / 8; }

    /** Board square under a pixel, or -1. Applies the flip mapping. */
    private int squareAt(int px, int py) {
        int s = squareSize();
        if (s == 0 || px < 0 || py < 0) return -1;
        int col = px / s, rowFromTop = py / s;
        if (col > 7 || rowFromTop > 7) return -1;
        int display = (7 - rowFromTop) * 8 + col;   // display index, white-at-bottom
        return flipped ? 63 - display : display;
    }

    private int xOf(int sq) {
        int display = flipped ? 63 - sq : sq;
        return (display & 7) * squareSize();
    }

    private int yOf(int sq) {
        int display = flipped ? 63 - sq : sq;
        return (7 - (display >>> 3)) * squareSize();
    }

    // ---- painting ----

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int s = squareSize();
        // With premoves queued the pieces are painted where they will stand.
        int[] pieces = hasPremove() ? virtualSquares() : null;

        for (int sq = 0; sq < 64; sq++) {
            boolean light = (((sq >>> 3) + (sq & 7)) & 1) == 1;
            g.setColor(light ? LIGHT_SQ : DARK_SQ);
            g.fillRect(xOf(sq), yOf(sq), s, s);
        }

        Move last = session.lastMove();
        if (last != null) {
            g.setColor(LAST_MOVE);
            g.fillRect(xOf(last.from()), yOf(last.from()), s, s);
            g.fillRect(xOf(last.to()), yOf(last.to()), s, s);
        }

        if (session.inCheckNow()) {
            g.setColor(CHECK);
            int ksq = session.board().kingSquare(session.sideToMove());
            g.fillRect(xOf(ksq), yOf(ksq), s, s);
        }

        g.setColor(PREMOVE);
        for (int[] pm : premoves) {
            g.fillRect(xOf(pm[0]), yOf(pm[0]), s, s);
            g.fillRect(xOf(pm[1]), yOf(pm[1]), s, s);
        }

        if (selectedSquare >= 0) {
            g.setColor(SELECTED);
            g.fillRect(xOf(selectedSquare), yOf(selectedSquare), s, s);
        }

        g.setColor(MARK);
        for (int sq : markedSquares) g.fillRect(xOf(sq), yOf(sq), s, s);

        int hover = dragging ? squareAt(dragPoint.x, dragPoint.y) : -1;
        if (hover >= 0) {
            g.setColor(HOVER);
            g.setStroke(new BasicStroke(Math.max(2f, s / 14f)));
            int inset = Math.max(1, s / 28);
            g.drawRect(xOf(hover) + inset, yOf(hover) + inset, s - 2 * inset, s - 2 * inset);
        }

        drawCoordinates(g, s);

        // Squares whose piece is currently sliding (moving piece + castling rook).
        int animTo = anim != null ? anim.to() : -1;
        int rookTo = anim != null && anim.isCastle() ? (anim.to() > anim.from() ? anim.from() + 1 : anim.from() - 1) : -1;

        for (int sq = 0; sq < 64; sq++) {
            int p = pieces != null ? pieces[sq] : session.board().pieceAt(sq);
            if (p == EMPTY) continue;
            if (dragging && sq == dragSquare) continue;                              // in the air
            if (sq == animTo || sq == rookTo) continue;                              // sliding below
            renderer.draw(g, p, xOf(sq), yOf(sq), s);
        }
        if (anim != null) {
            double t = animationProgress();
            drawSliding(g, anim.from(), anim.to(), t, s);
            if (rookTo >= 0) drawSliding(g, anim.to() > anim.from() ? anim.from() + 3 : anim.from() - 4, rookTo, t, s);
        }

        for (int[] a : arrows) drawArrow(g, a[0], a[1], s);

        // Destination markers drawn over the pieces: dot on empty squares,
        // ring on captures.
        for (Target t : targets) {
            int x = xOf(t.square()), y = yOf(t.square());
            g.setColor(TARGET_DOT);
            if (t.capture()) {
                g.setStroke(new BasicStroke(s / 12f));
                g.drawOval(x + s / 12, y + s / 12, s - s / 6, s - s / 6);
            } else {
                g.fillOval(x + s * 3 / 8, y + s * 3 / 8, s / 4, s / 4);
            }
        }

        // The lifted piece rides under the cursor, on top of everything.
        if (dragging) {
            int p = pieces != null ? pieces[dragSquare] : session.board().pieceAt(dragSquare);
            if (p != EMPTY) {
                int size = s + s / 8;
                renderer.draw(g, p, dragPoint.x - size / 2, dragPoint.y - size / 2, size);
            }
        }

        if (promotionChoices != null) paintPromotionStrip(g, s);
        if (bannerTitle != null && anim == null) paintBanner(g, s);
    }

    /** Dimmed board, rounded dark box in the middle, big title and a line below. Fits any board size. */
    private void paintBanner(Graphics2D g, int s) {
        int board = 8 * s;
        g.setColor(BANNER_DIM);
        g.fillRect(0, 0, board, board);

        int maxTextW = board - s;
        Font titleFont = new Font("Segoe UI", Font.BOLD, Math.max(16, s * 3 / 4));
        Font subFont = new Font("Segoe UI", Font.PLAIN, Math.max(11, s * 3 / 10));
        FontMetrics tm = g.getFontMetrics(titleFont);
        while (tm.stringWidth(bannerTitle) > maxTextW && titleFont.getSize() > 12) {   // long titles shrink to fit
            titleFont = titleFont.deriveFont((float) titleFont.getSize() - 2);
            tm = g.getFontMetrics(titleFont);
        }
        FontMetrics sm = g.getFontMetrics(subFont);
        String sub = bannerSubtitle == null ? "" : bannerSubtitle;
        int textW = Math.max(tm.stringWidth(bannerTitle), sm.stringWidth(sub));
        int padX = s / 2, padY = s / 4, gap = s / 10;
        int boxW = Math.min(board - s / 2, textW + 2 * padX);
        int boxH = tm.getHeight() + (sub.isEmpty() ? 0 : sm.getHeight() + gap) + 2 * padY;
        int x = (board - boxW) / 2, y = (board - boxH) / 2, arc = s / 2;

        g.setColor(BANNER_BG);
        g.fillRoundRect(x, y, boxW, boxH, arc, arc);
        g.setColor(BANNER_EDGE);
        g.setStroke(new BasicStroke(Math.max(2f, s / 24f)));
        g.drawRoundRect(x, y, boxW, boxH, arc, arc);

        g.setFont(titleFont);
        g.setColor(BANNER_TITLE);
        g.drawString(bannerTitle, (board - tm.stringWidth(bannerTitle)) / 2, y + padY + tm.getAscent());
        if (!sub.isEmpty()) {
            g.setFont(subFont);
            g.setColor(BANNER_TEXT);
            g.drawString(sub, (board - sm.stringWidth(sub)) / 2, y + padY + tm.getHeight() + gap + sm.getAscent());
        }
    }

    /** Thick translucent arrow between square centres, shortened so the head sits inside the target. */
    private void drawArrow(Graphics2D g, int from, int to, int s) {
        double x1 = xOf(from) + s / 2.0, y1 = yOf(from) + s / 2.0;
        double x2 = xOf(to) + s / 2.0, y2 = yOf(to) + s / 2.0;
        double dx = x2 - x1, dy = y2 - y1, len = Math.hypot(dx, dy);
        if (len == 0) return;
        double ux = dx / len, uy = dy / len;
        double head = s * 0.42, width = s / 7.0;
        // Start a little away from the centre so the tail does not cover the piece.
        double sx = x1 + ux * s * 0.3, sy = y1 + uy * s * 0.3;
        double bx = x2 - ux * head, by = y2 - uy * head;   // base of the head
        g.setColor(ARROW);
        g.setStroke(new BasicStroke((float) width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        g.draw(new java.awt.geom.Line2D.Double(sx, sy, bx, by));
        java.awt.geom.Path2D.Double tip = new java.awt.geom.Path2D.Double();
        double hw = head * 0.6;   // half-width of the head
        tip.moveTo(x2, y2);
        tip.lineTo(bx - uy * hw, by + ux * hw);
        tip.lineTo(bx + uy * hw, by - ux * hw);
        tip.closePath();
        g.fill(tip);
    }

    /** Draws the piece now standing on {@code to} at a point {@code t} of the way from {@code from}. */
    private void drawSliding(Graphics2D g, int from, int to, double t, int s) {
        int p = session.board().pieceAt(to);
        if (p == EMPTY) return;   // e.g. taken back mid-animation
        int x = (int) Math.round(xOf(from) + (xOf(to) - xOf(from)) * t);
        int y = (int) Math.round(yOf(from) + (yOf(to) - yOf(from)) * t);
        renderer.draw(g, p, x, y, s);
    }

    private void drawCoordinates(Graphics2D g, int s) {
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(9, s / 6)));
        for (int i = 0; i < 8; i++) {
            int fileSq = flipped ? 56 + (7 - i) : i;          // squares on the bottom row
            int rankSq = flipped ? (7 - i) * 8 + 7 : i * 8;   // squares on the left column
            boolean lightF = (((fileSq >>> 3) + (fileSq & 7)) & 1) == 1;
            boolean lightR = (((rankSq >>> 3) + (rankSq & 7)) & 1) == 1;
            g.setColor(lightF ? DARK_SQ : LIGHT_SQ);
            g.drawString(String.valueOf((char) ('a' + (fileSq & 7))),
                    i * s + s - s / 5, 8 * s - s / 12);
            g.setColor(lightR ? DARK_SQ : LIGHT_SQ);
            g.drawString(String.valueOf((char) ('1' + (rankSq >>> 3))),
                    s / 16, (7 - i) * s + s / 4);
        }
    }
}

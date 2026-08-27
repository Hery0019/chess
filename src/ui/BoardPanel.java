package ui;

import engine.Board;
import engine.Move;
import engine.Pieces;
import game.GameSession;

import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
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
 * While the opponent (AI) is thinking, the human may pre-enter their reply:
 * select an own piece and pick any square the piece could reach on an empty
 * board (blockers ignored — the position will change before the move is
 * played). The premove is shown as a red highlight with the piece ghosted on
 * its destination. When the human's turn arrives, {@link GamePanel} calls
 * {@link #consumePremove()}: if a legal move matches the from/to pair it is
 * played instantly (promotion defaults to a queen), otherwise the premove is
 * silently dropped. One premove is held at a time; any click on the board
 * cancels it (a right-click cancels without selecting anything).
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

    // ---- premove ----
    private int premoveFrom = -1, premoveTo = -1;

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
    }

    // ---- premove API (used by GamePanel) ----

    public boolean hasPremove() { return premoveFrom >= 0; }

    /** e.g. {@code e2e4}; null when no premove is held. */
    public String premoveText() {
        return hasPremove() ? Move.squareName(premoveFrom) + Move.squareName(premoveTo) : null;
    }

    public void cancelPremove() {
        premoveFrom = premoveTo = -1;
        repaint();
    }

    /**
     * Removes the held premove and returns the legal move it denotes in the
     * CURRENT position, or null if it has none (piece captured, square no
     * longer reachable, would leave the king in check...). Among promotion
     * candidates the queen is chosen. Never returns an illegal move.
     */
    public Move consumePremove() {
        int from = premoveFrom, to = premoveTo;
        premoveFrom = premoveTo = -1;
        if (from < 0) return null;
        Move best = null;
        for (Move m : session.legalMoves()) {
            if (m.from() != from || m.to() != to) continue;
            if (best == null || m.promotion() == QUEEN) best = m;
        }
        repaint();
        return best;
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
        int p = session.board().pieceAt(sq);
        return p != EMPTY && colorOf(p) == ownColor(mode);
    }

    private Target targetAt(int sq) {
        for (Target t : targets) if (t.square() == sq) return t;
        return null;
    }

    private void onPress(MouseEvent e) {
        InputMode mode = mode();
        if (mode == InputMode.NONE) return;
        if (SwingUtilities.isRightMouseButton(e)) {
            cancelPremove();
            clearSelection();
            repaint();
            return;
        }
        if (!SwingUtilities.isLeftMouseButton(e)) return;
        int sq = squareAt(e.getX(), e.getY());
        if (sq < 0) return;

        // chess.com semantics: any left-click on the board cancels a pending
        // premove, and the click then proceeds normally (so clicking a piece
        // both cancels and re-selects in one go).
        if (hasPremove()) cancelPremove();

        // A press on a highlighted destination completes the move (click-click).
        if (selectedSquare >= 0 && targetAt(sq) != null) {
            commitMove(selectedSquare, sq, mode);
            return;
        }

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
            commitMove(from, sq, mode);
            return;
        } else {
            clearSelection();   // snap back
        }
        repaint();
    }

    private void updateCursor(MouseEvent e) {
        InputMode mode = mode();
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
     * promotions, where a modal dialog picks the piece.
     */
    private void commitMove(int from, int to, InputMode mode) {
        clearSelection();
        if (mode == InputMode.PREMOVE) {
            premoveFrom = from;
            premoveTo = to;
            repaint();
            return;
        }
        List<Move> matching = new ArrayList<>();
        for (Move m : session.legalMoves()) if (m.from() == from && m.to() == to) matching.add(m);
        repaint();
        if (matching.isEmpty()) return;   // stale selection; nothing to do
        Move chosen = matching.size() == 1 ? matching.get(0) : choosePromotion(matching);
        if (chosen != null) moveConsumer.accept(chosen);
    }

    /**
     * Promotion choice (approved decision #6): the four candidate moves for
     * the clicked destination differ only in promotion piece; a modal dialog
     * picks among them. Cancelling aborts the move entirely.
     */
    private Move choosePromotion(List<Move> candidates) {
        String[] options = {"Queen", "Rook", "Bishop", "Knight"};
        int[] types = {QUEEN, ROOK, BISHOP, KNIGHT};
        int choice = JOptionPane.showOptionDialog(this, "Promote pawn to:", "Promotion",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (choice < 0) return null;
        for (Move m : candidates) if (m.promotion() == types[choice]) return m;
        throw new IllegalStateException("promotion candidates incomplete");
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
        int p = b.pieceAt(from);
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
            int occupant = b.pieceAt(sq);
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

    /** A premove is drawn only while the piece it refers to is still there. */
    private boolean premoveVisible() {
        if (!hasPremove()) return false;
        int p = session.board().pieceAt(premoveFrom);
        return p != EMPTY && colorOf(p) == humanColor;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int s = squareSize();
        boolean showPremove = premoveVisible();

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

        if (showPremove) {
            g.setColor(PREMOVE);
            g.fillRect(xOf(premoveFrom), yOf(premoveFrom), s, s);
            g.fillRect(xOf(premoveTo), yOf(premoveTo), s, s);
        }

        if (selectedSquare >= 0) {
            g.setColor(SELECTED);
            g.fillRect(xOf(selectedSquare), yOf(selectedSquare), s, s);
        }

        int hover = dragging ? squareAt(dragPoint.x, dragPoint.y) : -1;
        if (hover >= 0) {
            g.setColor(HOVER);
            g.setStroke(new BasicStroke(Math.max(2f, s / 14f)));
            int inset = Math.max(1, s / 28);
            g.drawRect(xOf(hover) + inset, yOf(hover) + inset, s - 2 * inset, s - 2 * inset);
        }

        drawCoordinates(g, s);

        for (int sq = 0; sq < 64; sq++) {
            int p = session.board().pieceAt(sq);
            if (p == EMPTY) continue;
            if (dragging && sq == dragSquare) continue;                              // in the air
            if (showPremove && (sq == premoveFrom || sq == premoveTo)) continue;    // ghosted below
            renderer.draw(g, p, xOf(sq), yOf(sq), s);
        }
        if (showPremove) {
            // Ghost: the premoved piece is shown on its destination (the
            // occupant there, if any, is hidden — it would be captured).
            renderer.draw(g, session.board().pieceAt(premoveFrom), xOf(premoveTo), yOf(premoveTo), s);
        }

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
            int p = session.board().pieceAt(dragSquare);
            if (p != EMPTY) {
                int size = s + s / 8;
                renderer.draw(g, p, dragPoint.x - size / 2, dragPoint.y - size / 2, size);
            }
        }
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

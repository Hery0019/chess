package ui;

import engine.Move;
import engine.Pieces;
import game.GameSession;

import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Custom-painted 8x8 board. Pure view + input: owns selection state and
 * pixel/square mapping, but every accepted move is delegated upward via the
 * {@code moveConsumer} — this panel never mutates the session itself.
 *
 * Interaction model (per spec): click a piece to select, legal destinations
 * are highlighted, click a destination to move. Clicking another own piece
 * re-selects; clicking elsewhere clears. When {@code interactionEnabled} is
 * false (AI thinking, AI-vs-AI, game over) clicks are ignored.
 *
 * When the human plays Black the board is flipped by the visual mapping
 * {@code displayIndex = 63 - square} (180° rotation, a-file on the right —
 * the standard flipped orientation).
 */
public final class BoardPanel extends JPanel {

    private static final Color LIGHT_SQ = new Color(0xF0, 0xD9, 0xB5);
    private static final Color DARK_SQ = new Color(0xB5, 0x88, 0x63);
    private static final Color SELECTED = new Color(0x3A, 0x7B, 0xD5, 140);
    private static final Color LAST_MOVE = new Color(0xF7, 0xEC, 0x5D, 110);
    private static final Color CHECK = new Color(0xD9, 0x2B, 0x2B, 150);
    private static final Color TARGET_DOT = new Color(0, 0, 0, 70);

    private final GameSession session;
    private final PieceRenderer renderer;
    private final Consumer<Move> moveConsumer;
    private final boolean flipped;

    private boolean interactionEnabled = false;
    private int selectedSquare = -1;
    private final List<Move> selectedMoves = new ArrayList<>();

    public BoardPanel(GameSession session, boolean flipped, Consumer<Move> moveConsumer) {
        this.session = session;
        this.flipped = flipped;
        this.moveConsumer = moveConsumer;
        this.renderer = UnicodePieceRenderer.createBest();
        setPreferredSize(new Dimension(560, 560));
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { handleClick(e); }
        });
    }

    public void setInteractionEnabled(boolean enabled) {
        interactionEnabled = enabled;
        if (!enabled) clearSelection();
        repaint();
    }

    public void clearSelection() {
        selectedSquare = -1;
        selectedMoves.clear();
    }

    // ---- input ----

    private void handleClick(MouseEvent e) {
        if (!interactionEnabled || session.result().isOver()) return;
        int sq = squareAt(e.getX(), e.getY());
        if (sq < 0) return;

        // A click on a highlighted destination completes the move.
        List<Move> matching = new ArrayList<>();
        for (Move m : selectedMoves) if (m.to() == sq) matching.add(m);
        if (!matching.isEmpty()) {
            Move chosen = matching.size() == 1 ? matching.get(0) : choosePromotion(matching);
            clearSelection();
            repaint();
            if (chosen != null) moveConsumer.accept(chosen);
            return;
        }

        // Otherwise (re)select if the square holds a piece of the side to move.
        int p = session.board().pieceAt(sq);
        if (p != Pieces.EMPTY && Pieces.colorOf(p) == session.sideToMove()) {
            selectedSquare = sq;
            selectedMoves.clear();
            for (Move m : session.legalMoves()) if (m.from() == sq) selectedMoves.add(m);
        } else {
            clearSelection();
        }
        repaint();
    }

    /**
     * Promotion choice (approved decision #6): the four candidate moves for
     * the clicked destination differ only in promotion piece; a modal dialog
     * picks among them. Cancelling aborts the move entirely.
     */
    private Move choosePromotion(List<Move> candidates) {
        String[] options = {"Queen", "Rook", "Bishop", "Knight"};
        int[] types = {Pieces.QUEEN, Pieces.ROOK, Pieces.BISHOP, Pieces.KNIGHT};
        int choice = JOptionPane.showOptionDialog(this, "Promote pawn to:", "Promotion",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (choice < 0) return null;
        for (Move m : candidates) if (m.promotion() == types[choice]) return m;
        throw new IllegalStateException("promotion candidates incomplete");
    }

    // ---- geometry ----

    private int squareSize() { return Math.min(getWidth(), getHeight()) / 8; }

    /** Board square under a pixel, or -1. Applies the flip mapping. */
    private int squareAt(int px, int py) {
        int s = squareSize();
        int col = px / s, rowFromTop = py / s;
        if (col < 0 || col > 7 || rowFromTop < 0 || rowFromTop > 7) return -1;
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

        if (selectedSquare >= 0) {
            g.setColor(SELECTED);
            g.fillRect(xOf(selectedSquare), yOf(selectedSquare), s, s);
        }

        drawCoordinates(g, s);

        for (int sq = 0; sq < 64; sq++) {
            int p = session.board().pieceAt(sq);
            if (p != Pieces.EMPTY) renderer.draw(g, p, xOf(sq), yOf(sq), s);
        }

        // Destination markers drawn over the pieces: dot on empty squares,
        // ring on captures.
        for (Move m : selectedMoves) {
            int x = xOf(m.to()), y = yOf(m.to());
            g.setColor(TARGET_DOT);
            if (m.isCapture()) {
                g.setStroke(new BasicStroke(s / 12f));
                g.drawOval(x + s / 12, y + s / 12, s - s / 6, s - s / 6);
            } else {
                g.fillOval(x + s * 3 / 8, y + s * 3 / 8, s / 4, s / 4);
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

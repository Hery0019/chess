package test;

import engine.Move;
import engine.Pieces;
import game.GameConfig;
import game.GameSession;
import ui.BoardPanel;
import ui.StartScreen;

import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives the Swing views with synthetic mouse events — no window, no
 * display needed (runs under {@code -Djava.awt.headless=true}). Covers the
 * BoardPanel interaction model (click-click, drag and drop, premoves,
 * flipped geometry) and the StartScreen's config emission. Plain main-class
 * runner; exit code != 0 on failure.
 */
public final class UiTests {

    /** Square size for a 400x400 board. */
    private static final int S = 50;
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        // Views must be touched on the EDT (GameSession asserts it when a display exists).
        SwingUtilities.invokeAndWait(() -> {
            try {
                boardInteraction();
                boardPremoves();
                boardFlippedGeometry();
                boardPromotionStrip();
                boardAnnotations();
                boardPaintsInEveryState();
                startScreenEmitsConfig();
            } catch (Exception e) {
                e.printStackTrace();
                failures++;
            }
        });
        if (failures > 0) {
            System.err.println(failures + " test(s) FAILED.");
            System.exit(1);
        }
        System.out.println("All UI tests passed.");
    }

    // ---- BoardPanel: click-click and drag & drop ----

    private static void boardInteraction() {
        GameSession session = new GameSession();
        List<Move> received = new ArrayList<>();
        BoardPanel p = newBoard(session, false, Pieces.WHITE, received);

        // Interaction disabled on the human's own turn: nothing happens.
        dragTo(p, sq("e2"), sq("e4"));
        check("board: no move while interaction disabled", received.isEmpty());

        // Drag and drop plays a move.
        p.setInteractionEnabled(true);
        dragTo(p, sq("e2"), sq("e4"));
        check("board: drag e2->e4 submits e2e4", submitted(received, "e2e4"));
        session.applyMove(received.remove(0));

        // Illegal drop snaps back, submits nothing; click-click still works after.
        p.setInteractionEnabled(false);
        session.applyMove(find(session, "e7e5"));
        p.setInteractionEnabled(true);
        dragTo(p, sq("g1"), sq("g4"));
        check("board: illegal drop submits nothing", received.isEmpty());
        click(p, sq("g1"));
        click(p, sq("f3"));
        check("board: click-click g1,f3 submits g1f3", submitted(received, "g1f3"));
        session.applyMove(received.remove(0));

        // Dropping back on the origin keeps the selection; clicking a target finishes.
        p.setInteractionEnabled(false);
        session.applyMove(find(session, "b8c6"));
        p.setInteractionEnabled(true);
        dragTo(p, sq("f1"), sq("f1"));
        click(p, sq("c4"));
        check("board: drop on origin keeps selection", submitted(received, "f1c4"));
        session.applyMove(received.remove(0));

        // Clicking an enemy piece or an empty square clears the selection.
        p.setInteractionEnabled(false);
        session.applyMove(find(session, "g8f6"));
        p.setInteractionEnabled(true);
        click(p, sq("d2"));
        click(p, sq("e7"));   // enemy
        click(p, sq("d4"));   // would be a target of d2 if still selected
        check("board: clicking elsewhere clears the selection", received.isEmpty());
    }

    // ---- BoardPanel: premoves ----

    private static void boardPremoves() {
        GameSession session = new GameSession();
        List<Move> received = new ArrayList<>();
        BoardPanel p = newBoard(session, false, Pieces.WHITE, received);
        // 1.e4 e5 2.Nf3 Nc6 3.Bc4 Nf6 — White (human) has just moved, Black (AI) to move.
        for (String m : List.of("e2e4", "e7e5", "g1f3", "b8c6", "f1c4")) session.applyMove(find(session, m));
        p.setInteractionEnabled(false);

        // Castling premove, recorded but not submitted; resolves to O-O when legal.
        click(p, sq("e1"));
        click(p, sq("g1"));
        check("premove: castling recorded", p.hasPremove() && "e1g1".equals(p.premoveText()));
        check("premove: nothing submitted while waiting", received.isEmpty());
        session.applyMove(find(session, "g8f6"));
        p.setInteractionEnabled(true);
        Move pre = p.consumePremove();
        check("premove: resolves to legal O-O", pre != null && pre.isCastle() && !p.hasPremove());
        session.applyMove(pre);
        p.setInteractionEnabled(false);

        // Optimistic premove through a blocker is accepted, then evaporates if still illegal.
        dragTo(p, sq("d1"), sq("d5"));
        check("premove: blocked ray accepted optimistically", "d1d5".equals(p.premoveText()));
        session.applyMove(find(session, "f8c5"));
        p.setInteractionEnabled(true);
        check("premove: illegal premove evaporates", p.consumePremove() == null);

        // Human's own turn with interaction off: neither a premove nor a move.
        p.setInteractionEnabled(false);
        click(p, sq("a2"));
        click(p, sq("a3"));
        check("premove: none on own turn", !p.hasPremove() && received.isEmpty());
        session.applyMove(find(session, "d2d3"));
        p.setInteractionEnabled(false);

        // Any left-click cancels; right-click cancels; enemy pieces cannot be premoved.
        click(p, sq("a2"));
        click(p, sq("a3"));
        check("premove: pawn a2a3", "a2a3".equals(p.premoveText()));
        click(p, sq("h7"));
        check("premove: left click cancels", !p.hasPremove());
        click(p, sq("b1"));
        click(p, sq("c3"));
        check("premove: knight b1c3", "b1c3".equals(p.premoveText()));
        press(p, sq("e4"), MouseEvent.BUTTON3);
        check("premove: right click cancels", !p.hasPremove());
        click(p, sq("c6"));
        click(p, sq("d4"));
        check("premove: enemy piece cannot be premoved", !p.hasPremove());

        // A new premove replaces the old one; own-piece squares are not targets.
        click(p, sq("c4"));
        click(p, sq("b3"));
        click(p, sq("c4"));
        click(p, sq("d5"));
        check("premove: replaced by a newer one", "c4d5".equals(p.premoveText()));
        click(p, sq("a1"));
        click(p, sq("a2"));
        check("premove: own-piece square is not a target", !p.hasPremove());

        // Double push premove resolves when the turn arrives.
        click(p, sq("h2"));
        click(p, sq("h4"));
        session.applyMove(find(session, "d7d6"));
        p.setInteractionEnabled(true);
        Move pre2 = p.consumePremove();
        check("premove: double push resolves", pre2 != null && pre2.toString().equals("h2h4"));

        // AI-vs-AI panel (no human colour) never premoves.
        GameSession s2 = new GameSession();
        BoardPanel p2 = newBoard(s2, false, -1, received);
        click(p2, sq("e2"));
        click(p2, sq("e4"));
        check("premove: none without a human player", !p2.hasPremove() && received.isEmpty());
    }

    // ---- BoardPanel: flipped orientation ----

    private static void boardFlippedGeometry() {
        GameSession session = new GameSession();
        List<Move> received = new ArrayList<>();
        BoardPanel p = newBoard(session, true, Pieces.BLACK, received);
        session.applyMove(find(session, "e2e4"));
        p.setInteractionEnabled(true);
        // Flipped: pixel position of square sq is that of display index 63 - sq.
        int e7 = 63 - sq("e7"), e5 = 63 - sq("e5");
        press(p, e7, MouseEvent.BUTTON1);
        drag(p, px(e7) + 10, py(e7) + 10);
        release(p, px(e5), py(e5));
        check("board: flipped drag e7->e5", submitted(received, "e7e5"));
    }

    // ---- BoardPanel: on-board promotion strip ----

    private static void boardPromotionStrip() {
        // White pawn on a7; the strip for a8 runs downwards: Q on a8, N on a7, R on a6, B on a5.
        GameSession session = new GameSession(engine.Board.fromFen("8/P7/8/8/8/8/8/k6K w - - 0 1"));
        List<Move> received = new ArrayList<>();
        BoardPanel p = newBoard(session, false, Pieces.WHITE, received);
        p.setInteractionEnabled(true);

        click(p, sq("a7"));
        click(p, sq("a8"));
        check("promotion: strip opens instead of submitting", received.isEmpty() && p.isChoosingPromotion());
        click(p, sq("a7"));   // second cell = knight
        check("promotion: clicking the knight cell submits a7a8n", submitted(received, "a7a8n") && !p.isChoosingPromotion());
        received.clear();

        click(p, sq("a7"));
        click(p, sq("a8"));
        click(p, sq("e4"));   // outside the strip: cancel
        check("promotion: clicking elsewhere cancels", received.isEmpty() && !p.isChoosingPromotion());
        click(p, sq("a8"));   // selection was cleared too: nothing happens
        check("promotion: cancel also clears the selection", received.isEmpty() && !p.isChoosingPromotion());

        dragTo(p, sq("a7"), sq("a8"));
        check("promotion: drop opens the strip", received.isEmpty() && p.isChoosingPromotion());
        click(p, sq("a6"));   // third cell = rook
        check("promotion: rook via drag then click", submitted(received, "a7a8r"));
        received.clear();

        // Paint with the strip open must not throw.
        click(p, sq("a7"));
        click(p, sq("a8"));
        p.paint(new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB).getGraphics());
        check("promotion: strip paints", p.isChoosingPromotion());
    }

    // ---- BoardPanel: right-click marks and arrows ----

    private static void boardAnnotations() {
        GameSession session = new GameSession();
        List<Move> received = new ArrayList<>();
        BoardPanel p = newBoard(session, false, Pieces.WHITE, received);
        p.setInteractionEnabled(true);

        rightClick(p, sq("e4"), sq("e4"));
        check("annotations: right-click marks a square", p.annotationCount() == 1);
        rightClick(p, sq("e4"), sq("e4"));
        check("annotations: right-click again removes the mark", p.annotationCount() == 0);
        rightClick(p, sq("g1"), sq("f3"));
        rightClick(p, sq("e2"), sq("e4"));
        check("annotations: right-drag draws arrows", p.annotationCount() == 2);
        rightClick(p, sq("g1"), sq("f3"));
        check("annotations: same arrow again removes it", p.annotationCount() == 1);
        p.paint(new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB).getGraphics());
        click(p, sq("d2"));
        check("annotations: left click clears everything", p.annotationCount() == 0);
        check("annotations: the left click still selects", received.isEmpty());
        click(p, sq("d4"));
        check("annotations: play continues normally", submitted(received, "d2d4"));

        // Also available while the board is not interactive.
        p.setInteractionEnabled(false);
        rightClick(p, sq("a1"), sq("h8"));
        check("annotations: arrows allowed while not interactive", p.annotationCount() == 1);
    }

    private static void rightClick(BoardPanel p, int from, int to) {
        press(p, from, MouseEvent.BUTTON3);
        p.dispatchEvent(new MouseEvent(p, MouseEvent.MOUSE_RELEASED, 0, 0, px(to), py(to), 1, false, MouseEvent.BUTTON3));
    }

    // ---- BoardPanel: painting never throws ----

    private static void boardPaintsInEveryState() {
        GameSession session = new GameSession();
        BoardPanel p = newBoard(session, false, Pieces.WHITE, new ArrayList<>());
        BufferedImage img = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        p.paint(img.getGraphics());                      // idle
        p.setInteractionEnabled(true);
        press(p, sq("e2"), MouseEvent.BUTTON1);
        drag(p, px(sq("e2")) + 10, py(sq("e2")) + 10);
        drag(p, px(sq("e3")), py(sq("e3")));
        p.paint(img.getGraphics());                      // mid-drag, hover outline
        release(p, px(sq("e4")), py(sq("e4")));
        session.applyMove(find(session, "e2e4"));
        p.setInteractionEnabled(false);
        click(p, sq("g1"));
        click(p, sq("f3"));
        p.paint(img.getGraphics());                      // premove ghost
        check("board: paints in idle, drag and premove states", true);
    }

    // ---- StartScreen ----

    private static void startScreenEmitsConfig() {
        List<GameConfig> emitted = new ArrayList<>();
        StartScreen s = new StartScreen(null, emitted::add, saved -> {});
        s.setSize(640, 760);
        layoutTree(s);
        s.paint(new BufferedImage(640, 760, BufferedImage.TYPE_INT_RGB).getGraphics());

        AbstractButton start = findButton(s, "Start Game");
        check("start: Start Game button exists", start != null);
        if (start == null) return;
        start.doClick();
        check("start: emits the default config",
                emitted.size() == 1
                && emitted.get(0).mode() == GameConfig.Mode.HUMAN_VS_AI
                && emitted.get(0).humanColor() == Pieces.WHITE
                && emitted.get(0).minutesPerSide() == GameConfig.NO_CLOCK
                && emitted.get(0).aiDepth() == 4);

        // Change every option through the visible controls and start again.
        AbstractButton aiVsAi = findButton(s, "AI vs AI");
        AbstractButton black = findButton(s, "Black");
        AbstractButton fiveMin = findButton(s, "5");
        AbstractButton depth2 = findButton(s, "2");
        check("start: option buttons exist", aiVsAi != null && black != null && fiveMin != null && depth2 != null);
        if (aiVsAi == null || black == null || fiveMin == null || depth2 == null) return;
        aiVsAi.doClick();
        check("start: side controls disabled in AI vs AI", !black.isEnabled());
        findButton(s, "Human vs AI").doClick();
        check("start: side controls re-enabled", black.isEnabled());
        black.doClick();
        fiveMin.doClick();
        depth2.doClick();
        start.doClick();
        GameConfig cfg = emitted.get(emitted.size() - 1);
        check("start: emits the chosen config",
                cfg.mode() == GameConfig.Mode.HUMAN_VS_AI && cfg.humanColor() == Pieces.BLACK
                && cfg.minutesPerSide() == 5 && cfg.aiDepth() == 2);

        // Remembered settings are preselected and come back unchanged.
        GameConfig remembered = new GameConfig(GameConfig.Mode.AI_VS_AI, Pieces.BLACK, 15, 6);
        List<GameConfig> again = new ArrayList<>();
        StartScreen s2 = new StartScreen(remembered, again::add, saved -> {});
        s2.setSize(640, 760);
        layoutTree(s2);
        AbstractButton black2 = findButton(s2, "Black");
        findButton(s2, "Start Game").doClick();
        check("start: remembered settings preselected",
                again.size() == 1 && again.get(0).equals(remembered) && black2 != null && !black2.isEnabled());
    }

    // ---- helpers ----

    private static BoardPanel newBoard(GameSession session, boolean flipped, int humanColor, List<Move> sink) {
        BoardPanel p = new BoardPanel(session, flipped, humanColor, sink::add);
        p.setSize(8 * S, 8 * S);
        return p;
    }

    private static int sq(String name) { return (name.charAt(0) - 'a') + 8 * (name.charAt(1) - '1'); }
    private static int px(int sq) { return (sq & 7) * S + S / 2; }
    private static int py(int sq) { return (7 - (sq >>> 3)) * S + S / 2; }

    private static void press(BoardPanel p, int sq, int button) {
        int mods = button == MouseEvent.BUTTON1 ? InputEvent.BUTTON1_DOWN_MASK : InputEvent.BUTTON3_DOWN_MASK;
        p.dispatchEvent(new MouseEvent(p, MouseEvent.MOUSE_PRESSED, 0, mods, px(sq), py(sq), 1, false, button));
    }

    private static void drag(BoardPanel p, int x, int y) {
        p.dispatchEvent(new MouseEvent(p, MouseEvent.MOUSE_DRAGGED, 0, InputEvent.BUTTON1_DOWN_MASK,
                x, y, 1, false, MouseEvent.NOBUTTON));
    }

    private static void release(BoardPanel p, int x, int y) {
        p.dispatchEvent(new MouseEvent(p, MouseEvent.MOUSE_RELEASED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1));
    }

    private static void click(BoardPanel p, int sq) {
        press(p, sq, MouseEvent.BUTTON1);
        release(p, px(sq), py(sq));
    }

    private static void dragTo(BoardPanel p, int from, int to) {
        press(p, from, MouseEvent.BUTTON1);
        drag(p, px(from) + 10, py(from) + 10);
        drag(p, (px(from) + px(to)) / 2, (py(from) + py(to)) / 2);
        drag(p, px(to), py(to));
        release(p, px(to), py(to));
    }

    private static boolean submitted(List<Move> received, String lan) {
        return received.size() == 1 && received.get(0).toString().equals(lan);
    }

    private static Move find(GameSession s, String lan) {
        for (Move m : s.legalMoves()) if (m.toString().equals(lan)) return m;
        throw new IllegalStateException("not legal now: " + lan);
    }

    /** Lays out a component tree without a peer (no window in headless mode). */
    private static void layoutTree(Component c) {
        c.doLayout();
        if (c instanceof Container ct) for (Component child : ct.getComponents()) layoutTree(child);
    }

    private static AbstractButton findButton(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof AbstractButton b && text.equals(b.getText())) return b;
            if (c instanceof Container ct) {
                AbstractButton found = findButton(ct, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void check(String name, boolean ok) {
        System.out.printf("%-55s %s%n", name, ok ? "OK" : "FAIL");
        if (!ok) failures++;
    }
}

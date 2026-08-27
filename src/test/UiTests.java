package test;

import engine.Move;
import engine.Pieces;
import game.GameConfig;
import game.GameSession;
import game.SavedGame;
import ui.BoardPanel;
import ui.GamePanel;
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
 * flipped geometry), the StartScreen's config emission and the GamePanel's
 * takeback allowance. Plain main-class
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
                gamePanelTakebackLimit();
                gameOverBanner();
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

        // Queue: a later premove starts from where the earlier one leaves the piece.
        click(p, sq("c4"));
        click(p, sq("b3"));
        click(p, sq("b3"));   // the bishop is already "on" b3
        click(p, sq("d5"));
        check("premove: queue of two", "c4b3 b3d5".equals(p.premoveText()));
        click(p, sq("a1"));
        click(p, sq("a2"));   // own pawn: re-selects a2, queue untouched
        check("premove: a click on an own piece re-selects it", p.premoveCount() == 2);
        click(p, sq("h2"));
        click(p, sq("h4"));
        check("premove: three queued", "c4b3 b3d5 h2h4".equals(p.premoveText()));

        // The queue plays out one move per turn.
        session.applyMove(find(session, "d7d6"));
        p.setInteractionEnabled(true);
        Move q1 = p.consumePremove();
        check("premove: first of the queue resolves",
                q1 != null && q1.toString().equals("c4b3") && "b3d5 h2h4".equals(p.premoveText()));
        session.applyMove(q1);
        p.setInteractionEnabled(false);
        session.applyMove(find(session, "a7a6"));
        p.setInteractionEnabled(true);
        Move q2 = p.consumePremove();
        check("premove: second resolves from its new square", q2 != null && q2.toString().equals("b3d5"));
        session.applyMove(q2);
        p.setInteractionEnabled(false);
        session.applyMove(find(session, "h7h6"));
        p.setInteractionEnabled(true);
        Move q3 = p.consumePremove();
        check("premove: double push resolves, queue empty", q3 != null && q3.toString().equals("h2h4") && !p.hasPremove());
        session.applyMove(q3);
        p.setInteractionEnabled(false);

        // An illegal first premove drops the whole queue.
        click(p, sq("d1"));
        click(p, sq("e2"));
        click(p, sq("e2"));
        click(p, sq("e3"));
        check("premove: queue pending", p.premoveCount() == 2);
        session.applyMove(find(session, "c5f2"));   // Bxf2+ — Qe2 no longer legal
        p.setInteractionEnabled(true);
        check("premove: illegal first premove drops the queue", p.consumePremove() == null && !p.hasPremove());

        // Recapture premoves: dragging onto an own piece is allowed; the premove
        // resolves only if that piece has been taken in the meantime.
        GameSession rs1 = new GameSession();
        List<Move> rgot = new ArrayList<>();
        BoardPanel rq1 = newBoard(rs1, false, Pieces.WHITE, rgot);
        for (String m : List.of("e2e4", "d7d5", "b1c3")) rs1.applyMove(find(rs1, m));   // Black may take on e4
        rq1.setInteractionEnabled(false);
        click(rq1, sq("c3"));
        click(rq1, sq("e4"));
        check("premove: click on an own piece does not premove onto it", !rq1.hasPremove());
        dragTo(rq1, sq("c3"), sq("e4"));
        check("premove: dragging onto an own piece records a recapture", "c3e4".equals(rq1.premoveText()) && rgot.isEmpty());
        rs1.applyMove(find(rs1, "d5e4"));
        rq1.setInteractionEnabled(true);
        Move rmv = rq1.consumePremove();
        check("premove: recapture resolves once the piece was taken", rmv != null && rmv.toString().equals("c3e4") && !rq1.hasPremove());

        GameSession rs2 = new GameSession();
        BoardPanel rq2 = newBoard(rs2, false, Pieces.WHITE, new ArrayList<>());
        for (String m : List.of("e2e4", "d7d5", "b1c3")) rs2.applyMove(find(rs2, m));
        rq2.setInteractionEnabled(false);
        dragTo(rq2, sq("c3"), sq("e4"));
        rs2.applyMove(find(rs2, "e7e6"));                                             // the pawn was not taken
        rq2.setInteractionEnabled(true);
        check("premove: recapture evaporates when the piece is still there", rq2.consumePremove() == null && !rq2.hasPremove());

        GameSession rs3 = new GameSession();
        BoardPanel rq3 = newBoard(rs3, false, Pieces.WHITE, new ArrayList<>());
        rs3.applyMove(find(rs3, "g1f3"));                                             // own knight ahead of the f-pawn
        rq3.setInteractionEnabled(false);
        dragTo(rq3, sq("f2"), sq("f3"));
        check("premove: a pawn cannot be premoved straight onto an own piece", !rq3.hasPremove());

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
        StartScreen s = new StartScreen(null, "Tester", "localhost:5000", emitted::add, saved -> {}, req -> {});
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
                && emitted.get(0).aiLevel() == engine.Skill.DEFAULT
                && emitted.get(0).undoLimit() == GameConfig.DEFAULT_UNDO_LIMIT);

        // Change every option through the visible controls and start again.
        AbstractButton aiVsAi = findButton(s, "AI vs AI");
        AbstractButton black = findButton(s, "Black");
        AbstractButton fiveMin = findButton(s, "5");
        AbstractButton depth2 = findButton(s, "700");    // strength pills are labelled with their Elo
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
                && cfg.minutesPerSide() == 5 && cfg.aiLevel() == 2);

        // Undo: Off greys out the limit pills and emits NO_UNDO; On + a pill emits that limit.
        // Time and depth also have numeric pills, so the takeback "10" is the last one in the tree.
        AbstractButton undoOff = findButton(s, "Off"), undoOn = findButton(s, "On");
        AbstractButton ten = lastButton(s, "10");
        check("start: undo controls exist", undoOff != null && undoOn != null && ten != null);
        if (undoOff == null || undoOn == null || ten == null) return;
        check("start: undo on by default, limit pills enabled", undoOn.isSelected() && ten.isEnabled());
        undoOff.doClick();
        check("start: limit pills disabled when undo is off", !ten.isEnabled());
        start.doClick();
        check("start: undo off emits NO_UNDO", emitted.get(emitted.size() - 1).undoLimit() == GameConfig.NO_UNDO);
        undoOn.doClick();
        ten.doClick();
        start.doClick();
        check("start: undo on emits the chosen limit", emitted.get(emitted.size() - 1).undoLimit() == 10);
        aiVsAi.doClick();
        check("start: undo controls disabled in AI vs AI", !undoOn.isEnabled() && !ten.isEnabled());
        findButton(s, "Human vs AI").doClick();
        check("start: undo controls re-enabled", undoOn.isEnabled() && ten.isEnabled());

        // Remembered settings are preselected and come back unchanged.
        GameConfig remembered = new GameConfig(GameConfig.Mode.AI_VS_AI, Pieces.BLACK, 15, 6, 5);
        List<GameConfig> again = new ArrayList<>();
        StartScreen s2 = new StartScreen(remembered, "Tester", "localhost:5000", again::add, saved -> {}, req -> {});
        s2.setSize(640, 760);
        layoutTree(s2);
        AbstractButton black2 = findButton(s2, "Black");
        findButton(s2, "Start Game").doClick();
        check("start: remembered settings preselected",
                again.size() == 1 && again.get(0).equals(remembered) && black2 != null && !black2.isEnabled());

        // Online mode: side/strength disabled, name and address travel with the request.
        List<StartScreen.OnlineRequest> requests = new ArrayList<>();
        StartScreen s3 = new StartScreen(null, "Alice", "192.168.1.10:5000", emitted::add, saved -> {}, requests::add);
        s3.setSize(640, 760);
        layoutTree(s3);
        findButton(s3, "Online 1 v 1").doClick();
        AbstractButton depth3 = findButton(s3, "1500");
        check("online: side and strength disabled", !findButton(s3, "Black").isEnabled() && depth3 != null && !depth3.isEnabled());
        List<javax.swing.JTextField> fields = new ArrayList<>();
        collect(s3, javax.swing.JTextField.class, fields);
        check("online: name and address fields prefilled",
                fields.size() == 2 && fields.get(0).getText().equals("Alice") && fields.get(1).getText().equals("192.168.1.10:5000"));
        fields.get(1).setText("chess.example.org:6000");
        findButton(s3, "10").doClick();
        findButton(s3, "Host game").doClick();
        findButton(s3, "Join game").doClick();
        check("online: host and join requests carry name, address and time",
                requests.size() == 2 && requests.get(0).host() && !requests.get(1).host()
                && requests.get(1).name().equals("Alice") && requests.get(1).address().equals("chess.example.org:6000")
                && requests.get(1).minutes() == 10 && emitted.size() == 4);
    }

    // ---- GamePanel: takeback allowance ----

    private static void gamePanelTakebackLimit() {
        GamePanel.Host host = new GamePanel.Host() {
            @Override public void newGame() { }
            @Override public void startGame(GameConfig config) { }
        };
        List<String> moves = List.of("e2e4", "e7e5", "g1f3", "b8c6");   // White (human) to move

        // One takeback allowed: usable once, then the button reports 0 left and greys out.
        GameConfig one = new GameConfig(GameConfig.Mode.HUMAN_VS_AI, Pieces.WHITE, GameConfig.NO_CLOCK, 1, 1);
        GamePanel panel = new GamePanel(one, new SavedGame(one, moves, 0, 0, 0), host);
        panel.setSize(900, 700);
        layoutTree(panel);
        panel.startGame();
        AbstractButton undo = findButtonStartingWith(panel, "Undo");
        List<javax.swing.JTable> tables = new ArrayList<>();
        collect(panel, javax.swing.JTable.class, tables);
        check("game: undo button shows the allowance",
                undo != null && "Undo (1 left)".equals(undo.getText()) && undo.isEnabled() && tables.size() == 1);
        if (undo == null || tables.isEmpty()) return;
        undo.doClick();
        check("game: takeback removes the move and the AI reply", tables.get(0).getRowCount() == 1);
        check("game: allowance spent greys out the button", "Undo (0 left)".equals(undo.getText()) && !undo.isEnabled());
        undo.doClick();
        check("game: no further takeback once spent", tables.get(0).getRowCount() == 1);
        panel.dispose();

        // A resumed game carries the takebacks already spent.
        GameConfig two = new GameConfig(GameConfig.Mode.HUMAN_VS_AI, Pieces.WHITE, GameConfig.NO_CLOCK, 1, 2);
        GamePanel resumed = new GamePanel(two, new SavedGame(two, moves, 0, 0, 1), host);
        resumed.setSize(900, 700);
        layoutTree(resumed);
        resumed.startGame();
        AbstractButton undo2 = findButtonStartingWith(resumed, "Undo");
        check("game: resumed game remembers takebacks spent",
                undo2 != null && "Undo (1 left)".equals(undo2.getText()) && undo2.isEnabled());
        resumed.dispose();

        // Undo switched off: no button at all.
        GameConfig off = new GameConfig(GameConfig.Mode.HUMAN_VS_AI, Pieces.WHITE, GameConfig.NO_CLOCK, 1, GameConfig.NO_UNDO);
        GamePanel noUndo = new GamePanel(off, new SavedGame(off, moves, 0, 0, 0), host);
        noUndo.setSize(900, 700);
        layoutTree(noUndo);
        noUndo.startGame();
        check("game: no undo button when takebacks are off", findButtonStartingWith(noUndo, "Undo") == null);
        noUndo.dispose();
    }

    // ---- game-over banner over the board ----

    private static void gameOverBanner() {
        // BoardPanel: the announcement paints, and a click puts it away.
        GameSession session = new GameSession();
        BoardPanel p = newBoard(session, false, Pieces.WHITE, new ArrayList<>());
        p.showBanner("CHECKMATE", "White wins");
        BufferedImage img = new BufferedImage(8 * S, 8 * S, BufferedImage.TYPE_INT_RGB);
        p.paint(img.getGraphics());
        check("banner: shown and painted", "CHECKMATE".equals(p.bannerTitle()));
        click(p, sq("e4"));
        check("banner: a click on the board dismisses it", p.bannerTitle() == null);

        // GamePanel: a game that ends in mate announces it; a takeback that resumes the game clears it.
        GamePanel.Host host = new GamePanel.Host() {
            @Override public void newGame() { }
            @Override public void startGame(GameConfig config) { }
        };
        GameConfig cfg = new GameConfig(GameConfig.Mode.HUMAN_VS_AI, Pieces.WHITE, GameConfig.NO_CLOCK, 1, 3);
        List<String> foolsMate = List.of("f2f3", "e7e5", "g2g4", "d8h4");
        GamePanel panel = new GamePanel(cfg, new SavedGame(cfg, foolsMate, 0, 0, 0), host);
        panel.setSize(900, 700);
        layoutTree(panel);
        panel.startGame();
        List<BoardPanel> boards = new ArrayList<>();
        collect(panel, BoardPanel.class, boards);
        check("banner: checkmate announced over the board when the game ends",
                boards.size() == 1 && "CHECKMATE".equals(boards.get(0).bannerTitle()));
        panel.paint(new BufferedImage(900, 700, BufferedImage.TYPE_INT_RGB).getGraphics());
        AbstractButton undo = findButtonStartingWith(panel, "Undo");
        if (undo != null) undo.doClick();
        check("banner: taking the mate back clears it", undo != null && boards.get(0).bannerTitle() == null);
        panel.dispose();

        check("banner: titles by result",
                "CHECKMATE".equals(GamePanel.bannerFor(game.GameResult.BLACK_WINS_MATE)[0])
                && "Black wins".equals(GamePanel.bannerFor(game.GameResult.BLACK_WINS_MATE)[1])
                && "STALEMATE".equals(GamePanel.bannerFor(game.GameResult.DRAW_STALEMATE)[0])
                && "DRAW".equals(GamePanel.bannerFor(game.GameResult.DRAW_REPETITION)[0])
                && "Threefold repetition".equals(GamePanel.bannerFor(game.GameResult.DRAW_REPETITION)[1])
                && "TIME OUT".equals(GamePanel.bannerFor(game.GameResult.WHITE_WINS_TIMEOUT)[0]));
    }

    private static <T> void collect(Container root, Class<T> type, List<T> out) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) out.add(type.cast(c));
            if (c instanceof Container ct) collect(ct, type, out);
        }
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

    /** The last button with this exact text in tree order (numeric pill labels repeat across controls). */
    private static AbstractButton lastButton(Container root, String text) {
        List<AbstractButton> all = new ArrayList<>();
        collect(root, AbstractButton.class, all);
        AbstractButton found = null;
        for (AbstractButton b : all) if (text.equals(b.getText())) found = b;
        return found;
    }

    private static AbstractButton findButtonStartingWith(Container root, String prefix) {
        List<AbstractButton> all = new ArrayList<>();
        collect(root, AbstractButton.class, all);
        for (AbstractButton b : all) if (b.getText() != null && b.getText().startsWith(prefix)) return b;
        return null;
    }

    private static void check(String name, boolean ok) {
        System.out.printf("%-55s %s%n", name, ok ? "OK" : "FAIL");
        if (!ok) failures++;
    }
}

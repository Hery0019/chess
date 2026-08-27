package test;

import engine.Board;
import engine.Move;
import engine.MoveGenerator;
import net.ChessClient;
import net.ChessServer;
import net.Protocol;
import net.Protocol.Message;

import javax.swing.SwingUtilities;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static net.Protocol.*;

/**
 * End-to-end tests of the online layer: a real {@link ChessServer} on an
 * ephemeral port, real {@link ChessClient}s over loopback. Covers pairing,
 * validated relay of moves, out-of-turn and illegal moves, draw offers,
 * rematch with swapped colours, resignation, opponent disconnect and server
 * shutdown. Plain main-class runner; exit code != 0 on failure.
 */
public final class NetTests {

    private static final long TIMEOUT_MS = 5_000;
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        protocolBasics();
        try (ChessServer server = new ChessServer(0, true)) {
            server.start();
            pairingAndPlay(server.port());
            panelsPlayOverLoopback(server.port());
            waitingClientSeesShutdown(server);
        }
        if (failures > 0) {
            System.err.println(failures + " test(s) FAILED.");
            System.exit(1);
        }
        System.out.println("All network tests passed.");
        System.exit(0);
    }

    // ---- protocol helpers ----

    private static void protocolBasics() {
        Message m = Message.parse("  MOVE   e2e4 ");
        check("protocol: parse and re-serialise", m.type().equals(MOVE) && m.arg(0).equals("e2e4") && m.line().equals("MOVE e2e4"));
        check("protocol: message without args", Message.parse("PING").args().isEmpty() && Message.parse("PING").arg(0) == null);
        boolean threw = false;
        try { Message.parse("   "); } catch (IllegalArgumentException e) { threw = true; }
        check("protocol: empty line rejected", threw);
        check("protocol: names become single safe tokens",
                sanitizeName("Alice Smith!").equals("Alice_Smith_")
                && sanitizeName("").equals("Player") && sanitizeName(null).equals("Player")
                && sanitizeName("abcdefghijklmnopqrstuvwxyz").length() == Protocol.MAX_NAME_LENGTH);
    }

    // ---- two players over loopback ----

    private static void pairingAndPlay(int port) throws Exception {
        Recorder ra = new Recorder(), rb = new Recorder();
        ChessClient a = ChessClient.connect("127.0.0.1", port, "Alice Smith!", 5, 2_000);
        listen(a, ra);
        check("net: first client is welcomed and waits", ra.next().type().equals(WELCOME) && ra.quiet(300));

        ChessClient b = ChessClient.connect("127.0.0.1", port, "Bob", 3, 2_000);
        listen(b, rb);
        check("net: second client is welcomed", rb.next().type().equals(WELCOME));
        Message sa = ra.next(), sb = rb.next();
        check("net: both players get START with opposite colours",
                sa != null && sb != null && sa.type().equals(START) && sb.type().equals(START)
                && sa.arg(0).equals("WHITE") && sb.arg(0).equals("BLACK"));
        check("net: the first arrival's time control and sanitised names are used",
                sa.arg(1).equals("5") && sb.arg(1).equals("5")
                && sa.arg(2).equals("Alice_Smith_") && sa.arg(3).equals("Bob") && sb.args().equals(sb.args()));

        // White moves; Black receives exactly that move.
        Move e4 = find(Board.startPosition(), "e2e4");
        a.sendMove(e4);
        Message mv = rb.next();
        check("net: legal move relayed to the opponent", mv != null && mv.type().equals(MOVE) && mv.arg(0).equals("e2e4"));

        // Out of turn and illegal moves are refused and never relayed.
        a.send(Message.of(MOVE, "d2d4"));
        Message err = ra.next();
        check("net: out-of-turn move refused", err != null && err.type().equals(ERROR) && err.line().contains("turn") && rb.quiet(300));
        b.send(Message.of(MOVE, "e7e4"));
        err = rb.next();
        check("net: illegal move refused", err != null && err.type().equals(ERROR) && err.line().contains("illegal") && ra.quiet(300));
        b.send(Message.of(MOVE, "e7e5"));
        mv = ra.next();
        check("net: black's legal reply relayed", mv != null && mv.type().equals(MOVE) && mv.arg(0).equals("e7e5"));

        // Draw offers travel both ways; a decline and an acceptance.
        a.offerDraw();
        check("net: draw offer relayed", typeOf(rb.next()).equals(DRAW_OFFER));
        b.declineDraw();
        check("net: draw decline relayed", typeOf(ra.next()).equals(DRAW_DECLINE));
        b.offerDraw();
        check("net: draw offer relayed (other way)", typeOf(ra.next()).equals(DRAW_OFFER));
        a.acceptDraw();
        check("net: draw acceptance relayed", typeOf(rb.next()).equals(DRAW_ACCEPT));
        a.send(Message.of(MOVE, "g1f3"));
        err = ra.next();
        check("net: no moves after the game ended", err != null && err.type().equals(ERROR) && err.line().contains("over"));

        // Rematch needs both; colours swap.
        a.requestRematch();
        check("net: rematch request relayed", typeOf(rb.next()).equals(REMATCH));
        check("net: one request is not enough", ra.quiet(300));
        b.requestRematch();
        Message r1 = ra.next(), r2 = ra.next(), r3 = rb.next();
        check("net: second request starts the rematch with swapped colours",
                typeOf(r1).equals(REMATCH) && typeOf(r2).equals(START) && typeOf(r3).equals(START)
                && r2.arg(0).equals("BLACK") && r3.arg(0).equals("WHITE")
                && r2.arg(2).equals("Bob") && r2.arg(3).equals("Alice_Smith_"));

        // Bob is White now: he moves, then resigns.
        b.send(Message.of(MOVE, "d2d4"));
        mv = ra.next();
        check("net: new white's move relayed", mv != null && mv.type().equals(MOVE) && mv.arg(0).equals("d2d4"));
        b.resign();
        check("net: resignation relayed", typeOf(ra.next()).equals(RESIGN));

        // Timeout report relayed after another rematch.
        a.requestRematch();
        b.requestRematch();
        Message rem1 = ra.next(), rem2 = rb.next();   // each side hears the other's request first
        check("net: both rematch requests relayed", typeOf(rem1).equals(REMATCH) && typeOf(rem2).equals(REMATCH));
        Message s3a = ra.next(), s3b = rb.next();
        check("net: third game, colours swapped back",
                typeOf(s3a).equals(START) && s3a.arg(0).equals("WHITE") && typeOf(s3b).equals(START) && s3b.arg(0).equals("BLACK"));
        a.reportTimeout();
        check("net: flag fall relayed", typeOf(rb.next()).equals(TIMEOUT));

        // Alice leaves: Bob is told, and can no longer play.
        a.close();
        check("net: opponent disconnect reported", typeOf(rb.next()).equals(OPPONENT_LEFT));
        check("net: local close raises no disconnect callback", ra.noDisconnect(300));
        b.send(Message.of(MOVE, "e2e4"));
        err = rb.next();
        check("net: room is gone after the opponent left", err != null && err.type().equals(ERROR));
        b.close();
    }

    // ---- two real GamePanels wired through the network ----

    /**
     * The full client-side path: a click on one panel's board becomes a MOVE
     * on the wire, is validated by the server, arrives at the other panel and
     * is applied there — then the reply travels back. No dialogs are
     * triggered (the game is left unfinished), so this runs headless.
     */
    private static void panelsPlayOverLoopback(int port) throws Exception {
        Recorder ra = new Recorder(), rb = new Recorder();
        ChessClient a = ChessClient.connect("127.0.0.1", port, "Alice", 0, 2_000);
        listen(a, ra);
        ChessClient b = ChessClient.connect("127.0.0.1", port, "Bob", 0, 2_000);
        listen(b, rb);
        ra.next();                                   // WELCOME
        rb.next();
        Message sa = ra.next(), sb = rb.next();      // START
        check("panels: paired", typeOf(sa).equals(START) && typeOf(sb).equals(START));
        // Colours come from START (whose HELLO the server handled first), exactly as MainFrame does.
        int colorA = "WHITE".equals(sa.arg(0)) ? engine.Pieces.WHITE : engine.Pieces.BLACK;
        int colorB = colorA ^ 1;
        check("panels: START colours are opposite", sb.arg(0).equals(colorB == engine.Pieces.WHITE ? "WHITE" : "BLACK"));

        ui.GamePanel[] panels = new ui.GamePanel[2];
        ui.GamePanel.Host noHost = new ui.GamePanel.Host() {
            @Override public void newGame() { }
            @Override public void startGame(game.GameConfig config) { }
        };
        SwingUtilities.invokeAndWait(() -> {
            panels[0] = new ui.GamePanel(new game.GameConfig(game.GameConfig.Mode.ONLINE, colorA, 0, 1),
                    null, noHost, a, sa.arg(2), sa.arg(3));
            panels[1] = new ui.GamePanel(new game.GameConfig(game.GameConfig.Mode.ONLINE, colorB, 0, 1),
                    null, noHost, b, sb.arg(2), sb.arg(3));
            a.setListener(routeTo(panels[0]));
            b.setListener(routeTo(panels[1]));
            for (ui.GamePanel p : panels) {
                p.setSize(900, 700);
                layoutTree(p);
                p.startGame();
            }
        });
        ui.GamePanel white = colorA == engine.Pieces.WHITE ? panels[0] : panels[1];
        ui.GamePanel black = colorA == engine.Pieces.WHITE ? panels[1] : panels[0];
        ui.BoardPanel boardW = findBoard(white), boardB = findBoard(black);
        check("panels: boards found and sized", boardW != null && boardB != null && boardW.getWidth() >= 400);

        // White clicks e2-e4; the black panel must receive and apply it.
        clickMove(boardW, "e2", "e4");
        check("panels: white's click reaches black's board", waitForPlies(black, 1) && san(black).equals("e4"));
        check("panels: white's own board applied it too", san(white).equals("e4"));

        // Black replies on its (flipped) board; the white panel must receive it.
        clickMove(boardB, "e7", "e5");
        check("panels: black's reply reaches white's board", waitForPlies(white, 2) && san(white).equals("e4 e5"));

        // Clicking out of turn does nothing and sends nothing.
        clickMove(boardB, "d7", "d5");
        Thread.sleep(300);
        check("panels: out-of-turn click ignored locally", san(black).equals("e4 e5") && san(white).equals("e4 e5"));

        SwingUtilities.invokeAndWait(() -> { panels[0].dispose(); panels[1].dispose(); });
        a.close();
        b.close();
    }

    private static ChessClient.Listener routeTo(ui.GamePanel panel) {
        return new ChessClient.Listener() {
            @Override public void onMessage(Message m) { panel.onOnlineMessage(m); }
            @Override public void onDisconnected(String reason) { panel.onOnlineDisconnected(reason); }
        };
    }

    private static ui.BoardPanel findBoard(java.awt.Container root) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof ui.BoardPanel b) return b;
            if (c instanceof java.awt.Container ct) {
                ui.BoardPanel found = findBoard(ct);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void layoutTree(java.awt.Component c) {
        c.doLayout();
        if (c instanceof java.awt.Container ct) for (java.awt.Component child : ct.getComponents()) layoutTree(child);
    }

    private static void clickMove(ui.BoardPanel board, String from, String to) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            click(board, from);
            click(board, to);
        });
    }

    private static void click(ui.BoardPanel board, String square) {
        int sq = (square.charAt(0) - 'a') + 8 * (square.charAt(1) - '1');
        int s = Math.min(board.getWidth(), board.getHeight()) / 8;
        int x = (sq & 7) * s + s / 2, y = (7 - (sq >>> 3)) * s + s / 2;   // white at the bottom
        if (board.isFlipped()) { x = 8 * s - x; y = 8 * s - y; }   // 180° rotation of the board area
        board.dispatchEvent(new java.awt.event.MouseEvent(board, java.awt.event.MouseEvent.MOUSE_PRESSED, 0,
                java.awt.event.InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, java.awt.event.MouseEvent.BUTTON1));
        board.dispatchEvent(new java.awt.event.MouseEvent(board, java.awt.event.MouseEvent.MOUSE_RELEASED, 0,
                0, x, y, 1, false, java.awt.event.MouseEvent.BUTTON1));
    }

    private static game.GameSession sessionOf(ui.GamePanel panel) throws Exception {
        java.lang.reflect.Field f = ui.GamePanel.class.getDeclaredField("session");
        f.setAccessible(true);
        return (game.GameSession) f.get(panel);
    }

    private static String san(ui.GamePanel panel) throws Exception {
        String[] out = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            try { out[0] = String.join(" ", sessionOf(panel).sanHistory()); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        return out[0];
    }

    private static boolean waitForPlies(ui.GamePanel panel, int plies) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            int[] n = new int[1];
            SwingUtilities.invokeAndWait(() -> {
                try { n[0] = sessionOf(panel).plyCount(); } catch (Exception e) { throw new RuntimeException(e); }
            });
            if (n[0] >= plies) return true;
            Thread.sleep(20);
        }
        return false;
    }

    private static void waitingClientSeesShutdown(ChessServer server) throws Exception {
        Recorder rc = new Recorder();
        ChessClient c = ChessClient.connect("127.0.0.1", server.port(), "Carol", 0, 2_000);
        listen(c, rc);
        check("net: lone client waits", typeOf(rc.next()).equals(WELCOME));
        server.close();
        String reason = rc.disconnects.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        check("net: server shutdown disconnects the waiting client", reason != null && !c.isOpen());
    }

    // ---- helpers ----

    /** Collects what the client delivers (on the EDT) for the test thread to inspect. */
    private static final class Recorder implements ChessClient.Listener {
        final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<String> disconnects = new LinkedBlockingQueue<>();

        @Override public void onMessage(Message m) { messages.add(m); }
        @Override public void onDisconnected(String reason) { disconnects.add(reason); }

        Message next() throws InterruptedException { return messages.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS); }
        boolean quiet(long ms) throws InterruptedException { return messages.poll(ms, TimeUnit.MILLISECONDS) == null; }
        boolean noDisconnect(long ms) throws InterruptedException { return disconnects.poll(ms, TimeUnit.MILLISECONDS) == null; }
    }

    private static void listen(ChessClient client, Recorder r) throws Exception {
        SwingUtilities.invokeAndWait(() -> client.setListener(r));   // listeners are installed on the EDT
    }

    private static String typeOf(Message m) { return m == null ? "<none>" : m.type(); }

    private static Move find(Board b, String lan) {
        for (Move m : new MoveGenerator().generateLegal(b)) if (m.toString().equals(lan)) return m;
        throw new AssertionError("not legal: " + lan);
    }

    private static void check(String name, boolean ok) {
        System.out.printf("%-55s %s%n", name, ok ? "OK" : "FAIL");
        if (!ok) failures++;
    }
}

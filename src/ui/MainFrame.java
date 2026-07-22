package ui;

import engine.Pieces;
import game.GameConfig;
import game.SavedGame;
import net.ChessClient;
import net.ChessServer;
import net.Protocol;
import net.Protocol.Message;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import java.awt.CardLayout;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Application shell: start screen <-> lobby <-> game panel via CardLayout.
 * Owns the lifecycle handoff — the outgoing GamePanel is always disposed
 * (timers stopped, worker cancelled) before being replaced, so no orphaned
 * timers or workers can outlive their game. Also owns the online session:
 * the embedded server when hosting, the client connection, and the single
 * listener that routes START to a new GamePanel and everything else to the
 * current one. Remembers window placement and start-screen settings.
 */
public final class MainFrame extends JFrame implements GamePanel.Host {

    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private final CardLayout cards = new CardLayout();
    private GamePanel currentGame;

    // ---- online session (null when offline) ----
    private ChessServer server;
    private ChessClient client;
    private OnlineLobbyPanel lobby;

    public MainFrame() {
        super("Chess");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(cards);
        add(new StartScreen(Prefs.lastConfig(), Prefs.onlineName(), Prefs.onlineAddress(),
                this::startGame, this::resumeGame, this::startOnline), "start");

        Rectangle remembered = Prefs.windowBounds();
        if (remembered != null && fitsOnAScreen(remembered)) {
            setBounds(remembered);
        } else {
            setSize(900, 760);
            setLocationRelativeTo(null);
        }
        addComponentListener(new ComponentAdapter() {
            @Override public void componentMoved(ComponentEvent e) { remember(); }
            @Override public void componentResized(ComponentEvent e) { remember(); }
            private void remember() {
                if (isShowing() && (getExtendedState() & MAXIMIZED_BOTH) == 0) Prefs.saveWindowBounds(getBounds());
            }
        });
        cards.show(getContentPane(), "start");
    }

    /** A remembered position is only reused if it is (partly) visible on some current screen. */
    private static boolean fitsOnAScreen(Rectangle r) {
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle screen = gd.getDefaultConfiguration().getBounds();
            Rectangle visible = screen.intersection(r);
            if (visible.width >= 200 && visible.height >= 100) return true;
        }
        return false;
    }

    // ---- local games ----

    @Override
    public void startGame(GameConfig config) {
        Prefs.saveLastConfig(config);
        launch(config, null, null, null);
    }

    private void resumeGame(SavedGame saved) { launch(saved.config(), saved, null, null); }

    private void launch(GameConfig config, SavedGame saved, String whiteName, String blackName) {
        disposeCurrentGame();
        GamePanel panel;
        try {
            panel = new GamePanel(config, saved, this, config.mode() == GameConfig.Mode.ONLINE ? client : null,
                    whiteName, blackName);
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, "Cannot resume this game:\n" + ex.getMessage(),
                    "Resume game", JOptionPane.ERROR_MESSAGE);
            cards.show(getContentPane(), "start");
            return;
        }
        currentGame = panel;
        add(currentGame, "game");
        cards.show(getContentPane(), "game");
        currentGame.startGame();
    }

    @Override
    public void newGame() {
        disposeCurrentGame();
        closeOnline();
        cards.show(getContentPane(), "start");
    }

    private void disposeCurrentGame() {
        if (currentGame != null) {
            currentGame.dispose();
            remove(currentGame);
            currentGame = null;
        }
    }

    // ---- online games ----

    private void startOnline(StartScreen.OnlineRequest req) {
        Prefs.saveLastConfig(new GameConfig(GameConfig.Mode.ONLINE, Pieces.WHITE, req.minutes(), 1, GameConfig.NO_UNDO));
        Prefs.saveOnline(req.name(), req.address());
        closeOnline();

        String host;
        int port;
        try {
            String addr = req.address().trim();
            int colon = addr.lastIndexOf(':');
            host = colon < 0 ? addr : addr.substring(0, colon);
            port = colon < 0 ? Protocol.DEFAULT_PORT : Integer.parseInt(addr.substring(colon + 1).trim());
            if (host.isEmpty()) host = "localhost";
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Please enter the server as host:port, e.g. 192.168.1.10:5000",
                    "Online game", JOptionPane.ERROR_MESSAGE);
            return;
        }

        lobby = new OnlineLobbyPanel(this::cancelOnline);
        add(lobby, "lobby");
        cards.show(getContentPane(), "lobby");

        if (req.host()) {
            try {
                server = new ChessServer(port, true);
                server.start();
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Cannot open port " + port + ":\n" + e.getMessage(),
                        "Host game", JOptionPane.ERROR_MESSAGE);
                cancelOnline();
                return;
            }
            lobby.setShareInfo(localAddresses(), server.port());
            lobby.setStatus("Waiting for an opponent to join…");
            host = "127.0.0.1";
        } else {
            lobby.setStatus("Connecting to " + host + ":" + port + "…");
        }

        final String connectHost = host;
        final int connectPort = port;
        new SwingWorker<ChessClient, Void>() {
            @Override
            protected ChessClient doInBackground() throws IOException {
                return ChessClient.connect(connectHost, connectPort, req.name(), req.minutes(), CONNECT_TIMEOUT_MS);
            }

            @Override
            protected void done() {
                if (lobby == null) return;   // cancelled meanwhile
                try {
                    client = get();
                } catch (Exception e) {
                    String why = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    JOptionPane.showMessageDialog(MainFrame.this,
                            "Cannot connect to " + connectHost + ":" + connectPort + ":\n" + why,
                            "Online game", JOptionPane.ERROR_MESSAGE);
                    cancelOnline();
                    return;
                }
                client.setListener(new ChessClient.Listener() {
                    @Override public void onMessage(Message m) { onOnlineMessage(m); }
                    @Override public void onDisconnected(String reason) { onOnlineDisconnected(reason); }
                });
                if (!req.host()) lobby.setStatus("Connected — waiting for an opponent…");
            }
        }.execute();
    }

    /** START goes to a fresh GamePanel (first game or rematch); everything else to the current one. */
    private void onOnlineMessage(Message m) {
        if (client == null) return;
        if (m.type().equals(Protocol.START)) {
            int color = "WHITE".equals(m.arg(0)) ? Pieces.WHITE : Pieces.BLACK;
            int minutes;
            try { minutes = Integer.parseInt(m.arg(1)); } catch (RuntimeException e) { minutes = 0; }
            GameConfig config = new GameConfig(GameConfig.Mode.ONLINE, color, minutes, 1, GameConfig.NO_UNDO);
            if (lobby != null) {
                remove(lobby);
                lobby = null;
            }
            launch(config, null, m.arg(2), m.arg(3));
            return;
        }
        if (currentGame != null) {
            currentGame.onOnlineMessage(m);
        } else if (m.type().equals(Protocol.ERROR)) {
            JOptionPane.showMessageDialog(this, "Server: " + String.join(" ", m.args()),
                    "Online game", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onOnlineDisconnected(String reason) {
        if (currentGame != null) {
            currentGame.onOnlineDisconnected(reason);
        } else if (lobby != null) {
            JOptionPane.showMessageDialog(this, "Connection lost: " + reason, "Online game", JOptionPane.ERROR_MESSAGE);
            cancelOnline();
        }
    }

    private void cancelOnline() {
        closeOnline();
        cards.show(getContentPane(), "start");
    }

    private void closeOnline() {
        if (lobby != null) {
            remove(lobby);
            lobby = null;
        }
        if (client != null) {
            client.close();
            client = null;
        }
        if (server != null) {
            server.close();
            server = null;
        }
    }

    /** IPv4 addresses of this machine's active, non-loopback interfaces. */
    private static List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                    if (a instanceof Inet4Address) out.add(a.getHostAddress());
                }
            }
        } catch (IOException ignored) { }
        if (out.isEmpty()) out.add("127.0.0.1");
        return out;
    }
}

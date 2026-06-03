package net;

import engine.Move;
import engine.Pieces;
import game.GameSession;
import net.Protocol.Message;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static net.Protocol.*;

/**
 * Relay + referee for online games. Clients arrive, say HELLO, and are
 * paired in order of arrival into a {@link Room}; any number of rooms can
 * coexist. Each room keeps its own {@link GameSession}: a move is relayed
 * only if it is legal and from the side to move, so two honest clients can
 * never drift apart and a tampered one cannot cheat by sending nonsense.
 *
 * Threads: one accept thread, one reader thread per client. Room state is
 * guarded by the room's monitor; the waiting slot by the server's. Writes
 * to a socket are serialised on its writer. Every thread is a daemon when
 * the server is embedded in the GUI, so closing the window ends it.
 */
public final class ChessServer implements Closeable {

    /** A silent client is dropped after this long (clients ping every few seconds). */
    static final int READ_TIMEOUT_MS = 30_000;

    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private final boolean daemon;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private Client waiting;   // guarded by this

    /**
     * @param port   TCP port, 0 for an ephemeral one (see {@link #port()})
     * @param daemon true when embedded in a GUI (threads must not keep the JVM alive)
     */
    public ChessServer(int port, boolean daemon) throws IOException {
        this.serverSocket = new ServerSocket(port);
        this.daemon = daemon;
        this.acceptThread = new Thread(this::acceptLoop, "chess-server-accept");
        acceptThread.setDaemon(daemon);
    }

    public void start() { acceptThread.start(); }

    public int port() { return serverSocket.getLocalPort(); }

    public int connectedClients() { return clients.size(); }

    @Override
    public void close() {
        closed = true;
        try { serverSocket.close(); } catch (IOException ignored) { }
        for (Client c : clients) c.close();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket s = serverSocket.accept();
                s.setTcpNoDelay(true);
                s.setSoTimeout(READ_TIMEOUT_MS);
                Client c = new Client(s);
                clients.add(c);
                Thread t = new Thread(c::readLoop, "chess-server-client");
                t.setDaemon(daemon);
                t.start();
            } catch (IOException e) {
                if (!closed) System.err.println("chess server: accept failed: " + e.getMessage());
            }
        }
    }

    // ---- per-connection state ----

    private final class Client {
        final Socket socket;
        final BufferedReader in;
        final PrintWriter out;
        volatile String name = "?";
        volatile int minutes;
        volatile boolean greeted;
        volatile Room room;
        volatile int color;

        Client(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        }

        void send(Message m) {
            synchronized (out) { out.println(m.line()); }
        }

        void readLoop() {
            try {
                String line;
                while ((line = in.readLine()) != null) handle(line);
            } catch (IOException ignored) {
                // timeout or reset: fall through to disconnect
            } finally {
                disconnect();
            }
        }

        private void handle(String line) {
            Message m;
            try {
                m = Message.parse(line);
            } catch (IllegalArgumentException e) {
                return;
            }
            switch (m.type()) {
                case PING -> send(Message.of(PONG));
                case HELLO -> hello(this, m);
                default -> {
                    Room r = room;
                    if (r == null) send(Message.of(ERROR, "not", "in", "a", "game"));
                    else r.handle(this, m);
                }
            }
        }

        private void disconnect() {
            clients.remove(this);
            synchronized (ChessServer.this) { if (waiting == this) waiting = null; }
            Room r = room;
            if (r != null) r.leave(this);
            close();
        }

        void close() {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private void hello(Client c, Message m) {
        if (c.greeted) { c.send(Message.of(ERROR, "already", "greeted")); return; }
        int version;
        try { version = Integer.parseInt(m.arg(0)); } catch (RuntimeException e) { version = -1; }
        if (version != VERSION) {
            c.send(Message.of(ERROR, "unsupported", "protocol", "version"));
            c.close();
            return;
        }
        int minutes;
        try { minutes = Integer.parseInt(m.arg(1)); } catch (RuntimeException e) { minutes = 0; }
        c.minutes = Math.max(0, Math.min(minutes, 180));
        c.name = sanitizeName(m.arg(2));
        c.greeted = true;
        c.send(Message.of(WELCOME));

        Client partner = null;
        synchronized (this) {
            if (waiting == null || waiting.socket.isClosed()) waiting = c;
            else { partner = waiting; waiting = null; }
        }
        if (partner != null) new Room(partner, c).start();
    }

    // ---- a game between two clients ----

    private static final class Room {
        private final Client[] players = new Client[2];   // index = colour
        private final int minutes;
        private final boolean[] wantsRematch = new boolean[2];
        private GameSession session;
        private boolean over;

        Room(Client first, Client second) {
            players[Pieces.WHITE] = first;
            players[Pieces.BLACK] = second;
            minutes = first.minutes;   // the first arrival's time control wins
            first.room = this;
            second.room = this;
        }

        synchronized void start() {
            session = new GameSession(engine.Board.startPosition(), false);
            over = false;
            wantsRematch[0] = wantsRematch[1] = false;
            for (int color = 0; color < 2; color++) {
                players[color].color = color;
                players[color].send(Message.of(START, color == Pieces.WHITE ? "WHITE" : "BLACK",
                        String.valueOf(minutes), players[Pieces.WHITE].name, players[Pieces.BLACK].name));
            }
        }

        synchronized void handle(Client c, Message m) {
            Client opp = players[c.color ^ 1];
            if (opp == null) { c.send(Message.of(ERROR, "opponent", "left")); return; }
            switch (m.type()) {
                case MOVE -> {
                    if (over) { c.send(Message.of(ERROR, "game", "over")); return; }
                    if (session.sideToMove() != c.color) { c.send(Message.of(ERROR, "not", "your", "turn")); return; }
                    Move mv = null;
                    for (Move legal : session.legalMoves()) if (legal.toString().equals(m.arg(0))) { mv = legal; break; }
                    if (mv == null) { c.send(Message.of(ERROR, "illegal", "move", String.valueOf(m.arg(0)))); return; }
                    session.applyMove(mv);
                    if (session.result().isOver()) over = true;
                    opp.send(Message.of(MOVE, mv.toString()));
                }
                case RESIGN -> {
                    if (!over) { over = true; session.resign(c.color); }
                    opp.send(Message.of(RESIGN));
                }
                case DRAW_OFFER, DRAW_DECLINE -> opp.send(Message.of(m.type()));
                case DRAW_ACCEPT -> {
                    if (!over) { over = true; session.agreeDraw(); }
                    opp.send(Message.of(DRAW_ACCEPT));
                }
                case TIMEOUT -> {
                    if (!over) { over = true; session.timeout(c.color); }
                    opp.send(Message.of(TIMEOUT));
                }
                case REMATCH -> {
                    wantsRematch[c.color] = true;
                    opp.send(Message.of(REMATCH));
                    if (wantsRematch[0] && wantsRematch[1]) {
                        Client w = players[Pieces.WHITE];
                        players[Pieces.WHITE] = players[Pieces.BLACK];
                        players[Pieces.BLACK] = w;
                        start();
                    }
                }
                default -> c.send(Message.of(ERROR, "unknown", "message", m.type()));
            }
        }

        synchronized void leave(Client c) {
            Client opp = players[c.color ^ 1];
            players[c.color] = null;
            c.room = null;
            over = true;
            if (opp != null) {
                opp.send(Message.of(OPPONENT_LEFT));
                opp.room = null;
                players[c.color ^ 1] = null;
            }
        }
    }
}

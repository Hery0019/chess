package net;

import engine.Move;
import engine.Pieces;
import game.GameSession;
import net.Protocol.Message;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * One port serves two kinds of client. A connection whose first bytes are
 * a protocol line ({@code HELLO ...}) is the Java app; one that starts
 * with an HTTP request is a browser: {@code GET /} is answered with the
 * web client (an HTML page embedded in the jar), {@code GET /ws} is
 * upgraded to a WebSocket over which the very same protocol lines travel
 * as text frames. So the address to share is the same for both, with or
 * without {@code http://}.
 *
 * Threads: one accept thread, one reader thread per client. Room state is
 * guarded by the room's monitor; the waiting slot by the server's. Writes
 * to a connection are serialised on its transport. Every thread is a
 * daemon when the server is embedded in the GUI, so closing the window
 * ends it.
 */
public final class ChessServer implements Closeable {

    /** A silent client is dropped after this long (clients ping every few seconds). */
    static final int READ_TIMEOUT_MS = 30_000;
    /** Classpath location of the browser client. */
    static final String WEB_PAGE = "/web/index.html";

    /** One protocol line in, one out — over a raw socket or WebSocket frames. */
    interface Transport extends Closeable {
        /** Next message, or null at end of stream. */
        String read() throws IOException;
        void write(String line) throws IOException;
    }

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
                Thread t = new Thread(() -> serve(s), "chess-server-client");
                t.setDaemon(daemon);
                t.start();
            } catch (IOException e) {
                if (!closed) System.err.println("chess server: accept failed: " + e.getMessage());
            }
        }
    }

    /** Sniffs the first bytes to tell a browser from the Java app, then runs the client. */
    private void serve(Socket s) {
        Client c = null;
        try {
            BufferedInputStream in = new BufferedInputStream(s.getInputStream());
            in.mark(8);
            byte[] head = in.readNBytes(4);
            in.reset();
            String start = new String(head, StandardCharsets.ISO_8859_1);
            Transport t;
            if (start.equals("GET ") || start.startsWith("HEAD") || start.startsWith("POST") || start.startsWith("OPTI")) {
                t = Http.serve(in, s.getOutputStream());
                if (t == null) { s.close(); return; }          // a page was served; nothing more to do
            } else {
                t = new LineTransport(in, s.getOutputStream());
            }
            c = new Client(s, t);
            clients.add(c);
            c.readLoop();
        } catch (IOException e) {
            if (c == null) { try { s.close(); } catch (IOException ignored) { } }
        }
    }

    // ---- transports ----

    /** The Java app: newline-terminated UTF-8 lines. */
    private static final class LineTransport implements Transport {
        private final BufferedReader in;
        private final PrintWriter out;

        LineTransport(InputStream in, OutputStream out) {
            this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            this.out = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true);
        }

        @Override public String read() throws IOException { return in.readLine(); }
        @Override public void write(String line) { synchronized (out) { out.println(line); } }
        @Override public void close() { }
    }

    /** Minimal HTTP: the web page, and the WebSocket upgrade for {@code /ws}. */
    private static final class Http {
        private static volatile byte[] page;

        /** @return a WebSocket transport after a successful upgrade, or null once a response was written */
        static Transport serve(BufferedInputStream in, OutputStream out) throws IOException {
            String requestLine = readLine(in);
            if (requestLine == null) return null;
            String[] parts = requestLine.split(" ");
            String method = parts[0], path = parts.length > 1 ? parts[1] : "/";
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            if (path.equals("/ws")) {
                String key = headers.get("sec-websocket-key");
                if (!method.equals("GET") || key == null
                        || !headers.getOrDefault("upgrade", "").equalsIgnoreCase("websocket")) {
                    respond(out, 400, "text/plain", "WebSocket upgrade expected".getBytes(StandardCharsets.UTF_8));
                    return null;
                }
                String response = "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + WebSocket.acceptKey(key) + "\r\n\r\n";
                out.write(response.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                return new WebSocket.Transport(in, out);
            }
            if (!method.equals("GET") && !method.equals("HEAD")) {
                respond(out, 405, "text/plain", "GET only".getBytes(StandardCharsets.UTF_8));
                return null;
            }
            if (path.equals("/") || path.equals("/index.html")) {
                byte[] body = page();
                if (body == null) respond(out, 500, "text/plain", "web client not packaged in this build".getBytes(StandardCharsets.UTF_8));
                else respond(out, 200, "text/html; charset=utf-8", method.equals("HEAD") ? new byte[0] : body);
                return null;
            }
            respond(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return null;
        }

        private static byte[] page() {
            byte[] p = page;
            if (p != null) return p;
            try (InputStream res = ChessServer.class.getResourceAsStream(WEB_PAGE)) {
                if (res == null) return null;
                page = p = res.readAllBytes();
                return p;
            } catch (IOException e) {
                return null;
            }
        }

        private static void respond(OutputStream out, int status, String type, byte[] body) throws IOException {
            String reason = switch (status) { case 200 -> "OK"; case 400 -> "Bad Request"; case 404 -> "Not Found";
                                              case 405 -> "Method Not Allowed"; default -> "Error"; };
            String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                    + "Content-Type: " + type + "\r\nContent-Length: " + body.length + "\r\n"
                    + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
        }

        /** One CRLF-terminated header line (ISO-8859-1, as HTTP headers are); null at end of stream. */
        private static String readLine(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = in.read()) >= 0) {
                if (b == '\n') break;
                if (b != '\r') sb.append((char) b);
                if (sb.length() > 8192) throw new IOException("header line too long");
            }
            return b < 0 && sb.length() == 0 ? null : sb.toString();
        }
    }

    // ---- per-connection state ----

    private final class Client {
        final Socket socket;
        final Transport transport;
        volatile String name = "?";
        volatile int minutes;
        volatile boolean greeted;
        volatile Room room;
        volatile int color;

        Client(Socket socket, Transport transport) {
            this.socket = socket;
            this.transport = transport;
        }

        void send(Message m) {
            try {
                transport.write(m.line());
            } catch (IOException e) {
                close();
            }
        }

        void readLoop() {
            try {
                String line;
                while ((line = transport.read()) != null) handle(line);
            } catch (IOException ignored) {
                // timeout, reset or a bad frame: fall through to disconnect
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
            try { transport.close(); } catch (IOException ignored) { }
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
                    opp.send(Message.of(MOVE, mv.toString()));
                    if (session.result().isOver()) finish();
                }
                case LEGAL -> {
                    // The browser client keeps no chess rules: it asks for the moves it may play.
                    List<String> lan = new ArrayList<>();
                    if (!over && session.sideToMove() == c.color) for (Move legal : session.legalMoves()) lan.add(legal.toString());
                    c.send(new Message(LEGAL, lan));
                }
                case RESIGN -> {
                    opp.send(Message.of(RESIGN));
                    if (!over) { session.resign(c.color); finish(); }
                }
                case DRAW_OFFER, DRAW_DECLINE -> opp.send(Message.of(m.type()));
                case DRAW_ACCEPT -> {
                    opp.send(Message.of(DRAW_ACCEPT));
                    if (!over) { session.agreeDraw(); finish(); }
                }
                case TIMEOUT -> {
                    opp.send(Message.of(TIMEOUT));
                    if (!over) { session.timeout(c.color); finish(); }
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

        /** The game just ended in the referee's session: tell both sides how. */
        private void finish() {
            over = true;
            List<String> args = new ArrayList<>();
            args.add(session.result().pgnToken());
            args.addAll(List.of(session.result().message().split("\\s+")));
            Message result = new Message(RESULT, args);
            for (Client p : players) if (p != null) p.send(result);
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

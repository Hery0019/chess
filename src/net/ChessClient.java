package net;

import engine.Move;
import net.Protocol.Message;

import javax.swing.SwingUtilities;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static net.Protocol.*;

/**
 * Connection to a {@link ChessServer}. A reader thread parses incoming
 * lines and hands them to the {@link Listener} <em>on the EDT</em>, so UI
 * code needs no synchronisation; messages that arrive before a listener is
 * installed are queued and replayed when it is. A daemon scheduler sends a
 * PING every few seconds so the server's idle timeout never fires on a
 * live connection.
 */
public final class ChessClient implements Closeable {

    public interface Listener {
        void onMessage(Message m);
        /** The connection is gone (server closed it, network error, or {@link #close()}). */
        void onDisconnected(String reason);
    }

    private static final int PING_PERIOD_SECONDS = 5;

    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;
    private final ScheduledExecutorService pinger;
    private final Deque<Consumer<Listener>> pending = new ArrayDeque<>();   // EDT-confined
    private Listener listener;                                              // EDT-confined
    private volatile boolean closed;

    /**
     * Connects and sends HELLO. Blocks for the TCP handshake only; call it
     * off the EDT for a remote host.
     */
    public static ChessClient connect(String host, int port, String name, int minutes, int timeoutMs) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        s.setTcpNoDelay(true);
        ChessClient c = new ChessClient(s);
        c.send(Message.of(HELLO, String.valueOf(VERSION), String.valueOf(minutes), sanitizeName(name)));
        return c;
    }

    private ChessClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        Thread reader = new Thread(this::readLoop, "chess-client-reader");
        reader.setDaemon(true);
        reader.start();
        pinger = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chess-client-ping");
            t.setDaemon(true);
            return t;
        });
        pinger.scheduleAtFixedRate(() -> send(Message.of(PING)), PING_PERIOD_SECONDS, PING_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    /** Installs (or replaces) the listener; queued messages are delivered at once. Call on the EDT. */
    public void setListener(Listener l) {
        listener = l;
        if (l == null) return;
        while (!pending.isEmpty()) pending.poll().accept(l);
    }

    public boolean isOpen() { return !closed; }

    public void send(Message m) {
        if (closed) return;
        synchronized (out) { out.println(m.line()); }
    }

    public void sendMove(Move m)      { send(Message.of(MOVE, m.toString())); }
    public void resign()              { send(Message.of(RESIGN)); }
    public void offerDraw()           { send(Message.of(DRAW_OFFER)); }
    public void acceptDraw()          { send(Message.of(DRAW_ACCEPT)); }
    public void declineDraw()         { send(Message.of(DRAW_DECLINE)); }
    public void reportTimeout()       { send(Message.of(TIMEOUT)); }
    public void requestRematch()      { send(Message.of(REMATCH)); }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        pinger.shutdownNow();
        try { socket.close(); } catch (IOException ignored) { }
    }

    private void readLoop() {
        String reason;
        try {
            String line;
            while ((line = in.readLine()) != null) {
                Message m;
                try { m = Message.parse(line); } catch (IllegalArgumentException e) { continue; }
                if (m.type().equals(PONG)) continue;
                deliver(l -> l.onMessage(m));
            }
            reason = "connection closed by the server";
        } catch (IOException e) {
            reason = closed ? "closed" : "connection lost (" + e.getMessage() + ")";
        }
        boolean wasClosedLocally = closed;
        close();
        if (!wasClosedLocally) {
            String why = reason;
            deliver(l -> l.onDisconnected(why));
        }
    }

    private void deliver(Consumer<Listener> action) {
        SwingUtilities.invokeLater(() -> {
            if (listener != null) action.accept(listener);
            else pending.add(action);
        });
    }
}

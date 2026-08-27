package net;

import java.util.Arrays;
import java.util.List;

/**
 * Wire protocol between {@link ChessClient} and {@link ChessServer}: one
 * UTF-8 text line per message, {@code TYPE arg arg ...}, space separated.
 * Player names are sanitised to single tokens so the split is unambiguous.
 *
 * <pre>
 * client -> server                       server -> client
 *   HELLO 1 &lt;minutes&gt; &lt;name&gt;          WELCOME                (paired when a second player arrives)
 *   MOVE e2e4 | e7e8q                     START WHITE|BLACK &lt;minutes&gt; &lt;whiteName&gt; &lt;blackName&gt;
 *   RESIGN                                MOVE ...               (the opponent's move, validated)
 *   DRAW_OFFER / DRAW_ACCEPT / DRAW_DECLINE   RESIGN / DRAW_* / TIMEOUT / REMATCH   (from the opponent)
 *   TIMEOUT      (my own flag fell)       OPPONENT_LEFT
 *   REMATCH                               ERROR &lt;text...&gt;
 *   PING                                  PONG
 *   LEGAL        (my legal moves?)        LEGAL e2e4 e2e3 ...    (empty when it is not my turn)
 *                                         RESULT 1-0|0-1|1/2-1/2 &lt;text...&gt;   (the game just ended)
 * </pre>
 *
 * LEGAL and RESULT exist for the browser client, which keeps no chess
 * rules of its own; the Java client never asks and ignores RESULT (it
 * adjudicates locally). The same lines travel as WebSocket text frames
 * for browsers — see {@link ChessServer}.
 *
 * The server is authoritative: it keeps its own game per room, rejects a
 * move that is illegal or out of turn with ERROR, and only relays moves it
 * accepted. Both clients still adjudicate mate/stalemate/draw rules
 * locally from the identical move sequence. A rematch starts when both
 * players asked for one; colours are swapped.
 */
public final class Protocol {

    public static final int VERSION = 1;
    public static final int DEFAULT_PORT = 5000;
    public static final int MAX_NAME_LENGTH = 16;

    // client -> server
    public static final String HELLO = "HELLO", MOVE = "MOVE", RESIGN = "RESIGN",
            DRAW_OFFER = "DRAW_OFFER", DRAW_ACCEPT = "DRAW_ACCEPT", DRAW_DECLINE = "DRAW_DECLINE",
            TIMEOUT = "TIMEOUT", REMATCH = "REMATCH", PING = "PING", LEGAL = "LEGAL";
    // server -> client
    public static final String WELCOME = "WELCOME", START = "START", OPPONENT_LEFT = "OPPONENT_LEFT",
            ERROR = "ERROR", PONG = "PONG", RESULT = "RESULT";

    private Protocol() {}

    /** One parsed line. */
    public record Message(String type, List<String> args) {

        public Message {
            args = List.copyOf(args);
        }

        public static Message of(String type, String... args) { return new Message(type, List.of(args)); }

        /** Argument {@code i}, or null when absent. */
        public String arg(int i) { return i < args.size() ? args.get(i) : null; }

        public String line() { return args.isEmpty() ? type : type + " " + String.join(" ", args); }

        /** @throws IllegalArgumentException on an empty line */
        public static Message parse(String line) {
            String[] t = line.trim().split("\\s+");
            if (t.length == 0 || t[0].isEmpty()) throw new IllegalArgumentException("empty message");
            return new Message(t[0], Arrays.asList(t).subList(1, t.length));
        }
    }

    /** Letters, digits, '_' and '-', at most {@value #MAX_NAME_LENGTH} chars, never empty. */
    public static String sanitizeName(String raw) {
        String s = raw == null ? "" : raw.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        if (s.length() > MAX_NAME_LENGTH) s = s.substring(0, MAX_NAME_LENGTH);
        return s.isEmpty() ? "Player" : s;
    }
}

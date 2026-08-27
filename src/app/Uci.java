package app;

import engine.Board;
import engine.Move;
import engine.MoveGenerator;
import engine.OpeningBook;
import engine.Pieces;
import engine.Search;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The engine as a UCI process, so it can be plugged into any chess GUI
 * (Arena, CuteChess, Banksia, Lichess bots...) and matched against other
 * engines for a real Elo figure — {@code test.Arena} only measures the
 * engine against itself.
 *
 * <pre>java -cp chess.jar app.Uci</pre>
 *
 * Supported: {@code uci}, {@code isready}, {@code ucinewgame},
 * {@code setoption name Hash|OwnBook}, {@code position startpos|fen ...
 * [moves ...]}, {@code go [depth N] [movetime MS] [wtime MS] [btime MS]
 * [winc MS] [binc MS] [infinite]}, {@code stop}, {@code quit}. Searches run
 * on their own thread so {@code stop} is honoured; each completed iteration
 * is reported as an {@code info} line and the last one answers a stop.
 * Time management with a clock: about 1/30 of the remaining time plus half
 * the increment, never more than half of what is left.
 */
public final class Uci {

    private static final String NAME = "Chess (Java Swing) v3";
    private static final int MAX_DEPTH = 64;

    private final BufferedReader in;
    private final PrintWriter out;
    private final MoveGenerator gen = new MoveGenerator();

    private int hashMb = 16;
    private boolean ownBook = true;
    private Search search = new Search(bitsFor(16));
    private Board board = Board.startPosition();
    private long[] priorKeys = new long[0];

    private Thread searching;
    private AtomicBoolean cancel = new AtomicBoolean(false);

    public Uci(BufferedReader in, PrintWriter out) {
        this.in = in;
        this.out = out;
    }

    public static void main(String[] args) throws Exception {
        new Uci(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                new PrintWriter(System.out, true, StandardCharsets.UTF_8)).run();
    }

    /** Reads commands until {@code quit} or end of input. */
    public void run() throws Exception {
        String line;
        while ((line = in.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty()) continue;
            String[] t = line.split("\\s+");
            switch (t[0]) {
                case "uci" -> {
                    send("id name " + NAME);
                    send("id author Hery");
                    send("option name Hash type spin default 16 min 1 max 1024");
                    send("option name OwnBook type check default true");
                    send("uciok");
                }
                case "isready" -> send("readyok");
                case "setoption" -> setOption(t);
                case "ucinewgame" -> { stopSearch(); search = new Search(bitsFor(hashMb)); }
                case "position" -> { stopSearch(); position(t); }
                case "go" -> { stopSearch(); go(t); }
                case "stop" -> stopSearch();
                case "quit" -> { stopSearch(); return; }
                default -> { }   // unknown commands are ignored, as the protocol asks
            }
        }
        stopSearch();
    }

    private void send(String s) {
        out.println(s);
        out.flush();
    }

    // ---- options ----

    private void setOption(String[] t) {
        String name = null, value = null;
        for (int i = 1; i < t.length; i++) {
            if (t[i].equals("name") && i + 1 < t.length) name = t[++i];
            else if (t[i].equals("value") && i + 1 < t.length) value = t[++i];
        }
        if (name == null || value == null) return;
        switch (name.toLowerCase(Locale.ROOT)) {
            case "hash" -> {
                try { hashMb = Math.max(1, Math.min(1024, Integer.parseInt(value))); } catch (NumberFormatException ignored) { return; }
                stopSearch();
                search = new Search(bitsFor(hashMb));
            }
            case "ownbook" -> ownBook = Boolean.parseBoolean(value);
            default -> { }
        }
    }

    /** Table size in bits for a megabyte budget; an entry is about 24 bytes. */
    private static int bitsFor(int mb) {
        long entries = mb * 1_048_576L / 24;
        int bits = 63 - Long.numberOfLeadingZeros(entries);
        return Math.max(10, Math.min(24, bits));
    }

    // ---- position ----

    private void position(String[] t) {
        int i = 1;
        Board b;
        if (i < t.length && t[i].equals("startpos")) {
            b = Board.startPosition();
            i++;
        } else if (i < t.length && t[i].equals("fen")) {
            StringBuilder fen = new StringBuilder();
            for (i++; i < t.length && !t[i].equals("moves"); i++) fen.append(t[i]).append(' ');
            try {
                b = Board.fromFen(fen.toString());
            } catch (RuntimeException e) {
                send("info string bad fen: " + fen.toString().strip());
                return;
            }
        } else {
            return;
        }
        List<Long> keys = new ArrayList<>();
        if (i < t.length && t[i].equals("moves")) {
            for (i++; i < t.length; i++) {
                Move m = find(b, t[i]);
                if (m == null) { send("info string illegal move " + t[i]); break; }
                keys.add(b.zobristKey());
                b.makeMove(m, new engine.Undo());
            }
        }
        board = b;
        priorKeys = new long[keys.size()];
        for (int k = 0; k < priorKeys.length; k++) priorKeys[k] = keys.get(k);
    }

    private Move find(Board b, String lan) {
        for (Move m : gen.generateLegal(b)) if (m.toString().equals(lan)) return m;
        return null;
    }

    // ---- go / stop ----

    private void go(String[] t) {
        int depth = MAX_DEPTH;
        long movetime = 0, wtime = -1, btime = -1, winc = 0, binc = 0;
        boolean infinite = false;
        for (int i = 1; i < t.length; i++) {
            String k = t[i];
            long v = i + 1 < t.length ? parseLong(t[i + 1]) : -1;
            switch (k) {
                case "depth" -> { depth = (int) Math.max(1, Math.min(MAX_DEPTH, v)); i++; }
                case "movetime" -> { movetime = v; i++; }
                case "wtime" -> { wtime = v; i++; }
                case "btime" -> { btime = v; i++; }
                case "winc" -> { winc = v; i++; }
                case "binc" -> { binc = v; i++; }
                case "infinite" -> infinite = true;
                default -> { }   // searchmoves, ponder, nodes, mate, movestogo: not supported, skipped
            }
        }
        long budget = 0;
        if (!infinite) {
            if (movetime > 0) {
                budget = Math.max(10, movetime - 30);
            } else {
                long remaining = board.sideToMove() == Pieces.WHITE ? wtime : btime;
                long inc = board.sideToMove() == Pieces.WHITE ? winc : binc;
                if (remaining >= 0) {
                    budget = remaining / 30 + inc / 2;
                    budget = Math.min(budget, remaining / 2);
                    budget = Math.max(10, budget - 30);   // margin for process and GUI overhead
                }
            }
        }
        final Board snapshot = board.copy();
        final long[] keys = priorKeys.clone();
        final int fDepth = depth;
        final long fBudget = budget;
        cancel = new AtomicBoolean(false);
        final AtomicBoolean flag = cancel;
        searching = new Thread(() -> think(snapshot, keys, fDepth, fBudget, flag), "uci-search");
        searching.setDaemon(true);
        searching.start();
    }

    private void think(Board b, long[] keys, int depth, long budget, AtomicBoolean flag) {
        Move book = ownBook ? OpeningBook.probe(b) : null;
        if (book != null) {
            send("info depth 0 score cp 0 nodes 0 time 0 pv " + book + " string book move");
            send("bestmove " + book);
            return;
        }
        final Search.Result[] last = new Search.Result[1];
        search.setIterationListener(r -> {
            last[0] = r;
            send(info(r));
        });
        Search.Result r;
        try {
            r = search.findBest(b, depth, budget, keys, flag);
        } finally {
            search.setIterationListener(null);
        }
        if (r == null) r = last[0];                                    // stopped: last completed iteration
        if (r == null || r.bestMove() == null) {
            // Nothing completed (stopped at once) or a terminal position: depth 1 is instant.
            r = search.findBest(b, 1, 0, keys, new AtomicBoolean(false));
        }
        send("bestmove " + (r != null && r.bestMove() != null ? r.bestMove() : "0000"));
    }

    private static String info(Search.Result r) {
        StringBuilder sb = new StringBuilder("info depth ").append(r.depth());
        if (r.isMate()) sb.append(" score mate ").append(r.mateIn());
        else sb.append(" score cp ").append(r.score());
        sb.append(" nodes ").append(r.nodes()).append(" time ").append(r.millis());
        if (r.millis() > 0) sb.append(" nps ").append(r.nodes() * 1000 / r.millis());
        if (!r.pv().isEmpty()) {
            sb.append(" pv");
            for (Move m : r.pv()) sb.append(' ').append(m);
        }
        return sb.toString();
    }

    private void stopSearch() {
        Thread t = searching;
        if (t == null) return;
        cancel.set(true);
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        searching = null;
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return -1; }
    }
}

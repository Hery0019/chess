package test;

import app.Uci;

import java.io.BufferedReader;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives {@link Uci} through a pipe the way a GUI would: handshake,
 * position setup, fixed-depth and timed searches, stop, and the bestmove
 * / info line format. Plain main-class runner; exit code != 0 on failure.
 */
public final class UciTests {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        handshake();
        searches();
        stopAndQuit();
        if (failures > 0) {
            System.err.println(failures + " test(s) FAILED.");
            System.exit(1);
        }
        System.out.println("All UCI tests passed.");
    }

    /** One engine session: commands go in through a pipe, output is collected as lines. */
    private static final class Session implements AutoCloseable {
        final PipedWriter toEngine = new PipedWriter();
        final StringWriter output = new StringWriter();
        final Thread thread;
        Throwable failure;

        Session() throws Exception {
            BufferedReader reader = new BufferedReader(new PipedReader(toEngine));
            Uci uci = new Uci(reader, new PrintWriter(output, true));
            thread = new Thread(() -> {
                try { uci.run(); } catch (Throwable t) { failure = t; }
            }, "uci-under-test");
            thread.start();
        }

        void send(String line) throws java.io.IOException {
            toEngine.write(line + "\n");
            toEngine.flush();
        }

        /** Waits until a line starting with {@code prefix} appears (or 10 s pass); returns it or null. */
        String await(String prefix) throws Exception {
            for (int i = 0; i < 200; i++) {
                for (String l : lines()) if (l.startsWith(prefix)) return l;
                Thread.sleep(50);
            }
            return null;
        }

        List<String> lines() {
            List<String> out = new ArrayList<>();
            for (String l : output.toString().split("\\R")) if (!l.isBlank()) out.add(l);
            return out;
        }

        void clear() { output.getBuffer().setLength(0); }

        @Override public void close() throws java.io.IOException {
            send("quit");
            try {
                thread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            toEngine.close();
        }
    }

    private static void handshake() throws Exception {
        try (Session s = new Session()) {
            s.send("uci");
            check("uci: handshake ends with uciok", s.await("uciok") != null);
            List<String> lines = s.lines();
            check("uci: id and options announced",
                    lines.stream().anyMatch(l -> l.startsWith("id name "))
                    && lines.stream().anyMatch(l -> l.startsWith("option name Hash "))
                    && lines.stream().anyMatch(l -> l.startsWith("option name OwnBook ")));
            s.send("isready");
            check("uci: isready -> readyok", s.await("readyok") != null);
            s.send("setoption name Hash value 8");
            s.send("setoption name OwnBook value false");
            s.send("isready");
            check("uci: options accepted", s.await("readyok") != null && s.failure == null);
        }
    }

    private static void searches() throws Exception {
        try (Session s = new Session()) {
            s.send("uci");
            s.await("uciok");
            s.send("setoption name OwnBook value false");
            s.clear();

            // Fixed depth from the start position: info per iteration, then a legal bestmove.
            s.send("position startpos moves e2e4 e7e5");
            s.send("go depth 4");
            String best = s.await("bestmove");
            check("uci: bestmove after go depth", best != null && best.matches("bestmove [a-h][1-8][a-h][1-8][nbrq]?"));
            List<String> infos = s.lines().stream().filter(l -> l.startsWith("info depth ")).toList();
            check("uci: one info line per iteration with score, nodes, time and pv",
                    infos.size() == 4 && infos.get(3).startsWith("info depth 4 score cp ")
                    && infos.get(3).contains(" nodes ") && infos.get(3).contains(" time ") && infos.get(3).contains(" pv "));
            s.clear();

            // Mate in one from a FEN: the score is reported as a mate and the mating move is played.
            s.send("position fen 6k1/5ppp/8/8/8/8/8/R6K w - - 0 1");
            s.send("go depth 3");
            best = s.await("bestmove");
            check("uci: mate in one found from fen", "bestmove a1a8".equals(best));
            check("uci: mate score reported as mate 1",
                    s.lines().stream().anyMatch(l -> l.startsWith("info depth") && l.contains(" score mate 1 ")));
            s.clear();

            // Timed search with a clock: returns well within the allotment.
            s.send("position startpos");
            long t0 = System.nanoTime();
            s.send("go wtime 3000 btime 3000 winc 0 binc 0");
            best = s.await("bestmove");
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            check("uci: clock-based search returns quickly (" + ms + " ms)", best != null && ms < 1_500);
            s.clear();

            // Book move when OwnBook is on.
            s.send("setoption name OwnBook value true");
            s.send("position startpos");
            s.send("go depth 2");
            best = s.await("bestmove");
            check("uci: book move with OwnBook on",
                    best != null && s.lines().stream().anyMatch(l -> l.contains("book move")));
            s.clear();

            // Illegal move in the position command is reported, not fatal.
            s.send("position startpos moves e2e5");
            s.send("isready");
            check("uci: illegal move reported", s.await("info string illegal move") != null && s.await("readyok") != null);
            check("uci: engine thread alive", s.failure == null);
        }
    }

    private static void stopAndQuit() throws Exception {
        try (Session s = new Session()) {
            s.send("uci");
            s.await("uciok");
            s.send("setoption name OwnBook value false");
            s.send("position startpos moves d2d4 g8f6 c2c4 e7e6");
            s.send("go infinite");
            Thread.sleep(400);
            check("uci: infinite search keeps going", s.lines().stream().noneMatch(l -> l.startsWith("bestmove")));
            s.send("stop");
            String best = s.await("bestmove");
            check("uci: stop yields a bestmove", best != null && best.matches("bestmove [a-h][1-8][a-h][1-8][nbrq]?"));
            s.clear();
            // Stopping a search immediately still answers.
            s.send("go infinite");
            s.send("stop");
            check("uci: stop right after go still answers", s.await("bestmove") != null);
        }
    }

    private static void check(String name, boolean ok) {
        System.out.printf("%-55s %s%n", name, ok ? "OK" : "FAIL");
        if (!ok) failures++;
    }
}

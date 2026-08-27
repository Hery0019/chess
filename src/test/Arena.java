package test;

import engine.Board;
import engine.Move;
import engine.MoveGenerator;
import engine.OpeningBook;
import engine.Pieces;
import engine.Search;
import game.GameResult;
import game.GameSession;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Engine-vs-engine harness: plays two feature sets of {@link Search} against
 * each other from the opening-book lines (every opening twice, colours
 * swapped) and reports the score as an Elo difference with a 95% interval
 * and the likelihood of superiority. This is how a search or evaluation
 * change earns its place in this project: a technique that does not win
 * games at equal time is not kept.
 *
 * <pre>
 * java -cp out test.Arena [games=40] [movetime=100 | depth=4] [threads=cores/2]
 *                         [a=all] [b=baseline] [tt=16] [maxplies=300]
 * </pre>
 * A feature spec is {@code all}, {@code baseline}, or a comma list of
 * {@code pvs, nullmove, lmr, futility, aspiration, see, structure, mobility};
 * a name prefixed with {@code -} removes it ({@code all,-mobility} measures
 * mobility alone). Games are adjudicated when both engines have agreed for
 * eight plies that one side is up more than a queen, and drawn at the ply
 * limit. Not part of the test runners: a match takes minutes and its verdict
 * is statistical.
 */
public final class Arena {

    private static final String[] FEATURES =
            {"pvs", "nullmove", "lmr", "futility", "aspiration", "see", "structure", "mobility"};
    /** Plies of each book line replayed before the engines take over. */
    private static final int OPENING_PLIES = 8;
    private static final int ADJUDICATE_SCORE = 900, ADJUDICATE_PLIES = 8;

    private record Game(int index, String opening, String name, int aColor) {}
    private record Outcome(Game game, double aScore, int plies, String how) {}

    public static void main(String[] args) throws Exception {
        int games = 40, movetime = 100, depth = 0, ttBits = 16, maxPlies = 300;
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        String specA = "all", specB = "baseline";
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq < 0) throw new IllegalArgumentException("expected key=value, got " + arg);
            String k = arg.substring(0, eq), v = arg.substring(eq + 1);
            switch (k) {
                case "games" -> games = Integer.parseInt(v);
                case "movetime" -> { movetime = Integer.parseInt(v); depth = 0; }
                case "depth" -> { depth = Integer.parseInt(v); movetime = 0; }
                case "threads" -> threads = Integer.parseInt(v);
                case "a" -> specA = v;
                case "b" -> specB = v;
                case "tt" -> ttBits = Integer.parseInt(v);
                case "maxplies" -> maxPlies = Integer.parseInt(v);
                default -> throw new IllegalArgumentException("unknown option " + k);
            }
        }
        Search.Options a = parse(specA), b = parse(specB);
        System.out.printf(Locale.ROOT, "A = %s%nB = %s%n%d games, %s, %d thread(s), TT 2^%d, draw at %d plies%n%n",
                describe(a), describe(b), games,
                depth > 0 ? "depth " + depth : movetime + " ms/move", threads, ttBits, maxPlies);

        List<Game> schedule = schedule(games);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CompletionService<Outcome> done = new ExecutorCompletionService<>(pool);
        final int fDepth = depth, fMovetime = movetime, fTt = ttBits, fMax = maxPlies;
        for (Game g : schedule) done.submit(() -> play(g, a, b, fDepth, fMovetime, fTt, fMax));

        int wins = 0, draws = 0, losses = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < schedule.size(); i++) {
            Outcome o = done.take().get();
            if (o.aScore() == 1) wins++; else if (o.aScore() == 0) losses++; else draws++;
            System.out.printf(Locale.ROOT, "%3d. %-24s A as %-5s %-4s %3d plies  %s   [+%d =%d -%d]%n",
                    o.game().index() + 1, o.game().name(), o.game().aColor() == Pieces.WHITE ? "White" : "Black",
                    o.aScore() == 1 ? "WIN" : o.aScore() == 0 ? "LOSS" : "draw", o.plies(), o.how(),
                    wins, draws, losses);
        }
        pool.shutdown();
        report(wins, draws, losses, (System.nanoTime() - t0) / 1_000_000_000L);
    }

    // ---- schedule ----

    private static List<Game> schedule(int games) {
        List<String> lines = OpeningBook.lines();
        List<Game> out = new ArrayList<>(games);
        for (int i = 0; i < games; i++) {
            int line = (i / 2) % lines.size();
            String[] tokens = lines.get(line).trim().split("\\s+");
            String opening = String.join(" ", Arrays.copyOf(tokens, Math.min(OPENING_PLIES, tokens.length)));
            out.add(new Game(i, opening, "opening " + (line + 1), i % 2 == 0 ? Pieces.WHITE : Pieces.BLACK));
        }
        return out;
    }

    // ---- one game ----

    private static Outcome play(Game g, Search.Options a, Search.Options b,
                                int depth, int movetime, int ttBits, int maxPlies) {
        GameSession s = new GameSession(Board.startPosition(), false);
        MoveGenerator gen = new MoveGenerator();
        for (String token : g.opening().split(" ")) {
            Move move = null;
            for (Move m : gen.generateLegal(s.board())) if (m.toString().equals(token)) { move = m; break; }
            if (move == null) throw new IllegalStateException("opening: illegal move " + token);
            s.applyMove(move);
        }
        Search[] engines = new Search[2];
        engines[g.aColor()] = new Search(ttBits, a);
        engines[g.aColor() ^ 1] = new Search(ttBits, b);
        AtomicBoolean never = new AtomicBoolean(false);

        int agree = 0;                       // +n: n plies of "White is winning", -n: Black
        int winner = -1;
        String how = null;
        while (s.result() == GameResult.ONGOING) {
            if (s.plyCount() >= maxPlies) { how = "ply limit"; break; }
            int stm = s.sideToMove();
            Search.Result r = engines[stm].findBest(s.board(), depth > 0 ? depth : 64, movetime,
                    s.priorPositionKeys(), never);
            if (r == null || r.bestMove() == null) break;
            s.applyMove(r.bestMove());
            int whiteView = stm == Pieces.WHITE ? r.score() : -r.score();
            if (whiteView > ADJUDICATE_SCORE)       agree = agree > 0 ? agree + 1 : 1;
            else if (whiteView < -ADJUDICATE_SCORE) agree = agree < 0 ? agree - 1 : -1;
            else agree = 0;
            if (agree >= ADJUDICATE_PLIES)  { winner = Pieces.WHITE; how = "adjudicated"; break; }
            if (agree <= -ADJUDICATE_PLIES) { winner = Pieces.BLACK; how = "adjudicated"; break; }
        }
        if (how == null) {
            how = s.result().message();
            String token = s.result().pgnToken();
            winner = token.equals("1-0") ? Pieces.WHITE : token.equals("0-1") ? Pieces.BLACK : -1;
        }
        double aScore = winner < 0 ? 0.5 : winner == g.aColor() ? 1 : 0;
        return new Outcome(g, aScore, s.plyCount(), how);
    }

    // ---- report ----

    private static void report(int wins, int draws, int losses, long seconds) {
        int n = wins + draws + losses;
        double p = (wins + 0.5 * draws) / n;
        // Standard error of the mean per-game score, then a 95% interval mapped to Elo.
        double var = (wins * sq(1 - p) + draws * sq(0.5 - p) + losses * sq(p)) / n;
        double se = Math.sqrt(var / n);
        double los = 0.5 * (1 + erf((wins - losses) / Math.sqrt(2.0 * Math.max(1, wins + losses))));
        System.out.printf(Locale.ROOT, "%nA vs B: +%d =%d -%d  (%d games, %.1f%%, %d s)%n", wins, draws, losses, n, 100 * p, seconds);
        System.out.printf(Locale.ROOT, "Elo difference: %+.0f  [%+.0f, %+.0f] at 95%%   LOS %.1f%%%n",
                elo(p), elo(p - 1.96 * se), elo(p + 1.96 * se), 100 * los);
    }

    private static double sq(double x) { return x * x; }

    private static double elo(double p) {
        p = Math.max(0.001, Math.min(0.999, p));
        return -400 * Math.log10(1 / p - 1);
    }

    /** Abramowitz-Stegun 7.1.26, |error| < 1.5e-7. */
    private static double erf(double x) {
        double t = 1 / (1 + 0.3275911 * Math.abs(x));
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592)
                * t * Math.exp(-x * x);
        return x >= 0 ? y : -y;
    }

    // ---- feature specs ----

    static Search.Options parse(String spec) {
        boolean[] f = new boolean[FEATURES.length];
        for (String part : spec.split(",")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (p.isEmpty()) continue;
            if (p.equals("all")) { Arrays.fill(f, true); continue; }
            if (p.equals("baseline") || p.equals("none")) { Arrays.fill(f, false); continue; }
            boolean on = !p.startsWith("-");
            int i = Arrays.asList(FEATURES).indexOf(on ? p : p.substring(1));
            if (i < 0) throw new IllegalArgumentException("unknown feature " + p + "; known: " + String.join(", ", FEATURES));
            f[i] = on;
        }
        return new Search.Options(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7]);
    }

    static String describe(Search.Options o) {
        if (o.equals(Search.Options.ALL)) return "all";
        if (o.equals(Search.Options.BASELINE)) return "baseline";
        boolean[] f = {o.pvs(), o.nullMove(), o.lmr(), o.futility(), o.aspiration(), o.see(), o.pawnStructure(), o.mobility()};
        List<String> on = new ArrayList<>();
        for (int i = 0; i < f.length; i++) if (f[i]) on.add(FEATURES[i]);
        return on.isEmpty() ? "baseline" : String.join(",", on);
    }
}

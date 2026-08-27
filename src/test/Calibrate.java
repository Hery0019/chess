package test;

import engine.Board;
import engine.Move;
import engine.MoveGenerator;
import engine.OpeningBook;
import engine.Search;
import engine.Skill;
import engine.Undo;
import game.GameResult;
import game.GameSession;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Calibrates the Elo labels of the {@link Skill} levels by the metric
 * rating sites use for humans: the average centipawn loss per move (ACPL).
 * Each level plays itself from the book openings; every move it makes is
 * then judged by the full engine at fixed depth (best score before the move
 * against the score after it, both from the mover's side). Head-to-head
 * self-play cannot do this job: between two blunder-prone players the one
 * who blunders half as often wins almost every game, which reads as 400+
 * Elo when the real gap is a fraction of that.
 *
 * The ACPL-to-Elo curve is a fit through published Lichess statistics
 * (about 110 cp at 800, 65 at 1400, 34 at 2000):
 * {@code Elo = 2000 - 1000 * ln(ACPL / 34)}. It is a rough guide, good to
 * a couple of hundred points, which is the precision the labels claim.
 *
 * <pre>java -cp out test.Calibrate [levels=1-6] [games=8] [depth=8] [threads=cores/2]</pre>
 */
public final class Calibrate {

    private static final int OPENING_PLIES = 8;
    private static final int MAX_LOSS = 1000;        // a mate score counts as one lost queen
    private static final int BLUNDER = 300;

    private record Sample(int loss) {}
    private record Report(int level, double acpl, double blunderRate, int moves) {
        int elo() { return (int) Math.round(2000 - 1000 * Math.log(acpl / 34.0)); }
    }

    public static void main(String[] args) throws Exception {
        int from = 1, to = 6, games = 8, depth = 8;
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        for (String arg : args) {
            String[] kv = arg.split("=", 2);
            switch (kv[0]) {
                case "levels" -> { String[] r = kv[1].split("-"); from = Integer.parseInt(r[0]); to = Integer.parseInt(r[r.length - 1]); }
                case "games" -> games = Integer.parseInt(kv[1]);
                case "depth" -> depth = Integer.parseInt(kv[1]);
                case "threads" -> threads = Integer.parseInt(kv[1]);
                default -> throw new IllegalArgumentException("unknown option " + kv[0]);
            }
        }
        System.out.printf(Locale.ROOT, "levels %d..%d, %d self-play games each, judged at depth %d%n%n", from, to, games, depth);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Report>> futures = new ArrayList<>();
        for (int level = from; level <= to; level++) {
            final int lv = level, g = games, d = depth;
            futures.add(pool.submit(() -> measure(lv, g, d)));
        }
        System.out.println("level  label   ACPL  blunders  moves  -> Elo by ACPL curve");
        for (Future<Report> f : futures) {
            Report r = f.get();
            System.out.printf(Locale.ROOT, "%5d  %5d  %5.1f  %6.1f%%  %5d  -> %d%n",
                    r.level(), Skill.level(r.level()).elo(), r.acpl(), 100 * r.blunderRate(), r.moves(), r.elo());
        }
        pool.shutdown();
    }

    private static Report measure(int level, int games, int depth) {
        MoveGenerator gen = new MoveGenerator();
        Search player = new Search(16);
        Search judge = new Search(18);
        AtomicBoolean never = new AtomicBoolean(false);
        List<Sample> samples = new ArrayList<>();
        List<String> lines = OpeningBook.lines();
        for (int g = 0; g < games; g++) {
            GameSession s = new GameSession(Board.startPosition(), false);
            String[] tokens = lines.get(g % lines.size()).trim().split("\\s+");
            for (String token : Arrays.copyOf(tokens, Math.min(OPENING_PLIES, tokens.length))) {
                for (Move m : gen.generateLegal(s.board())) if (m.toString().equals(token)) { s.applyMove(m); break; }
            }
            int judged = 0;
            while (s.result() == GameResult.ONGOING && s.plyCount() < 120) {
                Board before = s.board().copy();
                Search.Result played = Skill.choose(player, level, before, 0, s.priorPositionKeys(), never);
                if (played == null || played.bestMove() == null) break;
                if (judged < 40) {
                    int best = judge.findBest(before, depth, 0, s.priorPositionKeys(), never).score();
                    Board after = before.copy();
                    after.makeMove(played.bestMove(), new Undo());
                    Search.Result reply = judge.findBest(after, depth - 1, 0, null, never);
                    int achieved = -reply.score();   // terminal replies (mate delivered, stalemate) score themselves
                    int loss = Math.max(0, Math.min(MAX_LOSS, clamp(best) - clamp(achieved)));
                    samples.add(new Sample(loss));
                    judged++;
                }
                s.applyMove(played.bestMove());
            }
        }
        double acpl = samples.stream().mapToInt(Sample::loss).average().orElse(0);
        double blunders = samples.stream().filter(x -> x.loss() >= BLUNDER).count() / (double) Math.max(1, samples.size());
        return new Report(level, acpl, blunders, samples.size());
    }

    /** Mate scores are far outside the centipawn scale; clamp them to a decisive but finite value. */
    private static int clamp(int score) { return Math.max(-MAX_LOSS, Math.min(MAX_LOSS, score)); }
}

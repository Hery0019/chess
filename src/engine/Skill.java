package engine;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.random.RandomGenerator;

/**
 * Playing-strength levels, each labelled with an approximate Elo.
 *
 * A level is a search depth plus, for the weaker ones, evaluation noise:
 * every root move is scored exactly at that depth, Gaussian noise is added
 * to each score, and the noisy maximum is played. The engine therefore
 * still prefers good moves but misjudges regularly, and once the noise is
 * comparable to a piece value it hangs material the way a beginner does.
 * Depth bounds the horizon; noise bounds the accuracy. Levels without noise
 * are the full engine at a depth cap (and obey the time budget).
 *
 * The Elo figures are approximate. Levels 1-6 were calibrated with
 * {@code test.Calibrate}: their average centipawn loss per move, judged by
 * the full engine, converted with the curve rating sites observe on human
 * games; the noise was tuned until each level landed on its label. The two
 * top levels are estimates. Weak levels also skip the opening book: a
 * beginner reciting ten moves of theory would give the game away.
 */
public final class Skill {

    /**
     * @param number  1..{@link #MAX}
     * @param elo     approximate rating shown to the player
     * @param depth   search depth (a cap for noiseless levels, exact for noisy ones)
     * @param noiseCp standard deviation of the noise added to each root score, 0 for none
     * @param book    whether the opening book is used
     * @param name    short description for the start screen
     */
    public record Level(int number, int elo, int depth, int noiseCp, boolean book, String name) {}

    public static final List<Level> LEVELS = List.of(
            new Level(1,  500,  1, 260, false, "just learning"),
            new Level(2,  700,  1, 185, false, "beginner"),
            new Level(3,  900,  2, 165, false, "casual"),
            new Level(4, 1100,  3, 125, true,  "improving"),
            new Level(5, 1300,  3, 110, true,  "club player"),
            new Level(6, 1500,  4,  85, true,  "strong club player"),
            new Level(7, 1750,  7,   0, true,  "expert"),
            new Level(8, 2000, 10,   0, true,  "maximum strength"));

    public static final int MIN = 1, MAX = LEVELS.size(), DEFAULT = 5;

    private Skill() {}

    /** @throws IllegalArgumentException outside 1..MAX */
    public static Level level(int number) {
        if (number < MIN || number > MAX) throw new IllegalArgumentException("level " + MIN + ".." + MAX);
        return LEVELS.get(number - 1);
    }

    /** Label such as {@code "1300 Elo"}. */
    public static String eloLabel(int number) { return level(number).elo() + " Elo"; }

    /** Chooses the move to play at this level; see {@link #choose(Search, int, Board, long, long[], AtomicBoolean, RandomGenerator)}. */
    public static Search.Result choose(Search search, int level, Board b, long timeMillis,
                                       long[] priorKeys, AtomicBoolean cancel) {
        return choose(search, level, b, timeMillis, priorKeys, cancel, ThreadLocalRandom.current());
    }

    /**
     * Chooses the move to play at this level.
     *
     * @return the same kind of result as {@link Search#findBest}: the move
     *         played with its true (noise-free) score; null when cancelled;
     *         a null move on a terminal position
     */
    public static Search.Result choose(Search search, int level, Board b, long timeMillis,
                                       long[] priorKeys, AtomicBoolean cancel, RandomGenerator rng) {
        Level lv = level(level);
        if (lv.noiseCp() == 0) return search.findBest(b, lv.depth(), timeMillis, priorKeys, cancel);

        long t0 = System.nanoTime();
        List<Search.RootScore> scores = search.scoreRootMoves(b, lv.depth(), priorKeys, cancel);
        if (scores == null) return null;                                    // cancelled
        if (scores.isEmpty()) return search.findBest(b, 1, 0, priorKeys, cancel);   // terminal: the usual answer
        Search.RootScore pick = null;
        double best = Double.NEGATIVE_INFINITY;
        for (Search.RootScore rs : scores) {
            double noisy = rs.score() + rng.nextGaussian() * lv.noiseCp();
            if (noisy > best) { best = noisy; pick = rs; }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        return new Search.Result(pick.move(), pick.score(), search.lastNodes(), lv.depth(), List.of(pick.move()), ms);
    }
}

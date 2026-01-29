package engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * A small built-in opening book: mainstream lines in long algebraic
 * notation, expanded at class-load time into a map
 * {@code position key -> book moves}. Keying by Zobrist hash means
 * transpositions are handled for free, and every entry is verified legal
 * while the map is built (an illegal token is a programming error and
 * fails fast with the offending line).
 *
 * Probing picks uniformly among the book moves of a position, which gives
 * the engine some opening variety without any search cost. Outside the book
 * the probe returns null and the caller searches normally.
 */
public final class OpeningBook {

    private static final String[] LINES = {
        // 1.e4 e5
        "e2e4 e7e5 g1f3 b8c6 f1b5 a7a6 b5a4 g8f6 e1g1 f8e7",          // Ruy Lopez, closed
        "e2e4 e7e5 g1f3 b8c6 f1b5 g8f6 e1g1 f6e4 d2d4 f8e7",          // Ruy Lopez, open Berlin
        "e2e4 e7e5 g1f3 b8c6 f1c4 f8c5 c2c3 g8f6 d2d3 d7d6",          // Giuoco Piano
        "e2e4 e7e5 g1f3 b8c6 f1c4 g8f6 d2d3 f8c5 c2c3 d7d6",          // Italian, Two Knights
        "e2e4 e7e5 g1f3 g8f6 f3e5 d7d6 e5f3 f6e4 d2d4 d7d5",          // Petroff
        "e2e4 e7e5 g1f3 b8c6 d2d4 e5d4 f3d4 g8f6 d4c6 b7c6 e4e5 d8e7", // Scotch
        "e2e4 e7e5 d2d4 e5d4 g1f3 b8c6 f1c4",                         // Scotch Gambit
        "e2e4 e7e5 b1c3 g8f6 f2f4 d7d5",                              // Vienna
        // 1.e4 c5
        "e2e4 c7c5 g1f3 d7d6 d2d4 c5d4 f3d4 g8f6 b1c3 a7a6",          // Sicilian Najdorf
        "e2e4 c7c5 g1f3 b8c6 d2d4 c5d4 f3d4 g8f6 b1c3 e7e5",          // Sicilian Sveshnikov
        "e2e4 c7c5 g1f3 e7e6 d2d4 c5d4 f3d4 b8c6",                    // Sicilian Taimanov
        "e2e4 c7c5 b1c3 b8c6 g2g3 g7g6 f1g2 f8g7",                    // Closed Sicilian
        // 1.e4 others
        "e2e4 e7e6 d2d4 d7d5 b1c3 g8f6 c1g5 f8e7",                    // French, Classical
        "e2e4 e7e6 d2d4 d7d5 e4e5 c7c5 c2c3 b8c6 g1f3",               // French, Advance
        "e2e4 c7c6 d2d4 d7d5 b1c3 d5e4 c3e4 c8f5 e4g3 f5g6",          // Caro-Kann, Classical
        "e2e4 c7c6 d2d4 d7d5 e4e5 c8f5 g1f3 e7e6",                    // Caro-Kann, Advance
        "e2e4 d7d6 d2d4 g8f6 b1c3 g7g6 g1f3 f8g7",                    // Pirc
        "e2e4 d7d5 e4d5 d8d5 b1c3 d5a5 d2d4 g8f6",                    // Scandinavian
        // 1.d4 d5
        "d2d4 d7d5 c2c4 e7e6 b1c3 g8f6 c1g5 f8e7 e2e3 e8g8",          // Queen's Gambit Declined
        "d2d4 d7d5 c2c4 c7c6 g1f3 g8f6 b1c3 d5c4 a2a4 c8f5",          // Slav
        "d2d4 d7d5 c2c4 d5c4 g1f3 g8f6 e2e3 e7e6 f1c4 c7c5",          // Queen's Gambit Accepted
        "d2d4 d7d5 g1f3 g8f6 c1f4 e7e6 e2e3 c7c5",                    // London System
        // 1.d4 Nf6
        "d2d4 g8f6 c2c4 e7e6 b1c3 f8b4 e2e3 e8g8 f1d3 d7d5",          // Nimzo-Indian
        "d2d4 g8f6 c2c4 e7e6 g1f3 b7b6 g2g3 c8b7 f1g2 f8e7",          // Queen's Indian
        "d2d4 g8f6 c2c4 g7g6 b1c3 f8g7 e2e4 d7d6 g1f3 e8g8",          // King's Indian
        "d2d4 g8f6 c2c4 g7g6 b1c3 d7d5 c4d5 f6d5 e2e4 d5c3 b2c3 f8g7", // Grünfeld
        "d2d4 g8f6 c1g5 e7e6 e2e4 h7h6 g5f6 d8f6",                    // Trompowsky
        "d2d4 f7f5 g2g3 g8f6 f1g2 e7e6 g1f3 f8e7",                    // Dutch
        // Flank openings
        "c2c4 e7e5 b1c3 g8f6 g1f3 b8c6 g2g3 d7d5",                    // English, Reversed Sicilian
        "c2c4 c7c5 g1f3 g8f6 b1c3 b8c6 g2g3 d7d5",                    // English, Symmetrical
        "g1f3 d7d5 g2g3 g8f6 f1g2 e7e6 e1g1 f8e7",                    // Réti / King's Indian Attack
        "g1f3 g8f6 c2c4 g7g6 b1c3 f8g7 e2e4 d7d6 d2d4 e8g8",          // King's Indian by transposition
    };

    private static final Map<Long, List<Move>> BOOK = build();

    private OpeningBook() {}

    /** A random book move for the position, or null when out of book. */
    public static Move probe(Board b) { return probe(b, ThreadLocalRandom.current()); }

    public static Move probe(Board b, RandomGenerator rng) {
        List<Move> candidates = BOOK.get(b.zobristKey());
        if (candidates == null) return null;
        return candidates.get(rng.nextInt(candidates.size()));
    }

    /** Number of distinct positions with at least one book move. */
    public static int positionCount() { return BOOK.size(); }

    private static Map<Long, List<Move>> build() {
        Map<Long, List<Move>> map = new HashMap<>();
        MoveGenerator gen = new MoveGenerator();
        for (String line : LINES) {
            Board b = Board.startPosition();
            for (String token : line.trim().split("\\s+")) {
                Move move = null;
                for (Move m : gen.generateLegal(b)) if (m.toString().equals(token)) { move = m; break; }
                if (move == null) throw new IllegalStateException("opening book: illegal move " + token + " in line " + line);
                List<Move> list = map.computeIfAbsent(b.zobristKey(), k -> new ArrayList<>(2));
                if (!list.contains(move)) list.add(move);
                b.makeMove(move, new Undo());
            }
        }
        return map;
    }
}

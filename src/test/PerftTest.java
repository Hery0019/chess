package test;

import engine.Board;
import engine.Perft;

/**
 * Perft acceptance battery. Positions and node counts are the standard set
 * from the Chess Programming Wiki; between them they exercise castling
 * legality (all refusal conditions), en passant (including the horizontal-pin
 * trap), promotions (including capture-promotions), and pinned-piece
 * legality. Exit code != 0 on any mismatch.
 *
 * Plain main-class runner (no JUnit) so the project builds with a bare JDK.
 */
public final class PerftTest {

    private record Case(String name, String fen, int depth, long expected) {}

    private static final Case[] CASES = {
        new Case("startpos d1", "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", 1, 20),
        new Case("startpos d2", "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", 2, 400),
        new Case("startpos d3", "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", 3, 8_902),
        new Case("startpos d4", "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", 4, 197_281),
        new Case("startpos d5", "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", 5, 4_865_609),
        new Case("kiwipete d3", "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1", 3, 97_862),
        new Case("kiwipete d4", "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1", 4, 4_085_603),
        new Case("pos3 (ep/pins) d5", "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", 5, 674_624),
        new Case("pos4 (promos) d4", "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1", 4, 422_333),
        new Case("pos5 d4", "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8", 4, 2_103_487),
        new Case("pos6 d3", "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10", 3, 89_890),
    };

    public static void main(String[] args) {
        Perft perft = new Perft();
        boolean allPass = true;
        long t0 = System.nanoTime();
        long totalNodes = 0;
        for (Case c : CASES) {
            Board b = Board.fromFen(c.fen());
            long got = perft.perft(b, c.depth());
            totalNodes += got;
            boolean pass = got == c.expected();
            allPass &= pass;
            System.out.printf("%-22s expected %,12d  got %,12d  %s%n",
                    c.name(), c.expected(), got, pass ? "OK" : "FAIL");
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        System.out.printf("%,d nodes in %.2fs (%.0f knps)%n", totalNodes, secs, totalNodes / secs / 1000);
        if (!allPass) {
            System.err.println("PERFT FAILURES — engine is NOT correct.");
            System.exit(1);
        }
        System.out.println("All perft cases passed.");
    }
}

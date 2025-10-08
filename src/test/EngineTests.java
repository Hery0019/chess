package test;

import engine.*;
import game.GameResult;
import game.GameSession;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import static engine.Pieces.*;

/**
 * Targeted rule tests beyond perft (which validates aggregate counts but not
 * named behaviours), plus game-layer draw adjudication and search sanity.
 * Plain main-class runner; exit code != 0 on failure.
 */
public final class EngineTests {

    private static int failures = 0;

    public static void main(String[] args) {
        castlingRefusals();
        enPassantPin();
        zobristIncrementalMatchesScratch();
        threefoldRepetition();
        fiftyMoveRule();
        insufficientMaterial();
        searchFindsMate();
        searchPrefersFasterMate();
        promotionMovesPresent();
        timeoutAdjudication();

        if (failures > 0) {
            System.err.println(failures + " test(s) FAILED.");
            System.exit(1);
        }
        System.out.println("All engine/game tests passed.");
    }

    // ---- castling: every refusal condition individually ----

    private static void castlingRefusals() {
        // Base: white can castle both sides.
        check("castling: both available",
                hasMove("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1", "e1g1")
             && hasMove("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1", "e1c1"));

        // King in check: neither side.
        String inCheck = "r3k2r/8/8/8/8/8/4r3/R3K2R w KQkq - 0 1";
        check("castling: refused while in check",
                !hasMove(inCheck, "e1g1") && !hasMove(inCheck, "e1c1"));

        // Pass-through square attacked (f1 by rook on f8): kingside refused.
        String f1Hit = "r4r1k/8/8/8/8/8/8/R3K2R w KQ - 0 1";
        check("castling: refused through attacked f1",
                !hasMove(f1Hit, "e1g1") && hasMove(f1Hit, "e1c1"));

        // Landing square attacked (g1): kingside refused.
        String g1Hit = "r5rk/8/8/8/8/8/8/R3K2R w KQ - 0 1";
        check("castling: refused into attacked g1", !hasMove(g1Hit, "e1g1"));

        // d1 attacked: queenside refused, kingside fine.
        String d1Hit = "r2r3k/8/8/8/8/8/8/R3K2R w KQ - 0 1";
        check("castling: refused through attacked d1",
                !hasMove(d1Hit, "e1c1") && hasMove(d1Hit, "e1g1"));

        // b1 attacked: queenside STILL LEGAL (king never touches b1) —
        // the classic over-restriction bug.
        String b1Hit = "rr5k/8/8/8/8/8/8/R3K2R w KQ - 0 1";
        check("castling: b1 attacked does not block O-O-O", hasMove(b1Hit, "e1c1"));

        // Blocked path.
        check("castling: blocked by own piece",
                !hasMove("r3k2r/8/8/8/8/8/8/R3KB1R w KQkq - 0 1", "e1g1"));

        // Rights absent (rook moved earlier per FEN).
        check("castling: refused without rights",
                !hasMove("r3k2r/8/8/8/8/8/8/R3K2R w Qkq - 0 1", "e1g1"));

        // Rights are stripped when the ROOK is captured on its home square.
        Board b = Board.fromFen("r3k2r/8/8/8/8/8/6b1/R3K2R b KQkq - 0 1");
        applyByName(b, "g2h1");   // bishop takes h1 rook
        check("castling: right stripped when rook captured",
                (b.castlingRights() & Board.WK_CASTLE) == 0
             && (b.castlingRights() & Board.WQ_CASTLE) != 0);
    }

    // ---- en passant horizontal-pin trap ----

    private static void enPassantPin() {
        // Rank 4: black Ka4, white Pd4 (just double-pushed), black Pe4, white Qh4.
        // exd3 e.p. would clear the whole rank and expose the black king to Qh4.
        Board b = Board.fromFen("8/8/8/8/k2Pp2Q/8/8/4K3 b - d3 0 1");
        check("en passant: capture refused when horizontally pinned",
                !hasMove(b, "e4d3"));

        // Same shape without the queen: ep must be available.
        Board b2 = Board.fromFen("8/8/8/8/k2Pp3/8/8/4K3 b - d3 0 1");
        check("en passant: available when not pinned", hasMove(b2, "e4d3"));
    }

    // ---- zobrist: incremental == from-scratch through a random playout ----

    private static void zobristIncrementalMatchesScratch() {
        MoveGenerator gen = new MoveGenerator();
        Random rng = new Random(42);
        Board b = Board.startPosition();
        Undo u = new Undo();
        boolean ok = true;
        for (int ply = 0; ply < 200; ply++) {
            List<Move> moves = gen.generateLegal(b);
            if (moves.isEmpty()) break;
            Move m = moves.get(rng.nextInt(moves.size()));
            b.makeMove(m, u);
            if (b.zobristKey() != Zobrist.computeFromScratch(b)) { ok = false; break; }
            // Also verify unmake restores the key exactly.
            long before = u.zobrist;
            b.unmakeMove(m, u);
            if (b.zobristKey() != before) { ok = false; break; }
            b.makeMove(m, u);
        }
        check("zobrist: incremental matches scratch over 200-ply playout", ok);
    }

    // ---- draws ----

    private static void threefoldRepetition() {
        GameSession s = new GameSession();
        String[] shuffle = {"g1f3", "g8f6", "f3g1", "f6g8"};
        for (int rep = 0; rep < 2; rep++) {
            for (String mv : shuffle) {
                check("repetition: game still ongoing mid-shuffle",
                        !s.result().isOver());
                s.applyMove(findMove(s.legalMoves(), mv));
            }
        }
        // Start position has now occurred 3 times (ply 0, 4, 8).
        check("repetition: threefold auto-declared",
                s.result() == GameResult.DRAW_REPETITION);
    }

    private static void fiftyMoveRule() {
        // Halfmove clock at 99; one quiet move reaches 100 -> immediate draw.
        Board b = Board.fromFen("7k/8/8/8/8/8/8/R6K w - - 99 80");
        GameSession s = new GameSession(b);
        s.applyMove(findMove(s.legalMoves(), "a1a2"));
        check("fifty-move: declared at halfmove 100",
                s.result() == GameResult.DRAW_FIFTY_MOVES);

        // A capture resets the clock: no draw.
        Board b2 = Board.fromFen("r6k/8/8/8/8/8/8/R6K w - - 99 80");
        GameSession s2 = new GameSession(b2);
        s2.applyMove(findMove(s2.legalMoves(), "a1a8"));
        check("fifty-move: reset by capture", s2.result() == GameResult.ONGOING);
    }

    private static void insufficientMaterial() {
        check("material: K vs K dead",
                new GameSession(Board.fromFen("7k/8/8/8/8/8/8/7K w - - 0 1")).result()
                        == GameResult.DRAW_INSUFFICIENT_MATERIAL);
        check("material: K+B vs K dead",
                new GameSession(Board.fromFen("7k/8/8/8/8/8/8/5B1K w - - 0 1")).result()
                        == GameResult.DRAW_INSUFFICIENT_MATERIAL);
        check("material: K+N vs K dead",
                new GameSession(Board.fromFen("7k/8/8/8/8/8/8/5N1K w - - 0 1")).result()
                        == GameResult.DRAW_INSUFFICIENT_MATERIAL);
        // Bishops on same colour (f1 and c8 are both light squares): dead.
        check("material: KB vs KB same colour dead",
                new GameSession(Board.fromFen("2b4k/8/8/8/8/8/8/5B1K w - - 0 1")).result()
                        == GameResult.DRAW_INSUFFICIENT_MATERIAL);
        // Bishops on opposite colours (f1 light, b8 dark): NOT auto-dead.
        check("material: KB vs KB opposite colour not dead",
                new GameSession(Board.fromFen("1b5k/8/8/8/8/8/8/5B1K w - - 0 1")).result()
                        == GameResult.ONGOING);
        check("material: K+R vs K not dead",
                new GameSession(Board.fromFen("7k/8/8/8/8/8/8/R6K w - - 0 1")).result()
                        == GameResult.ONGOING);
        check("material: KNN vs K not auto-declared",
                new GameSession(Board.fromFen("7k/8/8/8/8/8/8/N1N4K w - - 0 1")).result()
                        == GameResult.ONGOING);
    }

    // ---- search sanity ----

    private static void searchFindsMate() {
        // Back-rank: Ra8# is the only mate.
        Board b = Board.fromFen("6k1/5ppp/8/8/8/8/8/R6K w - - 0 1");
        Search.Result r = new Search().findBest(b, 3, new AtomicBoolean(false));
        check("search: finds back-rank mate in 1",
                r != null && r.bestMove().toString().equals("a1a8")
                          && r.score() > Search.MATE_THRESHOLD);
    }

    private static void searchPrefersFasterMate() {
        // Two-rook ladder: Kf2, Rg2, Rh1 vs Ka8. No mate in 1 exists; mate in
        // 2 is forced (1.Rg7 Kb8 2.Rh8#). A correct ply-adjusted scorer must
        // therefore return exactly MATE - 3 at depth 5, proving the search
        // reports the fastest mate rather than any mate within the horizon.
        Board b = Board.fromFen("k7/8/8/8/8/8/5KR1/7R w - - 0 1");
        Search.Result r = new Search().findBest(b, 5, new AtomicBoolean(false));
        // Mate in 2 => score == MATE - 3 plies (w, b, w).
        check("search: mate score is ply-adjusted (prefers fastest mate)",
                r != null && r.score() == Search.MATE_SCORE - 3);
    }

    private static void promotionMovesPresent() {
        Board b = Board.fromFen("8/P6k/8/8/8/8/8/7K w - - 0 1");
        List<Move> legal = new MoveGenerator().generateLegal(b);
        long promos = legal.stream().filter(m -> m.promotion() != 0).count();
        check("promotion: all four choices generated", promos == 4);
    }

    private static void timeoutAdjudication() {
        // Opponent has mating material: flag fall is a loss.
        GameSession s = new GameSession(Board.fromFen("7k/8/8/8/8/8/8/R6K w - - 0 1"));
        s.timeout(BLACK);
        check("timeout: loss with material on the board",
                s.result() == GameResult.WHITE_WINS_TIMEOUT);

        // Opponent is a bare king: draw. (K+p vs K: pawn side flags.)
        GameSession s2 = new GameSession(Board.fromFen("7k/8/8/8/8/8/P7/7K w - - 0 1"));
        s2.timeout(WHITE);
        check("timeout: draw vs bare king", s2.result() == GameResult.DRAW_TIMEOUT_VS_BARE_KING);
    }

    // ---- helpers ----

    private static boolean hasMove(String fen, String mv) {
        return hasMove(Board.fromFen(fen), mv);
    }

    private static boolean hasMove(Board b, String mv) {
        return new MoveGenerator().generateLegal(b).stream()
                .anyMatch(m -> m.toString().equals(mv));
    }

    private static Move findMove(List<Move> legal, String mv) {
        return legal.stream().filter(m -> m.toString().equals(mv)).findFirst()
                .orElseThrow(() -> new AssertionError("move not legal: " + mv));
    }

    private static void applyByName(Board b, String mv) {
        Move m = findMove(new MoveGenerator().generateLegal(b), mv);
        b.makeMove(m, new Undo());
    }

    private static void check(String name, boolean ok) {
        System.out.printf("%-55s %s%n", name, ok ? "OK" : "FAIL");
        if (!ok) failures++;
    }
}

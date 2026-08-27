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
        searchSeesRepetitionAsDraw();
        searchAvoidsRepetitionWhenWinning();
        searchIterativeDeepening();
        searchReusesTableAcrossCalls();
        staticExchange();
        nullMoveRoundTrip();
        pruningSavesNodes();
        openingBook();
        sanNotation();
        pgnExport();
        undoRestoresEverything();
        savedGameRoundTrip();
        resignationAndAgreedDraw();
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

    private static void searchSeesRepetitionAsDraw() {
        // White (Kh3, Qf8) is a queen and two rooks down but has a forced
        // perpetual: 1.Qf7+ Kh8 2.Qf8+ Kh7 repeats the root. Black's pieces
        // are boxed in behind its own b2 pawn and cannot interpose or capture.
        // With repetition detection the root scores exactly 0 and the engine
        // chooses the perpetual; without it the line scores ~-1300.
        Board b = Board.fromFen("5Q2/7k/6pp/8/8/7K/rp6/qr6 w - - 0 1");
        Search.Result r = new Search().findBest(b, 5, new AtomicBoolean(false));
        check("search: forced perpetual scores as a draw",
                r != null && r.score() == Search.DRAW_SCORE && r.bestMove().toString().equals("f8f7"));
    }

    private static void searchAvoidsRepetitionWhenWinning() {
        // KQ vs K, White to move and winning. First ask for the natural best
        // move; then tell the search that the position it leads to has
        // already occurred twice in the game (halfmove clock high enough for
        // those plies to count). Playing it again would be a threefold draw,
        // so the engine must switch to another move that is still winning.
        Board b = Board.fromFen("1k6/8/8/3Q4/8/8/8/6K1 w - - 10 30");
        Search.Result first = new Search().findBest(b, 4, new AtomicBoolean(false));
        Board after = b.copy();
        after.makeMove(first.bestMove(), new Undo());
        long[] prior = {after.zobristKey(), after.zobristKey()};
        Search.Result second = new Search().findBest(b, 4, 0, prior, new AtomicBoolean(false));
        check("search: winning side avoids the repeating move",
                first != null && second != null
                && !second.bestMove().equals(first.bestMove()) && second.score() > 500);
    }

    private static void searchIterativeDeepening() {
        Board b = Board.startPosition();
        Search.Result fixed = new Search().findBest(b, 3, new AtomicBoolean(false));
        check("search: fixed depth reports its depth and a PV",
                fixed != null && fixed.depth() == 3 && fixed.pv().size() >= 1
                && fixed.pv().get(0).equals(fixed.bestMove()));

        long t0 = System.nanoTime();
        Search.Result timed = new Search().findBest(b, 30, 150, null, new AtomicBoolean(false));
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        check("search: time budget returns a completed iteration quickly",
                timed != null && timed.bestMove() != null && timed.depth() >= 1 && timed.depth() < 30 && ms < 2_000);

        AtomicBoolean cancelled = new AtomicBoolean(true);
        check("search: cancelled search returns null",
                new Search().findBest(b, 6, 0, null, cancelled) == null);
    }

    private static void searchReusesTableAcrossCalls() {
        // Same instance, same position twice: the table must not corrupt the answer.
        Search s = new Search();
        Board b = Board.fromFen("k7/8/8/8/8/8/5KR1/7R w - - 0 1");
        Search.Result r1 = s.findBest(b, 5, new AtomicBoolean(false));
        Search.Result r2 = s.findBest(b, 5, new AtomicBoolean(false));
        check("search: table reuse keeps the mate-in-2 answer",
                r1 != null && r2 != null && r1.score() == Search.MATE_SCORE - 3
                && r2.score() == Search.MATE_SCORE - 3 && r2.bestMove().equals(r1.bestMove()));
    }

    private static void staticExchange() {
        MoveGenerator gen = new MoveGenerator();
        // The two classic examples: Rxe5 simply wins a pawn; Nxe5 wins the pawn
        // but the recapture sequence (with the queen x-raying through the
        // bishop and the queen behind the rook) ends a knight for a pawn down.
        Board b1 = Board.fromFen("1k1r4/1pp4p/p7/4p3/8/P5P1/1PP4P/2K1R3 w - - 0 1");
        Board b2 = Board.fromFen("1k1r3q/1ppn3p/p4b2/4p3/8/P2N2P1/1PP1R1BP/2K1Q3 w - - 0 1");
        check("see: rook takes an undefended pawn = +100",
                StaticExchange.see(b1, findMove(gen.generateLegal(b1), "e1e5")) == 100);
        check("see: knight takes a defended pawn = -220 (x-rays counted)",
                StaticExchange.see(b2, findMove(gen.generateLegal(b2), "d3e5")) == -220);
        // Rook takes a pawn defended by a rook: pawn won, rook lost.
        Board b3 = Board.fromFen("4k3/3r4/8/3p4/8/8/8/3RK3 w - - 0 1");
        check("see: rook takes a rook-defended pawn = -400",
                StaticExchange.see(b3, findMove(gen.generateLegal(b3), "d1d5")) == -400);
        // Rook takes a queen defended by a rook: queen won, rook lost.
        Board b4 = Board.fromFen("4k3/3r4/8/3q4/8/8/8/3RK3 w - - 0 1");
        check("see: rook takes a rook-defended queen = +400",
                StaticExchange.see(b4, findMove(gen.generateLegal(b4), "d1d5")) == 400);
    }

    private static void nullMoveRoundTrip() {
        Board b = Board.fromFen("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2");
        long before = b.zobristKey();
        Undo u = new Undo();
        b.makeNullMove(u);
        check("null move: side flips, ep cleared, hash matches scratch",
                b.sideToMove() == BLACK && b.epSquare() == -1
                && b.zobristKey() == Zobrist.computeFromScratch(b) && b.zobristKey() != before);
        b.unmakeNullMove(u);
        check("null move: unmake restores the position",
                b.sideToMove() == WHITE && b.epSquare() == 44 && b.zobristKey() == before && b.halfmoveClock() == 0);
        check("null move: non-pawn material guard",
                b.hasNonPawnMaterial(WHITE)
                && !Board.fromFen("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1").hasNonPawnMaterial(WHITE));
    }

    private static void pruningSavesNodes() {
        // The point of the selectivity: the same depth for a fraction of the nodes.
        Board b = Board.fromFen("r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10");
        Search.Result full = new Search(16, Search.Options.ALL).findBest(b, 5, new AtomicBoolean(false));
        Search.Result plain = new Search(16, Search.Options.BASELINE).findBest(b, 5, new AtomicBoolean(false));
        check("pruning: depth 5 costs under half the nodes of plain alpha-beta",
                full != null && plain != null && full.nodes() * 2 < plain.nodes());
        // The mate tests above run with every technique on; the plain engine must agree.
        Board mate = Board.fromFen("k7/8/8/8/8/8/5KR1/7R w - - 0 1");
        check("pruning: forced mate scores identically without pruning",
                new Search(16, Search.Options.BASELINE).findBest(mate, 5, new AtomicBoolean(false)).score()
                        == Search.MATE_SCORE - 3);
    }

    private static void openingBook() {
        // Loading the class validates every line (an illegal token throws).
        check("book: holds a reasonable number of positions", OpeningBook.positionCount() > 100);
        Board start = Board.startPosition();
        Move m = OpeningBook.probe(start);
        check("book: start position has a legal book move",
                m != null && new MoveGenerator().generateLegal(start).contains(m));
        // Follow the book through a whole line: every probe is legal until it runs out.
        Board b = Board.startPosition();
        int plies = 0;
        for (Move bm; (bm = OpeningBook.probe(b, new java.util.Random(7))) != null; plies++) {
            if (!new MoveGenerator().generateLegal(b).contains(bm)) { plies = -1; break; }
            b.makeMove(bm, new Undo());
        }
        check("book: a random walk stays legal and ends after a few moves", plies >= 4 && plies <= 12);
        // Transposition: 1.Nf3 Nf6 2.c4 g6 reaches a 1.d4-book position by a different order — no matter, same key.
        Board kiwipete = Board.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1");
        check("book: out-of-book position probes null", OpeningBook.probe(kiwipete) == null);
    }

    private static void sanNotation() {
        GameSession s = new GameSession();
        for (String mv : new String[]{"e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6",
                                      "e1g1", "f6e4", "f1e1", "d7d5", "c4d5", "d8d5"}) {
            s.applyMove(findMove(s.legalMoves(), mv));
        }
        check("san: pieces, captures, castling, pawn moves",
                String.join(" ", s.sanHistory()).equals("e4 e5 Nf3 Nc6 Bc4 Nf6 O-O Nxe4 Re1 d5 Bxd5 Qxd5"));

        check("san: file disambiguation (Nce2 / Nge2)",
                sanOf("4k3/8/8/8/8/2N5/8/4K1N1 w - - 0 1", "c3e2").equals("Nce2")
             && sanOf("4k3/8/8/8/8/2N5/8/4K1N1 w - - 0 1", "g1e2").equals("Nge2"));
        check("san: rank disambiguation (R1a3 / R5a3)",
                sanOf("4k3/8/8/R7/8/8/8/R3K3 w - - 0 1", "a1a3").equals("R1a3")
             && sanOf("4k3/8/8/R7/8/8/8/R3K3 w - - 0 1", "a5a3").equals("R5a3"));
        check("san: no disambiguation when the other piece is pinned or cannot reach",
                sanOf("4k3/8/8/8/8/8/8/R3K2R w - - 0 1", "a1b1").equals("Rb1"));
        check("san: mate suffix", sanOf("6k1/5ppp/8/8/8/8/8/R6K w - - 0 1", "a1a8").equals("Ra8#"));
        check("san: promotion with check", sanOf("8/P7/8/8/8/8/8/k6K w - - 0 1", "a7a8q").equals("a8=Q+"));
        check("san: en passant is a pawn capture", sanOf("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1", "e5d6").equals("exd6"));
        check("san: queenside castling with check",
                sanOf("3k4/8/8/8/8/8/8/R3K3 w Q - 0 1", "e1c1").equals("O-O-O+"));
    }

    private static String sanOf(String fen, String lan) {
        Board b = Board.fromFen(fen);
        java.util.List<Move> legal = new MoveGenerator().generateLegal(b);
        return game.Notation.san(b, findMove(legal, lan), legal);
    }

    private static void pgnExport() {
        GameSession s = new GameSession();
        for (String mv : new String[]{"e2e4", "e7e5", "d1h5", "b8c6", "f1c4", "g8f6", "h5f7"}) {
            s.applyMove(findMove(s.legalMoves(), mv));
        }
        String pgn = game.Notation.pgn(s, "Tester", "Engine \"v2\"");
        check("pgn: result tag and token for a mate",
                pgn.contains("[Result \"1-0\"]") && pgn.trim().endsWith("4. Qxf7# 1-0"));
        check("pgn: seven-tag roster with escaped quotes",
                pgn.contains("[White \"Tester\"]") && pgn.contains("[Black \"Engine \\\"v2\\\"\"]")
             && pgn.contains("[Event ") && pgn.contains("[Date "));
        check("pgn: numbered move text", pgn.contains("1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6"));
        check("pgn: ongoing game ends with *", game.Notation.pgn(new GameSession(), "a", "b").trim().endsWith("*"));
    }

    private static void undoRestoresEverything() {
        // Play a line with captures, a castle and recaptures; take
        // it all back and compare against a fresh session replaying a prefix.
        String[] line = {"e2e4", "d7d5", "e4d5", "d8d5", "b1c3", "d5a5", "d2d4", "g8f6", "g1f3", "c7c6",
                         "f1c4", "c8f5", "e1g1", "e7e6", "d4d5", "e6d5", "c3d5", "c6d5", "c4d5"};
        GameSession s = new GameSession();
        for (String mv : line) s.applyMove(findMove(s.legalMoves(), mv));
        int full = s.plyCount();
        for (int i = 0; i < 6; i++) s.undoLastMove();
        GameSession replay = new GameSession();
        for (int i = 0; i < full - 6; i++) replay.applyMove(findMove(replay.legalMoves(), line[i]));
        check("undo: board, keys and SAN match a fresh replay",
                s.board().zobristKey() == replay.board().zobristKey()
                && Zobrist.computeFromScratch(s.board()) == s.board().zobristKey()
                && s.sanHistory().equals(replay.sanHistory())
                && java.util.Arrays.equals(s.priorPositionKeys(), replay.priorPositionKeys())
                && s.legalMoves().equals(replay.legalMoves())
                && s.board().toString().equals(replay.board().toString()));
        check("undo: the same moves can be replayed after taking back", replayable(s, line, full - 6));

        // Undo after checkmate makes the game ongoing again.
        GameSession mate = new GameSession();
        for (String mv : new String[]{"f2f3", "e7e5", "g2g4", "d8h4"}) mate.applyMove(findMove(mate.legalMoves(), mv));
        check("undo: game over before takeback", mate.result() == GameResult.BLACK_WINS_MATE);
        mate.undoLastMove();
        check("undo: takeback after mate resumes the game",
                mate.result() == GameResult.ONGOING && mate.plyCount() == 3 && mate.canUndo());

        // Repetition table is unwound: threefold declared, undone, re-declared.
        GameSession rep = new GameSession();
        String[] shuffle = {"g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1", "f6g8"};
        for (String mv : shuffle) rep.applyMove(findMove(rep.legalMoves(), mv));
        check("undo: threefold declared", rep.result() == GameResult.DRAW_REPETITION);
        rep.undoLastMove();
        check("undo: threefold withdrawn after takeback", rep.result() == GameResult.ONGOING);
        rep.applyMove(findMove(rep.legalMoves(), "f6g8"));
        check("undo: threefold declared again on replay", rep.result() == GameResult.DRAW_REPETITION);
        while (rep.canUndo()) rep.undoLastMove();
        check("undo: back to the start position",
                rep.board().zobristKey() == Board.startPosition().zobristKey() && rep.plyCount() == 0);
    }

    private static void savedGameRoundTrip() {
        game.GameConfig cfg = new game.GameConfig(game.GameConfig.Mode.HUMAN_VS_AI, BLACK, 10, 3, 2);
        boolean badLimit = false;
        try { new game.GameConfig(game.GameConfig.Mode.HUMAN_VS_AI, BLACK, 10, 3, -1); }
        catch (IllegalArgumentException e) { badLimit = true; }
        check("config: undo limit validated, takebacks are Human vs AI only",
                badLimit && cfg.undoEnabled()
                && !new game.GameConfig(game.GameConfig.Mode.HUMAN_VS_AI, BLACK, 10, 3, game.GameConfig.NO_UNDO).undoEnabled()
                && !new game.GameConfig(game.GameConfig.Mode.AI_VS_AI, BLACK, 10, 3, 5).undoEnabled());
        GameSession s = new GameSession();
        for (String mv : new String[]{"e2e4", "e7e5", "g1f3", "b8c6", "f1b5"}) s.applyMove(findMove(s.legalMoves(), mv));
        game.ChessClock clock = new game.ChessClock(cfg.millisPerSide());
        clock.restoreUsed(4321, 1234);
        game.SavedGame saved = game.SavedGame.of(cfg, s, clock, 1);
        String text = saved.serialize();
        game.SavedGame back = game.SavedGame.parse(text);
        check("save: serialise/parse round trip", back.equals(saved) && text.startsWith(game.SavedGame.HEADER));
        check("save: moves and clocks preserved",
                back.moves().equals(List.of("e2e4", "e7e5", "g1f3", "b8c6", "f1b5"))
                && back.whiteUsedMillis() == 4321 && back.blackUsedMillis() == 1234
                && back.config().humanColor() == BLACK && back.config().minutesPerSide() == 10);
        check("save: undo limit and takebacks spent preserved",
                back.config().undoLimit() == 2 && back.undoUsed() == 1
                && text.contains("\nundo-limit 2\nundo-used 1\n"));
        // Files written before the Undo setting existed carry no undo lines: defaults apply.
        game.SavedGame legacy = game.SavedGame.parse(text.replace("undo-limit 2\n", "").replace("undo-used 1\n", ""));
        check("save: undo lines optional (older files)",
                legacy.config().undoLimit() == game.GameConfig.DEFAULT_UNDO_LIMIT && legacy.undoUsed() == 0
                && legacy.moves().equals(saved.moves()));
        boolean badUndo = false;
        try { game.SavedGame.parse(text.replace("undo-used 1", "undo-used -1")); }
        catch (IllegalArgumentException e) { badUndo = true; }
        check("save: negative takeback count rejected", badUndo);
        // Replaying through a session reproduces the position.
        GameSession replay = new GameSession();
        for (String mv : back.moves()) replay.applyMove(findMove(replay.legalMoves(), mv));
        check("save: replay reaches the same position", replay.board().zobristKey() == s.board().zobristKey());
        // Windows line endings and blank lines are tolerated; garbage is not.
        check("save: CRLF tolerated", game.SavedGame.parse(text.replace("\n", "\r\n") + "\r\n").equals(saved));
        boolean rejected = false;
        try { game.SavedGame.parse("hello"); } catch (IllegalArgumentException e) { rejected = true; }
        boolean rejectedMove = false;
        try { game.SavedGame.parse(text.replace("f1b5", "zz99")); } catch (IllegalArgumentException e) { rejectedMove = true; }
        check("save: malformed files are rejected", rejected && rejectedMove);
        check("save: empty move list allowed",
                game.SavedGame.parse(new game.SavedGame(cfg, List.of(), 0, 0, 0).serialize()).moves().isEmpty());
    }

    private static void resignationAndAgreedDraw() {
        GameSession s = new GameSession();
        s.applyMove(findMove(s.legalMoves(), "e2e4"));
        s.resign(BLACK);
        check("resign: black resigns, white wins",
                s.result() == GameResult.WHITE_WINS_RESIGNATION && s.result().pgnToken().equals("1-0"));
        s.agreeDraw();
        check("resign: result is final", s.result() == GameResult.WHITE_WINS_RESIGNATION);
        boolean refused = false;
        try { s.applyMove(findMove(s.legalMoves(), "e7e5")); } catch (IllegalStateException e) { refused = true; }
        check("resign: no moves after the game ended", refused);
        s.undoLastMove();
        check("resign: takeback reopens the game", s.result() == GameResult.ONGOING);

        GameSession d = new GameSession();
        d.agreeDraw();
        check("draw: agreed draw", d.result() == GameResult.DRAW_AGREED && d.result().pgnToken().equals("1/2-1/2"));
        d.resign(WHITE);
        check("draw: agreed draw is final", d.result() == GameResult.DRAW_AGREED);
    }

    private static boolean replayable(GameSession s, String[] line, int from) {
        try {
            for (int i = from; i < line.length; i++) s.applyMove(findMove(s.legalMoves(), line[i]));
            return true;
        } catch (RuntimeException | AssertionError e) {
            return false;
        }
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

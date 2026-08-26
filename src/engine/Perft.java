package engine;

import java.util.List;

/**
 * Perft: counts leaf nodes of the legal move tree to a fixed depth. Matching
 * published node counts for standard test positions verifies the entire
 * generation + make/unmake pipeline, including every castling, en-passant,
 * promotion, and pin edge case. This is the engine's acceptance gate.
 */
public final class Perft {

    private final MoveGenerator gen = new MoveGenerator();

    public long perft(Board b, int depth) {
        if (depth == 0) return 1;
        List<Move> moves = gen.generateLegal(b);
        if (depth == 1) return moves.size();
        long nodes = 0;
        Undo u = new Undo();
        for (Move m : moves) {
            b.makeMove(m, u);
            nodes += perft(b, depth - 1);
            b.unmakeMove(m, u);
        }
        return nodes;
    }

    /** Per-root-move breakdown ("divide") — the standard debugging aid. */
    public void divide(Board b, int depth) {
        Undo u = new Undo();
        long total = 0;
        for (Move m : gen.generateLegal(b)) {
            b.makeMove(m, u);
            long n = perft(b, depth - 1);
            b.unmakeMove(m, u);
            System.out.printf("%s: %d%n", m, n);
            total += n;
        }
        System.out.println("total: " + total);
    }
}

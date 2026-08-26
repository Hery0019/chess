package engine;

import static engine.Pieces.*;

/**
 * Static evaluation in centipawns, returned from the side-to-move's
 * perspective (negamax convention).
 *
 * Components (per approved scope):
 *  - material (P=100 N=320 B=330 R=500 Q=900),
 *  - piece-square tables (Michniewski's "simplified evaluation" tables,
 *    a well-known public-domain baseline) giving center control,
 *    development, and pawn advancement,
 *  - king PST tapered between middlegame and endgame tables by remaining
 *    non-pawn material, avoiding evaluation discontinuities at a phase switch,
 *  - a small middlegame pawn-shield bonus for king safety.
 *
 * Deliberately EXCLUDED (approved): mobility — it would require running move
 * generation inside the hottest function in the program for marginal gain
 * at this search depth.
 *
 * Tables are written visually with rank 8 as the first row (index 0 = a8),
 * the standard published orientation. White reads them through sq ^ 56
 * (rank flip); Black reads them directly. Scores are accumulated
 * white-positive and negated at the end if Black is to move.
 */
public final class Evaluator {

    private static final int[] PAWN_PST = {
         0,  0,  0,  0,  0,  0,  0,  0,
        50, 50, 50, 50, 50, 50, 50, 50,
        10, 10, 20, 30, 30, 20, 10, 10,
         5,  5, 10, 25, 25, 10,  5,  5,
         0,  0,  0, 20, 20,  0,  0,  0,
         5, -5,-10,  0,  0,-10, -5,  5,
         5, 10, 10,-20,-20, 10, 10,  5,
         0,  0,  0,  0,  0,  0,  0,  0
    };
    private static final int[] KNIGHT_PST = {
       -50,-40,-30,-30,-30,-30,-40,-50,
       -40,-20,  0,  0,  0,  0,-20,-40,
       -30,  0, 10, 15, 15, 10,  0,-30,
       -30,  5, 15, 20, 20, 15,  5,-30,
       -30,  0, 15, 20, 20, 15,  0,-30,
       -30,  5, 10, 15, 15, 10,  5,-30,
       -40,-20,  0,  5,  5,  0,-20,-40,
       -50,-40,-30,-30,-30,-30,-40,-50
    };
    private static final int[] BISHOP_PST = {
       -20,-10,-10,-10,-10,-10,-10,-20,
       -10,  0,  0,  0,  0,  0,  0,-10,
       -10,  0,  5, 10, 10,  5,  0,-10,
       -10,  5,  5, 10, 10,  5,  5,-10,
       -10,  0, 10, 10, 10, 10,  0,-10,
       -10, 10, 10, 10, 10, 10, 10,-10,
       -10,  5,  0,  0,  0,  0,  5,-10,
       -20,-10,-10,-10,-10,-10,-10,-20
    };
    private static final int[] ROOK_PST = {
         0,  0,  0,  0,  0,  0,  0,  0,
         5, 10, 10, 10, 10, 10, 10,  5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
         0,  0,  0,  5,  5,  0,  0,  0
    };
    private static final int[] QUEEN_PST = {
       -20,-10,-10, -5, -5,-10,-10,-20,
       -10,  0,  0,  0,  0,  0,  0,-10,
       -10,  0,  5,  5,  5,  5,  0,-10,
        -5,  0,  5,  5,  5,  5,  0, -5,
         0,  0,  5,  5,  5,  5,  0, -5,
       -10,  5,  5,  5,  5,  5,  0,-10,
       -10,  0,  5,  0,  0,  0,  0,-10,
       -20,-10,-10, -5, -5,-10,-10,-20
    };
    private static final int[] KING_MG_PST = {
       -30,-40,-40,-50,-50,-40,-40,-30,
       -30,-40,-40,-50,-50,-40,-40,-30,
       -30,-40,-40,-50,-50,-40,-40,-30,
       -30,-40,-40,-50,-50,-40,-40,-30,
       -20,-30,-30,-40,-40,-30,-30,-20,
       -10,-20,-20,-20,-20,-20,-20,-10,
        20, 20,  0,  0,  0,  0, 20, 20,
        20, 30, 10,  0,  0, 10, 30, 20
    };
    private static final int[] KING_EG_PST = {
       -50,-40,-30,-20,-20,-30,-40,-50,
       -30,-20,-10,  0,  0,-10,-20,-30,
       -30,-10, 20, 30, 30, 20,-10,-30,
       -30,-10, 30, 40, 40, 30,-10,-30,
       -30,-10, 30, 40, 40, 30,-10,-30,
       -30,-10, 20, 30, 30, 20,-10,-30,
       -30,-30,  0,  0,  0,  0,-30,-30,
       -50,-30,-30,-30,-30,-30,-30,-50
    };

    /** Game-phase weights by piece type; total from the start position = 24. */
    private static final int[] PHASE_WEIGHT = {0, 0, 1, 1, 2, 4, 0};
    private static final int MAX_PHASE = 24;
    private static final int PAWN_SHIELD_BONUS = 10;

    /** Score in centipawns from the perspective of the side to move. */
    public int evaluate(Board b) {
        int score = 0;   // white-positive
        int phase = 0;

        for (int sq = 0; sq < 64; sq++) {
            int p = b.pieceAt(sq);
            if (p == EMPTY) continue;
            int type = typeOf(p);
            int color = colorOf(p);
            phase += PHASE_WEIGHT[type];
            if (type == KING) continue;   // handled after phase is known

            int idx = color == WHITE ? (sq ^ 56) : sq;
            int v = VALUE[type] + switch (type) {
                case PAWN   -> PAWN_PST[idx];
                case KNIGHT -> KNIGHT_PST[idx];
                case BISHOP -> BISHOP_PST[idx];
                case ROOK   -> ROOK_PST[idx];
                case QUEEN  -> QUEEN_PST[idx];
                default -> 0;
            };
            score += color == WHITE ? v : -v;
        }
        if (phase > MAX_PHASE) phase = MAX_PHASE;   // promotions can exceed start material

        // Tapered king PST: pure MG at phase 24, pure EG at phase 0.
        score += taperedKing(b, WHITE, phase);
        score -= taperedKing(b, BLACK, phase);

        // Pawn shield: middlegame only, scaled with phase so it fades out
        // smoothly instead of snapping off.
        if (phase > 6) {
            score += pawnShield(b, WHITE) * phase / MAX_PHASE;
            score -= pawnShield(b, BLACK) * phase / MAX_PHASE;
        }

        return b.sideToMove() == WHITE ? score : -score;
    }

    private int taperedKing(Board b, int color, int phase) {
        int sq = b.kingSquare(color);
        int idx = color == WHITE ? (sq ^ 56) : sq;
        return (KING_MG_PST[idx] * phase + KING_EG_PST[idx] * (MAX_PHASE - phase)) / MAX_PHASE;
    }

    /** Counts friendly pawns on the three squares directly in front of the king. */
    private int pawnShield(Board b, int color) {
        int sq = b.kingSquare(color);
        int rank = sq >>> 3, file = sq & 7;
        int frontRank = rank + (color == WHITE ? 1 : -1);
        if (frontRank < 0 || frontRank > 7) return 0;
        int pawn = make(PAWN, color), bonus = 0;
        for (int f = Math.max(0, file - 1); f <= Math.min(7, file + 1); f++) {
            if (b.pieceAt(frontRank * 8 + f) == pawn) bonus += PAWN_SHIELD_BONUS;
        }
        return bonus;
    }
}

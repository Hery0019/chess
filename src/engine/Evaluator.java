package engine;

import static engine.Pieces.*;

/**
 * Static evaluation in centipawns, returned from the side-to-move's
 * perspective (negamax convention). Every term is kept as a middlegame and
 * an endgame value and the two are blended by the remaining non-pawn
 * material ("tapered evaluation"), so nothing snaps at a phase boundary.
 *
 * Components:
 *  - material (P=100 N=320 B=330 R=500 Q=900),
 *  - piece-square tables (Michniewski's "simplified evaluation" tables, a
 *    well-known public-domain baseline) giving center control, development
 *    and pawn advancement; the king has separate middlegame / endgame tables,
 *  - a middlegame pawn-shield bonus for king safety,
 *  - pawn structure: passed pawns (growing with rank, much more so in the
 *    endgame), isolated and doubled pawns,
 *  - the bishop pair, rooks on open / half-open files and on the 7th rank,
 *  - mobility: squares each knight, bishop, rook and queen can reach that
 *    are not covered by an enemy pawn.
 *
 * Mobility was originally left out because it costs a ray scan per piece
 * inside the hottest function of the program. It stayed in once the
 * engine-vs-engine harness ({@code test.Arena}) showed the extra knowledge
 * outweighs the lost nodes; both groups of extras can be switched off (see
 * {@link Search.Options}) so that trade-off remains measurable.
 *
 * Tables are written visually with rank 8 as the first row (index 0 = a8),
 * the standard published orientation. White reads them through sq ^ 56
 * (rank flip); Black reads them directly. Scores are accumulated
 * white-positive and negated at the end if Black is to move.
 *
 * Not thread-safe (scratch arrays); one instance per {@link Search}.
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

    // ---- pawn structure, bishop pair, rooks: (middlegame, endgame) ----
    /** Bonus for a passed pawn by its rank counted from its own side (index 1 = second rank). */
    private static final int[] PASSED_MG = {0,  5, 10, 20, 35,  60, 100, 0};
    private static final int[] PASSED_EG = {0, 10, 20, 35, 60, 100, 160, 0};
    private static final int ISOLATED_MG = -12, ISOLATED_EG = -18;
    private static final int DOUBLED_MG = -10, DOUBLED_EG = -20;         // per extra pawn on a file
    private static final int BISHOP_PAIR_MG = 30, BISHOP_PAIR_EG = 50;
    private static final int ROOK_OPEN_MG = 20, ROOK_OPEN_EG = 15;
    private static final int ROOK_HALF_OPEN_MG = 10, ROOK_HALF_OPEN_EG = 8;
    private static final int ROOK_SEVENTH_MG = 20, ROOK_SEVENTH_EG = 30;

    // ---- mobility: (reachable squares - typical count) * weight ----
    private static final int KNIGHT_MOB_MG = 4, KNIGHT_MOB_EG = 4, KNIGHT_MOB_CENTER = 4;
    private static final int BISHOP_MOB_MG = 5, BISHOP_MOB_EG = 5, BISHOP_MOB_CENTER = 6;
    private static final int ROOK_MOB_MG   = 2, ROOK_MOB_EG   = 4, ROOK_MOB_CENTER   = 7;
    private static final int QUEEN_MOB_MG  = 1, QUEEN_MOB_EG  = 2, QUEEN_MOB_CENTER  = 13;

    private final boolean structure;
    private final boolean mobility;

    // Scratch state for one evaluation.
    private final int[][] pawnsOnFile = new int[2][8];
    private final int[] whitePawnMinRank = new int[8];   // least advanced white pawn per file (8 = none)
    private final int[] blackPawnMaxRank = new int[8];   // least advanced black pawn per file (-1 = none)
    private final long[] pawnAttacks = new long[2];      // squares covered by each side's pawns
    private int mg, eg;

    /** Full evaluation, every term on. */
    public Evaluator() { this(true, true); }

    /**
     * @param structure pawn structure, bishop pair and rook terms
     * @param mobility  piece mobility
     */
    public Evaluator(boolean structure, boolean mobility) {
        this.structure = structure;
        this.mobility = mobility;
    }

    /** Score in centipawns from the perspective of the side to move. */
    public int evaluate(Board b) {
        mg = 0;
        eg = 0;
        int phase = 0;
        int whiteBishops = 0, blackBishops = 0;
        for (int f = 0; f < 8; f++) {
            pawnsOnFile[WHITE][f] = 0;
            pawnsOnFile[BLACK][f] = 0;
            whitePawnMinRank[f] = 8;
            blackPawnMaxRank[f] = -1;
        }
        pawnAttacks[WHITE] = 0L;
        pawnAttacks[BLACK] = 0L;

        // Pass 1: material, piece-square tables, phase, pawn bookkeeping.
        for (int sq = 0; sq < 64; sq++) {
            int p = b.pieceAt(sq);
            if (p == EMPTY) continue;
            int type = typeOf(p);
            int color = colorOf(p);
            phase += PHASE_WEIGHT[type];
            int idx = color == WHITE ? (sq ^ 56) : sq;
            int v;
            switch (type) {
                case PAWN -> {
                    v = VALUE[PAWN] + PAWN_PST[idx];
                    int file = sq & 7, rank = sq >>> 3;
                    pawnsOnFile[color][file]++;
                    if (color == WHITE) {
                        if (rank < whitePawnMinRank[file]) whitePawnMinRank[file] = rank;
                        if (file > 0 && sq + 7 < 64) pawnAttacks[WHITE] |= 1L << (sq + 7);
                        if (file < 7 && sq + 9 < 64) pawnAttacks[WHITE] |= 1L << (sq + 9);
                    } else {
                        if (rank > blackPawnMaxRank[file]) blackPawnMaxRank[file] = rank;
                        if (file > 0 && sq - 9 >= 0) pawnAttacks[BLACK] |= 1L << (sq - 9);
                        if (file < 7 && sq - 7 >= 0) pawnAttacks[BLACK] |= 1L << (sq - 7);
                    }
                }
                case KNIGHT -> v = VALUE[KNIGHT] + KNIGHT_PST[idx];
                case BISHOP -> {
                    v = VALUE[BISHOP] + BISHOP_PST[idx];
                    if (color == WHITE) whiteBishops++; else blackBishops++;
                }
                case ROOK   -> v = VALUE[ROOK] + ROOK_PST[idx];
                case QUEEN  -> v = VALUE[QUEEN] + QUEEN_PST[idx];
                default -> {                                   // king: tapered tables
                    add(color, KING_MG_PST[idx], KING_EG_PST[idx]);
                    continue;
                }
            }
            add(color, v, v);
        }
        if (phase > MAX_PHASE) phase = MAX_PHASE;   // promotions can exceed start material

        // Pawn shield: a middlegame term, so it fades out with the phase.
        add(WHITE, pawnShield(b, WHITE), 0);
        add(BLACK, pawnShield(b, BLACK), 0);

        if (structure) {
            if (whiteBishops >= 2) add(WHITE, BISHOP_PAIR_MG, BISHOP_PAIR_EG);
            if (blackBishops >= 2) add(BLACK, BISHOP_PAIR_MG, BISHOP_PAIR_EG);
            for (int f = 0; f < 8; f++) {
                int w = pawnsOnFile[WHITE][f], bl = pawnsOnFile[BLACK][f];
                if (w > 1) add(WHITE, DOUBLED_MG * (w - 1), DOUBLED_EG * (w - 1));
                if (bl > 1) add(BLACK, DOUBLED_MG * (bl - 1), DOUBLED_EG * (bl - 1));
            }
        }

        // Pass 2: per-piece terms that need the pawn bookkeeping from pass 1.
        if (structure || mobility) {
            for (int sq = 0; sq < 64; sq++) {
                int p = b.pieceAt(sq);
                if (p == EMPTY) continue;
                int type = typeOf(p);
                int color = colorOf(p);
                switch (type) {
                    case PAWN -> { if (structure) pawnTerms(sq, color); }
                    case ROOK -> {
                        if (structure) rookTerms(b, sq, color);
                        if (mobility) mobilityTerms(b, sq, type, color);
                    }
                    case KNIGHT, BISHOP, QUEEN -> { if (mobility) mobilityTerms(b, sq, type, color); }
                    default -> { }
                }
            }
        }

        int score = (mg * phase + eg * (MAX_PHASE - phase)) / MAX_PHASE;
        return b.sideToMove() == WHITE ? score : -score;
    }

    private void add(int color, int mgValue, int egValue) {
        if (color == WHITE) { mg += mgValue; eg += egValue; }
        else                { mg -= mgValue; eg -= egValue; }
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

    /** Passed / isolated status of one pawn. */
    private void pawnTerms(int sq, int color) {
        int file = sq & 7, rank = sq >>> 3;
        boolean isolated = (file == 0 || pawnsOnFile[color][file - 1] == 0)
                        && (file == 7 || pawnsOnFile[color][file + 1] == 0);
        if (isolated) add(color, ISOLATED_MG, ISOLATED_EG);

        boolean passed = true;
        for (int f = Math.max(0, file - 1); f <= Math.min(7, file + 1) && passed; f++) {
            // An enemy pawn ahead on this or a neighbouring file can stop or capture it.
            passed = color == WHITE ? blackPawnMaxRank[f] <= rank : whitePawnMinRank[f] >= rank;
        }
        if (passed) {
            int relRank = color == WHITE ? rank : 7 - rank;
            add(color, PASSED_MG[relRank], PASSED_EG[relRank]);
        }
    }

    /** Open / half-open file and seventh-rank bonuses for one rook. */
    private void rookTerms(Board b, int sq, int color) {
        int file = sq & 7, rank = sq >>> 3;
        if (pawnsOnFile[color][file] == 0) {
            if (pawnsOnFile[color ^ 1][file] == 0) add(color, ROOK_OPEN_MG, ROOK_OPEN_EG);
            else add(color, ROOK_HALF_OPEN_MG, ROOK_HALF_OPEN_EG);
        }
        int seventh = color == WHITE ? 6 : 1, eighth = color == WHITE ? 7 : 0;
        if (rank == seventh) {
            // Worth something only with targets: the enemy king cut off on its back rank, or pawns to eat.
            boolean target = (b.kingSquare(color ^ 1) >>> 3) == eighth;
            int enemyPawn = make(PAWN, color ^ 1);
            for (int f = 0; f < 8 && !target; f++) target = b.pieceAt(seventh * 8 + f) == enemyPawn;
            if (target) add(color, ROOK_SEVENTH_MG, ROOK_SEVENTH_EG);
        }
    }

    /** Squares the piece can move to (empty or enemy-occupied) that no enemy pawn covers. */
    private void mobilityTerms(Board b, int sq, int type, int color) {
        long unsafe = pawnAttacks[color ^ 1];
        switch (type) {
            case KNIGHT -> {
                int n = 0;
                for (int t : Board.KNIGHT_TARGETS[sq]) if (reachable(b, t, color, unsafe)) n++;
                add(color, (n - KNIGHT_MOB_CENTER) * KNIGHT_MOB_MG, (n - KNIGHT_MOB_CENTER) * KNIGHT_MOB_EG);
            }
            case BISHOP -> {
                int n = rayMobility(b, sq, color, Board.BISHOP_DIRS, unsafe);
                add(color, (n - BISHOP_MOB_CENTER) * BISHOP_MOB_MG, (n - BISHOP_MOB_CENTER) * BISHOP_MOB_EG);
            }
            case ROOK -> {
                int n = rayMobility(b, sq, color, Board.ROOK_DIRS, unsafe);
                add(color, (n - ROOK_MOB_CENTER) * ROOK_MOB_MG, (n - ROOK_MOB_CENTER) * ROOK_MOB_EG);
            }
            case QUEEN -> {
                int n = rayMobility(b, sq, color, Board.BISHOP_DIRS, unsafe)
                      + rayMobility(b, sq, color, Board.ROOK_DIRS, unsafe);
                add(color, (n - QUEEN_MOB_CENTER) * QUEEN_MOB_MG, (n - QUEEN_MOB_CENTER) * QUEEN_MOB_EG);
            }
            default -> { }
        }
    }

    private static boolean reachable(Board b, int t, int color, long unsafe) {
        if ((unsafe & (1L << t)) != 0) return false;
        int p = b.pieceAt(t);
        return p == EMPTY || colorOf(p) != color;
    }

    private static int rayMobility(Board b, int sq, int color, int[][] dirs, long unsafe) {
        int r0 = sq >>> 3, f0 = sq & 7, n = 0;
        for (int[] d : dirs) {
            int r = r0 + d[0], f = f0 + d[1];
            while (r >= 0 && r < 8 && f >= 0 && f < 8) {
                int t = r * 8 + f;
                int p = b.pieceAt(t);
                if (p != EMPTY && colorOf(p) == color) break;
                if ((unsafe & (1L << t)) == 0) n++;
                if (p != EMPTY) break;
                r += d[0];
                f += d[1];
            }
        }
        return n;
    }
}

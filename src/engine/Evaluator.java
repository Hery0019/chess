package engine;

import static engine.Pieces.*;

/**
 * Static evaluation in centipawns, returned from the side-to-move's
 * perspective (negamax convention). Every term is kept as a middlegame and
 * an endgame value and the two are blended by the remaining non-pawn
 * material ("tapered evaluation"), so nothing snaps at a phase boundary.
 *
 * Components:
 *  - material and piece-square tables from PeSTO (Ronald Friederich's
 *    public-domain tables, tuned by Texel tuning against a large game
 *    corpus: separate middlegame / endgame values for every piece).
 *    Michniewski's "simplified evaluation" tables, the v2 baseline, are
 *    kept as the reference set the arena measures against,
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
 * paying off as the time per move grows; every group of extras can be
 * switched off (see {@link Search.Options}) so that trade-off remains
 * measurable.
 *
 * Tables are written visually with rank 8 as the first row (index 0 = a8),
 * the standard published orientation. White reads them through sq ^ 56
 * (rank flip); Black reads them directly. Scores are accumulated
 * white-positive and negated at the end if Black is to move.
 *
 * Not thread-safe (scratch arrays); one instance per {@link Search}.
 */
public final class Evaluator {

    // ---- PeSTO: material and tables by piece type (index 1 = pawn .. 6 = king) ----
    private static final int[] PESTO_MG_VALUE = {0, 82, 337, 365, 477, 1025, 0};
    private static final int[] PESTO_EG_VALUE = {0, 94, 281, 297, 512,  936, 0};

    private static final int[] PESTO_MG_PAWN = {
          0,   0,   0,   0,   0,   0,  0,   0,
         98, 134,  61,  95,  68, 126, 34, -11,
         -6,   7,  26,  31,  65,  56, 25, -20,
        -14,  13,   6,  21,  23,  12, 17, -23,
        -27,  -2,  -5,  12,  17,   6, 10, -25,
        -26,  -4,  -4, -10,   3,   3, 33, -12,
        -35,  -1, -20, -23, -15,  24, 38, -22,
          0,   0,   0,   0,   0,   0,  0,   0,
    };
    private static final int[] PESTO_EG_PAWN = {
          0,   0,   0,   0,   0,   0,   0,   0,
        178, 173, 158, 134, 147, 132, 165, 187,
         94, 100,  85,  67,  56,  53,  82,  84,
         32,  24,  13,   5,  -2,   4,  17,  17,
         13,   9,  -3,  -7,  -7,  -8,   3,  -1,
          4,   7,  -6,   1,   0,  -5,  -1,  -8,
         13,   8,   8,  10,  13,   0,   2,  -7,
          0,   0,   0,   0,   0,   0,   0,   0,
    };
    private static final int[] PESTO_MG_KNIGHT = {
        -167, -89, -34, -49,  61, -97, -15, -107,
         -73, -41,  72,  36,  23,  62,   7,  -17,
         -47,  60,  37,  65,  84, 129,  73,   44,
          -9,  17,  19,  53,  37,  69,  18,   22,
         -13,   4,  16,  13,  28,  19,  21,   -8,
         -23,  -9,  12,  10,  19,  17,  25,  -16,
         -29, -53, -12,  -3,  -1,  18, -14,  -19,
        -105, -21, -58, -33, -17, -28, -19,  -23,
    };
    private static final int[] PESTO_EG_KNIGHT = {
        -58, -38, -13, -28, -31, -27, -63, -99,
        -25,  -8, -25,  -2,  -9, -25, -24, -52,
        -24, -20,  10,   9,  -1,  -9, -19, -41,
        -17,   3,  22,  22,  22,  11,   8, -18,
        -18,  -6,  16,  25,  16,  17,   4, -18,
        -23,  -3,  -1,  15,  10,  -3, -20, -22,
        -42, -20, -10,  -5,  -2, -20, -23, -44,
        -29, -51, -23, -15, -22, -18, -50, -64,
    };
    private static final int[] PESTO_MG_BISHOP = {
        -29,   4, -82, -37, -25, -42,   7,  -8,
        -26,  16, -18, -13,  30,  59,  18, -47,
        -16,  37,  43,  40,  35,  50,  37,  -2,
         -4,   5,  19,  50,  37,  37,   7,  -2,
         -6,  13,  13,  26,  34,  12,  10,   4,
          0,  15,  15,  15,  14,  27,  18,  10,
          4,  15,  16,   0,   7,  21,  33,   1,
        -33,  -3, -14, -21, -13, -12, -39, -21,
    };
    private static final int[] PESTO_EG_BISHOP = {
        -14, -21, -11,  -8, -7,  -9, -17, -24,
         -8,  -4,   7, -12, -3, -13,  -4, -14,
          2,  -8,   0,  -1, -2,   6,   0,   4,
         -3,   9,  12,   9, 14,  10,   3,   2,
         -6,   3,  13,  19,  7,  10,  -3,  -9,
        -12,  -3,   8,  10, 13,   3,  -7, -15,
        -14, -18,  -7,  -1,  4,  -9, -15, -27,
        -23,  -9, -23,  -5, -9, -16,  -5, -17,
    };
    private static final int[] PESTO_MG_ROOK = {
         32,  42,  32,  51, 63,  9,  31,  43,
         27,  32,  58,  62, 80, 67,  26,  44,
         -5,  19,  26,  36, 17, 45,  61,  16,
        -24, -11,   7,  26, 24, 35,  -8, -20,
        -36, -26, -12,  -1,  9, -7,   6, -23,
        -45, -25, -16, -17,  3,  0,  -5, -33,
        -44, -16, -20,  -9, -1, 11,  -6, -71,
        -19, -13,   1,  17, 16,  7, -37, -26,
    };
    private static final int[] PESTO_EG_ROOK = {
        13, 10, 18, 15, 12,  12,   8,   5,
        11, 13, 13, 11, -3,   3,   8,   3,
         7,  7,  7,  5,  4,  -3,  -5,  -3,
         4,  3, 13,  1,  2,   1,  -1,   2,
         3,  5,  8,  4, -5,  -6,  -8, -11,
        -4,  0, -5, -1, -7, -12,  -8, -16,
        -6, -6,  0,  2, -9,  -9, -11,  -3,
        -9,  2,  3, -1, -5, -13,   4, -20,
    };
    private static final int[] PESTO_MG_QUEEN = {
        -28,   0,  29,  12,  59,  44,  43,  45,
        -24, -39,  -5,   1, -16,  57,  28,  54,
        -13, -17,   7,   8,  29,  56,  47,  57,
        -27, -27, -16, -16,  -1,  17,  -2,   1,
         -9, -26,  -9, -10,  -2,  -4,   3,  -3,
        -14,   2, -11,  -2,  -5,   2,  14,   5,
        -35,  -8,  11,   2,   8,  15,  -3,   1,
         -1, -18,  -9,  10, -15, -25, -31, -50,
    };
    private static final int[] PESTO_EG_QUEEN = {
         -9,  22,  22,  27,  27,  19,  10,  20,
        -17,  20,  32,  41,  58,  25,  30,   0,
        -20,   6,   9,  49,  47,  35,  19,   9,
          3,  22,  24,  45,  57,  40,  57,  36,
        -18,  28,  19,  47,  31,  34,  39,  23,
        -16, -27,  15,   6,   9,  17,  10,   5,
        -22, -23, -30, -16, -16, -23, -36, -32,
        -33, -28, -22, -43,  -5, -32, -20, -41,
    };
    private static final int[] PESTO_MG_KING = {
        -65,  23,  16, -15, -56, -34,   2,  13,
         29,  -1, -20,  -7,  -8,  -4, -38, -29,
         -9,  24,   2, -16, -20,   6,  22, -22,
        -17, -20, -12, -27, -30, -25, -14, -36,
        -49,  -1, -27, -39, -46, -44, -33, -51,
        -14, -14, -22, -46, -44, -30, -15, -27,
          1,   7,  -8, -64, -43, -16,   9,   8,
        -15,  36,  12, -54,   8, -28,  24,  14,
    };
    private static final int[] PESTO_EG_KING = {
        -74, -35, -18, -18, -11,  15,   4, -17,
        -12,  17,  14,  17,  17,  38,  23,  11,
         10,  17,  23,  15,  20,  45,  44,  13,
         -8,  22,  24,  27,  26,  33,  26,   3,
        -18,  -4,  21,  24,  27,  23,   9, -11,
        -19,  -3,  11,  21,  23,  16,   7,  -9,
        -27, -11,   4,  13,  14,   4,  -5, -17,
        -53, -34, -21, -11, -28, -14, -24, -43,
    };
    private static final int[][] PESTO_MG = {null, PESTO_MG_PAWN, PESTO_MG_KNIGHT, PESTO_MG_BISHOP, PESTO_MG_ROOK, PESTO_MG_QUEEN, PESTO_MG_KING};
    private static final int[][] PESTO_EG = {null, PESTO_EG_PAWN, PESTO_EG_KNIGHT, PESTO_EG_BISHOP, PESTO_EG_ROOK, PESTO_EG_QUEEN, PESTO_EG_KING};

    // ---- Michniewski "simplified evaluation" tables (v2 baseline; one table per piece, king tapered) ----
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
    private static final int[][] SIMPLE_PST = {null, PAWN_PST, KNIGHT_PST, BISHOP_PST, ROOK_PST, QUEEN_PST, null};

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
    private final boolean pesto;

    // Scratch state for one evaluation.
    private final int[][] pawnsOnFile = new int[2][8];
    private final int[] whitePawnMinRank = new int[8];   // least advanced white pawn per file (8 = none)
    private final int[] blackPawnMaxRank = new int[8];   // least advanced black pawn per file (-1 = none)
    private final long[] pawnAttacks = new long[2];      // squares covered by each side's pawns
    private int mg, eg;

    /** Full evaluation, every term on. */
    public Evaluator() { this(true, true, true); }

    /**
     * @param structure pawn structure, bishop pair and rook terms
     * @param mobility  piece mobility
     * @param pesto     PeSTO material and tables (false: Michniewski's tables with P=100 N=320 B=330 R=500 Q=900)
     */
    public Evaluator(boolean structure, boolean mobility, boolean pesto) {
        this.structure = structure;
        this.mobility = mobility;
        this.pesto = pesto;
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
            if (pesto) {
                add(color, PESTO_MG_VALUE[type] + PESTO_MG[type][idx], PESTO_EG_VALUE[type] + PESTO_EG[type][idx]);
            } else if (type == KING) {
                add(color, KING_MG_PST[idx], KING_EG_PST[idx]);
            } else {
                int v = VALUE[type] + SIMPLE_PST[type][idx];
                add(color, v, v);
            }
            if (type == PAWN) {
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
            } else if (type == BISHOP) {
                if (color == WHITE) whiteBishops++; else blackBishops++;
            }
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

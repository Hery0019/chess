package engine;

import static engine.Pieces.*;

/**
 * Mutable chess position. 8x8 mailbox ({@code int[64]}, a1 = 0, h8 = 63).
 *
 * DESIGN TRADE-OFF (reviewed and approved): make/unmake with {@link Undo}
 * records instead of copy-per-node. Copying is simpler but allocates a board
 * per search node; make/unmake keeps the hot loop allocation-free. The
 * correctness risk this introduces is discharged by the perft suite, which
 * exercises every branch of makeMove/unmakeMove against published node counts.
 *
 * Castling-rights bits: 1 = white O-O, 2 = white O-O-O, 4 = black O-O,
 * 8 = black O-O-O.
 */
public final class Board {

    public static final int WK_CASTLE = 1, WQ_CASTLE = 2, BK_CASTLE = 4, BQ_CASTLE = 8;

    /**
     * rights &= CASTLE_MASK[from] & CASTLE_MASK[to] on every move. Any move
     * touching e1/h1/a1/e8/h8/a8 (king or rook leaving, or rook captured)
     * strips exactly the affected rights; every other square keeps all 15 bits.
     */
    private static final int[] CASTLE_MASK = new int[64];
    static {
        java.util.Arrays.fill(CASTLE_MASK, 15);
        CASTLE_MASK[0]  = 15 & ~WQ_CASTLE;                 // a1
        CASTLE_MASK[4]  = 15 & ~(WK_CASTLE | WQ_CASTLE);   // e1
        CASTLE_MASK[7]  = 15 & ~WK_CASTLE;                 // h1
        CASTLE_MASK[56] = 15 & ~BQ_CASTLE;                 // a8
        CASTLE_MASK[60] = 15 & ~(BK_CASTLE | BQ_CASTLE);   // e8
        CASTLE_MASK[63] = 15 & ~BK_CASTLE;                 // h8
    }

    // Precomputed knight and king attack target lists per square.
    static final int[][] KNIGHT_TARGETS = new int[64][];
    static final int[][] KING_TARGETS = new int[64][];
    static {
        int[][] knightD = {{1,2},{2,1},{2,-1},{1,-2},{-1,-2},{-2,-1},{-2,1},{-1,2}};
        int[][] kingD   = {{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1},{0,-1},{1,-1}};
        for (int sq = 0; sq < 64; sq++) {
            KNIGHT_TARGETS[sq] = targets(sq, knightD);
            KING_TARGETS[sq]   = targets(sq, kingD);
        }
    }
    private static int[] targets(int sq, int[][] deltas) {
        int r = sq >>> 3, f = sq & 7, n = 0;
        int[] tmp = new int[8];
        for (int[] d : deltas) {
            int nr = r + d[0], nf = f + d[1];
            if (nr >= 0 && nr < 8 && nf >= 0 && nf < 8) tmp[n++] = nr * 8 + nf;
        }
        return java.util.Arrays.copyOf(tmp, n);
    }

    static final int[][] BISHOP_DIRS = {{1,1},{1,-1},{-1,1},{-1,-1}};
    static final int[][] ROOK_DIRS   = {{1,0},{-1,0},{0,1},{0,-1}};

    // ---- position state ----
    private final int[] squares = new int[64];
    private int sideToMove = WHITE;
    private int castlingRights = 0;
    private int epSquare = -1;      // square BEHIND the double-pushed pawn, or -1
    private int halfmoveClock = 0;
    private int fullmoveNumber = 1;
    private long zobrist = 0L;
    private final int[] kingSq = new int[2];

    // ---- accessors ----
    public int pieceAt(int sq)      { return squares[sq]; }
    public int sideToMove()         { return sideToMove; }
    public int castlingRights()     { return castlingRights; }
    public int epSquare()           { return epSquare; }
    public int halfmoveClock()      { return halfmoveClock; }
    public int fullmoveNumber()     { return fullmoveNumber; }
    public long zobristKey()        { return zobrist; }
    public int kingSquare(int color){ return kingSq[color]; }

    // ---- construction ----

    public static Board startPosition() {
        return fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
    }

    /** Minimal FEN parser — trusted input (tests and internal use only). */
    public static Board fromFen(String fen) {
        Board b = new Board();
        String[] parts = fen.trim().split("\\s+");
        if (parts.length < 4) throw new IllegalArgumentException("Bad FEN: " + fen);

        int sq = 56; // FEN starts at a8
        for (char c : parts[0].toCharArray()) {
            if (c == '/') { sq -= 16; continue; }
            if (Character.isDigit(c)) { sq += c - '0'; continue; }
            int color = Character.isUpperCase(c) ? WHITE : BLACK;
            int type = switch (Character.toLowerCase(c)) {
                case 'p' -> PAWN; case 'n' -> KNIGHT; case 'b' -> BISHOP;
                case 'r' -> ROOK; case 'q' -> QUEEN;  case 'k' -> KING;
                default -> throw new IllegalArgumentException("Bad FEN piece: " + c);
            };
            b.squares[sq] = make(type, color);
            if (type == KING) b.kingSq[color] = sq;
            sq++;
        }
        b.sideToMove = parts[1].equals("w") ? WHITE : BLACK;
        if (!parts[2].equals("-")) {
            for (char c : parts[2].toCharArray()) {
                b.castlingRights |= switch (c) {
                    case 'K' -> WK_CASTLE; case 'Q' -> WQ_CASTLE;
                    case 'k' -> BK_CASTLE; case 'q' -> BQ_CASTLE;
                    default -> 0;
                };
            }
        }
        if (!parts[3].equals("-")) {
            b.epSquare = (parts[3].charAt(0) - 'a') + 8 * (parts[3].charAt(1) - '1');
        }
        b.halfmoveClock = parts.length > 4 ? Integer.parseInt(parts[4]) : 0;
        b.fullmoveNumber = parts.length > 5 ? Integer.parseInt(parts[5]) : 1;
        b.zobrist = Zobrist.computeFromScratch(b);
        return b;
    }

    /** Deep copy — used to hand a private snapshot to the AI worker thread. */
    public Board copy() {
        Board b = new Board();
        System.arraycopy(squares, 0, b.squares, 0, 64);
        b.sideToMove = sideToMove;
        b.castlingRights = castlingRights;
        b.epSquare = epSquare;
        b.halfmoveClock = halfmoveClock;
        b.fullmoveNumber = fullmoveNumber;
        b.zobrist = zobrist;
        b.kingSq[0] = kingSq[0];
        b.kingSq[1] = kingSq[1];
        return b;
    }

    // ---- make / unmake ----

    public void makeMove(Move m, Undo u) {
        u.captured = m.captured();
        u.castlingRights = castlingRights;
        u.epSquare = epSquare;
        u.halfmoveClock = halfmoveClock;
        u.zobrist = zobrist;

        int us = sideToMove;
        int piece = m.piece();
        int type = typeOf(piece);
        int from = m.from(), to = m.to();

        // Clear any existing ep square from the hash; a new one may be set below.
        if (epSquare != -1) zobrist ^= Zobrist.EP_FILE[epSquare & 7];
        epSquare = -1;

        // Remove captured piece.
        if (m.isEnPassant()) {
            int capSq = to + (us == WHITE ? -8 : 8);
            squares[capSq] = EMPTY;
            zobrist ^= Zobrist.PIECE[m.captured()][capSq];
        } else if (m.captured() != EMPTY) {
            zobrist ^= Zobrist.PIECE[m.captured()][to];
        }

        // Move (and possibly promote) the piece.
        squares[from] = EMPTY;
        zobrist ^= Zobrist.PIECE[piece][from];
        int placed = m.promotion() != 0 ? make(m.promotion(), us) : piece;
        squares[to] = placed;
        zobrist ^= Zobrist.PIECE[placed][to];

        if (type == KING) kingSq[us] = to;

        // Rook hop for castling. Rook squares are fixed by geometry.
        if (m.isCastle()) {
            int rookFrom, rookTo;
            if (to > from) { rookFrom = from + 3; rookTo = from + 1; }   // kingside
            else           { rookFrom = from - 4; rookTo = from - 1; }   // queenside
            int rook = squares[rookFrom];
            squares[rookFrom] = EMPTY;
            squares[rookTo] = rook;
            zobrist ^= Zobrist.PIECE[rook][rookFrom] ^ Zobrist.PIECE[rook][rookTo];
        }

        if (m.isDoublePush()) {
            epSquare = (from + to) >>> 1;
            zobrist ^= Zobrist.EP_FILE[epSquare & 7];
        }

        // Castling rights.
        zobrist ^= Zobrist.CASTLING[castlingRights];
        castlingRights &= CASTLE_MASK[from] & CASTLE_MASK[to];
        zobrist ^= Zobrist.CASTLING[castlingRights];

        halfmoveClock = (type == PAWN || m.isCapture()) ? 0 : halfmoveClock + 1;
        if (us == BLACK) fullmoveNumber++;
        sideToMove ^= 1;
        zobrist ^= Zobrist.SIDE_TO_MOVE;
    }

    public void unmakeMove(Move m, Undo u) {
        sideToMove ^= 1;                    // back to the mover
        int us = sideToMove;
        if (us == BLACK) fullmoveNumber--;

        int from = m.from(), to = m.to();
        squares[from] = m.piece();          // undoes promotion implicitly
        squares[to] = EMPTY;

        if (m.isEnPassant()) {
            squares[to + (us == WHITE ? -8 : 8)] = m.captured();
        } else if (m.captured() != EMPTY) {
            squares[to] = m.captured();
        }

        if (typeOf(m.piece()) == KING) kingSq[us] = from;

        if (m.isCastle()) {
            int rookFrom, rookTo;
            if (to > from) { rookFrom = from + 3; rookTo = from + 1; }
            else           { rookFrom = from - 4; rookTo = from - 1; }
            squares[rookFrom] = squares[rookTo];
            squares[rookTo] = EMPTY;
        }

        castlingRights = u.castlingRights;
        epSquare = u.epSquare;
        halfmoveClock = u.halfmoveClock;
        zobrist = u.zobrist;                // full restore — no incremental undo
    }

    // ---- null move (search only) ----

    /**
     * Passes the turn without moving — the "null move" of null-move pruning.
     * Only the side to move, the en-passant square and the hash change;
     * restore with {@link #unmakeNullMove}. Never legal in a game.
     */
    public void makeNullMove(Undo u) {
        u.captured = EMPTY;
        u.castlingRights = castlingRights;
        u.epSquare = epSquare;
        u.halfmoveClock = halfmoveClock;
        u.zobrist = zobrist;
        if (epSquare != -1) {
            zobrist ^= Zobrist.EP_FILE[epSquare & 7];
            epSquare = -1;
        }
        halfmoveClock++;
        sideToMove ^= 1;
        zobrist ^= Zobrist.SIDE_TO_MOVE;
    }

    public void unmakeNullMove(Undo u) {
        sideToMove ^= 1;
        epSquare = u.epSquare;
        halfmoveClock = u.halfmoveClock;
        zobrist = u.zobrist;
    }

    /**
     * Does {@code color} own anything besides pawns and the king? Null-move
     * pruning is switched off without it: pawn endings are where passing
     * would be a real advantage (zugzwang), which breaks the null-move logic.
     */
    public boolean hasNonPawnMaterial(int color) {
        for (int sq = 0; sq < 64; sq++) {
            int p = squares[sq];
            if (p == EMPTY || colorOf(p) != color) continue;
            int t = typeOf(p);
            if (t != PAWN && t != KING) return true;
        }
        return false;
    }

    // ---- attack queries ----

    public boolean inCheck(int color) {
        return isSquareAttacked(kingSq[color], color ^ 1);
    }

    /** Is {@code sq} attacked by any piece of {@code byColor}? */
    public boolean isSquareAttacked(int sq, int byColor) {
        // Pawns. A white pawn on p attacks p+7 (file-1) and p+9 (file+1); invert.
        int file = sq & 7;
        if (byColor == WHITE) {
            if (file > 0 && sq >= 9  && squares[sq - 9] == make(PAWN, WHITE)) return true;
            if (file < 7 && sq >= 7  && squares[sq - 7] == make(PAWN, WHITE)) return true;
        } else {
            if (file > 0 && sq <= 56 && squares[sq + 7] == make(PAWN, BLACK)) return true;
            if (file < 7 && sq <= 54 && squares[sq + 9] == make(PAWN, BLACK)) return true;
        }

        int knight = make(KNIGHT, byColor);
        for (int t : KNIGHT_TARGETS[sq]) if (squares[t] == knight) return true;

        int king = make(KING, byColor);
        for (int t : KING_TARGETS[sq]) if (squares[t] == king) return true;

        return rayAttacked(sq, byColor, BISHOP_DIRS, BISHOP)
            || rayAttacked(sq, byColor, ROOK_DIRS, ROOK);
    }

    private boolean rayAttacked(int sq, int byColor, int[][] dirs, int sliderType) {
        int r0 = sq >>> 3, f0 = sq & 7;
        for (int[] d : dirs) {
            int r = r0 + d[0], f = f0 + d[1];
            while (r >= 0 && r < 8 && f >= 0 && f < 8) {
                int p = squares[r * 8 + f];
                if (p != EMPTY) {
                    if (colorOf(p) == byColor
                            && (typeOf(p) == sliderType || typeOf(p) == QUEEN)) return true;
                    break;
                }
                r += d[0]; f += d[1];
            }
        }
        return false;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int r = 7; r >= 0; r--) {
            for (int f = 0; f < 8; f++) sb.append(Pieces.toChar(squares[r * 8 + f])).append(' ');
            sb.append('\n');
        }
        return sb.toString();
    }
}

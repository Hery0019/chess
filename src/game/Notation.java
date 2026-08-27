package game;

import engine.Board;
import engine.Move;
import engine.MoveGenerator;
import engine.Undo;

import java.time.LocalDate;
import java.util.List;

import static engine.Pieces.*;

/**
 * Standard Algebraic Notation and PGN export. Pure functions over engine
 * types; no Swing, no session mutation.
 */
public final class Notation {

    private static final String PIECE_LETTERS = ".PNBRQK";
    private static final MoveGenerator GEN = new MoveGenerator();   // stateless

    private Notation() {}

    /**
     * SAN of {@code m} played from {@code before}. {@code legal} must be the
     * legal move list of {@code before} (used for disambiguation).
     * Produces e.g. {@code e4, exd5, Nbd7, R1a3, O-O-O, e8=Q+, Qxf7#}.
     */
    public static String san(Board before, Move m, List<Move> legal) {
        StringBuilder sb = new StringBuilder(8);
        int type = typeOf(m.piece());
        if (m.isCastle()) {
            sb.append(m.to() > m.from() ? "O-O" : "O-O-O");
        } else {
            if (type != PAWN) {
                sb.append(PIECE_LETTERS.charAt(type));
                appendDisambiguation(sb, m, legal);
            } else if (m.isCapture()) {
                sb.append(fileOf(m.from()));
            }
            if (m.isCapture()) sb.append('x');
            sb.append(Move.squareName(m.to()));
            if (m.promotion() != 0) sb.append('=').append(PIECE_LETTERS.charAt(m.promotion()));
        }
        Board after = before.copy();
        after.makeMove(m, new Undo());
        if (after.inCheck(after.sideToMove())) {
            sb.append(GEN.generateLegal(after).isEmpty() ? '#' : '+');
        }
        return sb.toString();
    }

    /**
     * FIDE disambiguation: when another piece of the same kind can reach the
     * same square, add the file if it differs, else the rank, else both.
     */
    private static void appendDisambiguation(StringBuilder sb, Move m, List<Move> legal) {
        boolean others = false, sameFile = false, sameRank = false;
        for (Move o : legal) {
            if (o.from() == m.from() || o.to() != m.to() || o.piece() != m.piece()) continue;
            others = true;
            if ((o.from() & 7) == (m.from() & 7)) sameFile = true;
            if ((o.from() >>> 3) == (m.from() >>> 3)) sameRank = true;
        }
        if (!others) return;
        if (!sameFile) sb.append(fileOf(m.from()));
        else if (!sameRank) sb.append(rankOf(m.from()));
        else sb.append(fileOf(m.from())).append(rankOf(m.from()));
    }

    private static char fileOf(int sq) { return (char) ('a' + (sq & 7)); }
    private static char rankOf(int sq) { return (char) ('1' + (sq >>> 3)); }

    /**
     * PGN export of a game played from the standard initial position: the
     * seven-tag roster plus the numbered SAN move text wrapped at 80 columns
     * and terminated by the result token.
     */
    public static String pgn(GameSession session, String white, String black) {
        String result = session.result().pgnToken();
        StringBuilder sb = new StringBuilder();
        sb.append("[Event \"Casual game\"]\n");
        sb.append("[Site \"Chess — Java Swing\"]\n");
        sb.append("[Date \"").append(LocalDate.now().toString().replace('-', '.')).append("\"]\n");
        sb.append("[Round \"-\"]\n");
        sb.append("[White \"").append(escape(white)).append("\"]\n");
        sb.append("[Black \"").append(escape(black)).append("\"]\n");
        sb.append("[Result \"").append(result).append("\"]\n\n");

        StringBuilder line = new StringBuilder();
        List<String> sans = session.sanHistory();
        for (int i = 0; i < sans.size(); i++) {
            String token = (i % 2 == 0 ? (i / 2 + 1) + ". " : "") + sans.get(i);
            if (line.length() + token.length() + 1 > 80) {
                sb.append(line).append('\n');
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(token);
        }
        if (line.length() + result.length() + 1 > 80) {
            sb.append(line).append('\n');
            line.setLength(0);
        }
        if (line.length() > 0) line.append(' ');
        line.append(result);
        sb.append(line).append('\n');
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

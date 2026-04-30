package game;

/** Terminal state of a game session (or ONGOING). */
public enum GameResult {
    ONGOING(""),
    WHITE_WINS_MATE("Checkmate — White wins"),
    BLACK_WINS_MATE("Checkmate — Black wins"),
    WHITE_WINS_TIMEOUT("Black lost on time — White wins"),
    BLACK_WINS_TIMEOUT("White lost on time — Black wins"),
    WHITE_WINS_RESIGNATION("Black resigned — White wins"),
    BLACK_WINS_RESIGNATION("White resigned — Black wins"),
    DRAW_AGREED("Draw — by agreement"),
    DRAW_STALEMATE("Draw — stalemate"),
    DRAW_REPETITION("Draw — threefold repetition"),
    DRAW_FIFTY_MOVES("Draw — fifty-move rule"),
    DRAW_INSUFFICIENT_MATERIAL("Draw — insufficient material"),
    DRAW_TIMEOUT_VS_BARE_KING("Draw — flag fell, but opponent cannot mate");

    private final String message;
    GameResult(String message) { this.message = message; }
    public String message() { return message; }
    public boolean isOver() { return this != ONGOING; }

    /** PGN result token: 1-0, 0-1, 1/2-1/2, or * while ongoing. */
    public String pgnToken() {
        return switch (this) {
            case ONGOING -> "*";
            case WHITE_WINS_MATE, WHITE_WINS_TIMEOUT, WHITE_WINS_RESIGNATION -> "1-0";
            case BLACK_WINS_MATE, BLACK_WINS_TIMEOUT, BLACK_WINS_RESIGNATION -> "0-1";
            default -> "1/2-1/2";
        };
    }
}

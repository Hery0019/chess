package game;

/** Terminal state of a game session (or ONGOING). */
public enum GameResult {
    ONGOING(""),
    WHITE_WINS_MATE("Checkmate — White wins"),
    BLACK_WINS_MATE("Checkmate — Black wins"),
    WHITE_WINS_TIMEOUT("Black lost on time — White wins"),
    BLACK_WINS_TIMEOUT("White lost on time — Black wins"),
    DRAW_STALEMATE("Draw — stalemate"),
    DRAW_REPETITION("Draw — threefold repetition"),
    DRAW_FIFTY_MOVES("Draw — fifty-move rule"),
    DRAW_INSUFFICIENT_MATERIAL("Draw — insufficient material"),
    DRAW_TIMEOUT_VS_BARE_KING("Draw — flag fell, but opponent cannot mate");

    private final String message;
    GameResult(String message) { this.message = message; }
    public String message() { return message; }
    public boolean isOver() { return this != ONGOING; }
}

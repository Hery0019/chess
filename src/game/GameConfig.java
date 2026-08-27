package game;

import engine.Pieces;

/**
 * Immutable game configuration produced by the start screen.
 *
 * @param mode          HUMAN_VS_AI or AI_VS_AI
 * @param humanColor    side the human plays (ignored in AI_VS_AI)
 * @param minutesPerSide chess clock allotment per player, or {@link #NO_CLOCK}
 *                      (0) for an untimed game — no flag fall, clocks show
 *                      elapsed time instead
 * @param aiDepth       maximum search depth, 1..10: iterative deepening stops
 *                      there, or earlier when the time budget runs out
 * @param undoLimit     number of takebacks the human may use in the game
 *                      (Human vs AI), or {@link #NO_UNDO} (0) when Undo is
 *                      switched off for the game
 */
public record GameConfig(Mode mode, int humanColor, int minutesPerSide, int aiDepth, int undoLimit) {

    /** ONLINE: the local human plays {@code humanColor}, the other side is a remote player. */
    public enum Mode { HUMAN_VS_AI, AI_VS_AI, ONLINE }

    /** {@code minutesPerSide} value meaning "no time control". */
    public static final int NO_CLOCK = 0;

    /** Deepest search the start screen offers. */
    public static final int MAX_DEPTH = 10;

    /** {@code undoLimit} value meaning "Undo is disabled for this game". */
    public static final int NO_UNDO = 0;

    /** Takebacks allowed when the start screen is first shown. */
    public static final int DEFAULT_UNDO_LIMIT = 3;

    public GameConfig {
        if (minutesPerSide < NO_CLOCK) throw new IllegalArgumentException("minutes >= 0");
        if (aiDepth < 1 || aiDepth > MAX_DEPTH) throw new IllegalArgumentException("depth 1.." + MAX_DEPTH);
        if (undoLimit < NO_UNDO) throw new IllegalArgumentException("undo limit >= 0");
    }

    /** False for an untimed game. */
    public boolean hasClock() { return minutesPerSide != NO_CLOCK; }

    /** Can moves be taken back at all in this game? Only Human vs AI has takebacks. */
    public boolean undoEnabled() { return mode == Mode.HUMAN_VS_AI && undoLimit != NO_UNDO; }

    /** Is the given engine color controlled by the AI under this config? */
    public boolean isAi(int color) {
        return mode == Mode.AI_VS_AI || (mode == Mode.HUMAN_VS_AI && color != humanColor);
    }

    /** Is the given color played by the human at this machine? */
    public boolean isHuman(int color) {
        return mode != Mode.AI_VS_AI && color == humanColor;
    }

    /** Is the given color played by someone over the network? */
    public boolean isRemote(int color) {
        return mode == Mode.ONLINE && color != humanColor;
    }

    /** Clock allotment in ms; 0 when {@link #hasClock()} is false. */
    public long millisPerSide() { return minutesPerSide * 60_000L; }

    public static String colorName(int color) {
        return color == Pieces.WHITE ? "White" : "Black";
    }
}

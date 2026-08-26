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
 * @param aiDepth       fixed search depth (approved: 1..5, no iterative deepening in v1)
 */
public record GameConfig(Mode mode, int humanColor, int minutesPerSide, int aiDepth) {

    public enum Mode { HUMAN_VS_AI, AI_VS_AI }

    /** {@code minutesPerSide} value meaning "no time control". */
    public static final int NO_CLOCK = 0;

    public GameConfig {
        if (minutesPerSide < NO_CLOCK) throw new IllegalArgumentException("minutes >= 0");
        if (aiDepth < 1 || aiDepth > 6) throw new IllegalArgumentException("depth 1..6");
    }

    /** False for an untimed game. */
    public boolean hasClock() { return minutesPerSide != NO_CLOCK; }

    /** Is the given engine color controlled by the AI under this config? */
    public boolean isAi(int color) {
        return mode == Mode.AI_VS_AI || color != humanColor;
    }

    public boolean isHuman(int color) {
        return mode == Mode.HUMAN_VS_AI && color == humanColor;
    }

    /** Clock allotment in ms; 0 when {@link #hasClock()} is false. */
    public long millisPerSide() { return minutesPerSide * 60_000L; }

    public static String colorName(int color) {
        return color == Pieces.WHITE ? "White" : "Black";
    }
}

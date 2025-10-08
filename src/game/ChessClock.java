package game;

/**
 * Two-sided chess clock. No increment (approved scope).
 *
 * DESIGN (flagged in review, point 10): remaining time is never decremented
 * by timer ticks — Swing timers drift and coalesce under load. Instead the
 * running side's turn start is anchored with {@link System#nanoTime()} and
 * time used is computed on demand: {@code used + (now - anchor)}. The UI's
 * repaint timer merely samples this; its period affects display smoothness
 * only, never timekeeping accuracy.
 *
 * An {@link #unlimited()} clock keeps the same bookkeeping but never expires:
 * {@link #format} then reports time <em>elapsed</em> per side instead of
 * time remaining, so the untimed game still shows how long each side has
 * been thinking.
 *
 * EDT-confined like GameSession; no synchronization by design.
 */
public final class ChessClock {

    private static final long UNLIMITED = -1;

    private final long allotNanos;                  // UNLIMITED for an untimed game
    private final long[] usedNanos = new long[2];
    private int running = -1;       // color whose clock is ticking, or -1
    private long anchorNanos;

    public ChessClock(long millisPerSide) {
        if (millisPerSide < 0) throw new IllegalArgumentException("millisPerSide >= 0");
        this.allotNanos = millisPerSide * 1_000_000L;
    }

    private ChessClock() { this.allotNanos = UNLIMITED; }

    /** A clock that never expires; {@link #format} shows elapsed time per side. */
    public static ChessClock unlimited() { return new ChessClock(); }

    public boolean isUnlimited() { return allotNanos == UNLIMITED; }

    /** Settles the currently running side (if any) and starts {@code color}'s clock. */
    public void startTurn(int color) {
        settle();
        running = color;
        anchorNanos = System.nanoTime();
    }

    /** Stops the clock entirely (game over, pause). Elapsed time is settled first. */
    public void stop() {
        settle();
        running = -1;
    }

    private void settle() {
        if (running != -1) {
            usedNanos[running] += System.nanoTime() - anchorNanos;
            running = -1;
        }
    }

    private long usedNanosLive(int color) {
        long u = usedNanos[color];
        if (running == color) u += System.nanoTime() - anchorNanos;
        return u;
    }

    /** Time this side has consumed so far. */
    public long elapsedMillis(int color) { return usedNanosLive(color) / 1_000_000L; }

    /** Time left, clamped at 0; {@link Long#MAX_VALUE} for an unlimited clock. */
    public long remainingMillis(int color) {
        if (isUnlimited()) return Long.MAX_VALUE;
        return Math.max(0, (allotNanos - usedNanosLive(color)) / 1_000_000L);
    }

    /** Never true for an unlimited clock. */
    public boolean isExpired(int color) { return !isUnlimited() && remainingMillis(color) == 0; }

    public int runningSide() { return running; }

    /**
     * Timed: mm:ss remaining, switching to m:ss.t under ten seconds so time
     * pressure is visible. Unlimited: "\u221E mm:ss" elapsed.
     */
    public String format(int color) {
        if (isUnlimited()) {
            long totalSec = elapsedMillis(color) / 1000;
            return String.format("\u221E %d:%02d", totalSec / 60, totalSec % 60);
        }
        long ms = remainingMillis(color);
        long totalSec = ms / 1000;
        if (totalSec < 10) {
            return String.format("%d:%02d.%d", totalSec / 60, totalSec % 60, (ms % 1000) / 100);
        }
        return String.format("%d:%02d", totalSec / 60, totalSec % 60);
    }
}

package ui;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.function.DoubleUnaryOperator;

/**
 * Tiny sound set for the game, synthesised at start-up so the project
 * ships no binary assets: a soft click for a move, a duller thud for a
 * capture, a bright double ping for check and a short chord when the game
 * ends. Playback is fire-and-forget on pre-opened {@link Clip}s.
 *
 * Audio may be unavailable (headless CI, no mixer, sandboxed JVM): every
 * failure is swallowed and the sounds simply stay silent.
 */
public final class Sounds {

    public enum Kind { MOVE, CAPTURE, CHECK, GAME_END }

    private static final float SAMPLE_RATE = 22_050f;
    private static final Map<Kind, Clip> CLIPS = new EnumMap<>(Kind.class);
    private static volatile boolean enabled = true;
    private static boolean initialised = false;

    private Sounds() {}

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean on) { enabled = on; }

    /** Plays a sound if audio is available and sounds are enabled. Never throws. */
    public static void play(Kind kind) {
        if (!enabled) return;
        try {
            init();
            Clip c = CLIPS.get(kind);
            if (c == null) return;
            if (c.isRunning()) c.stop();
            c.setFramePosition(0);
            c.start();
        } catch (Throwable ignored) {
            // No audio: stay silent.
        }
    }

    private static synchronized void init() {
        if (initialised) return;
        initialised = true;
        Random noise = new Random(1);
        add(Kind.MOVE, 0.09, t -> Math.sin(2 * Math.PI * 900 * t) * Math.exp(-t * 60) * 0.6
                                 + (noise.nextDouble() * 2 - 1) * Math.exp(-t * 140) * 0.25);
        add(Kind.CAPTURE, 0.16, t -> Math.sin(2 * Math.PI * 380 * t) * Math.exp(-t * 30) * 0.7
                                    + (noise.nextDouble() * 2 - 1) * Math.exp(-t * 45) * 0.35);
        add(Kind.CHECK, 0.40, t -> {
            double env = Math.exp(-t * 9) * (t < 0.12 || t > 0.16 ? 1 : 0.15);   // two pings
            return (Math.sin(2 * Math.PI * 1175 * t) + 0.5 * Math.sin(2 * Math.PI * 1760 * t)) * env * 0.35;
        });
        add(Kind.GAME_END, 0.9, t -> (Math.sin(2 * Math.PI * 523.25 * t) + Math.sin(2 * Math.PI * 659.25 * t)
                                    + Math.sin(2 * Math.PI * 783.99 * t)) / 3 * Math.exp(-t * 3.2) * 0.5);
    }

    private static void add(Kind kind, double seconds, DoubleUnaryOperator wave) {
        try {
            int frames = (int) (seconds * SAMPLE_RATE);
            byte[] pcm = new byte[frames * 2];
            for (int i = 0; i < frames; i++) {
                double v = Math.max(-1, Math.min(1, wave.applyAsDouble(i / SAMPLE_RATE)));
                // Short fade-in/out removes clicks at the clip edges.
                int edge = Math.min(i, frames - 1 - i);
                if (edge < 40) v *= edge / 40.0;
                short s = (short) Math.round(v * Short.MAX_VALUE);
                pcm[2 * i] = (byte) s;
                pcm[2 * i + 1] = (byte) (s >>> 8);
            }
            AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            Clip clip = AudioSystem.getClip();
            clip.open(fmt, pcm, 0, pcm.length);
            CLIPS.put(kind, clip);
        } catch (Throwable ignored) {
            // Leave this sound out; play() treats a missing clip as silence.
        }
    }
}

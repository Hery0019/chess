package ui;

import game.GameConfig;

import java.awt.Rectangle;
import java.util.prefs.Preferences;

/**
 * Small persistent preferences (window placement, sound switch, last
 * start-screen settings) on top of {@link Preferences}. Every accessor is
 * failure-tolerant: a missing or unreadable store just yields defaults.
 */
final class Prefs {

    private static final Preferences P = Preferences.userRoot().node("chess-swing");

    private Prefs() {}

    static Rectangle windowBounds() {
        try {
            int x = P.getInt("win.x", Integer.MIN_VALUE), y = P.getInt("win.y", Integer.MIN_VALUE);
            int w = P.getInt("win.w", -1), h = P.getInt("win.h", -1);
            if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || w < 200 || h < 200) return null;
            return new Rectangle(x, y, w, h);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static void saveWindowBounds(Rectangle r) {
        try {
            P.putInt("win.x", r.x);
            P.putInt("win.y", r.y);
            P.putInt("win.w", r.width);
            P.putInt("win.h", r.height);
        } catch (RuntimeException ignored) { }
    }

    static boolean soundEnabled() {
        try { return P.getBoolean("sound", true); } catch (RuntimeException e) { return true; }
    }

    static void setSoundEnabled(boolean on) {
        try { P.putBoolean("sound", on); } catch (RuntimeException ignored) { }
    }

    /** The configuration last used to start a game, or null. */
    static GameConfig lastConfig() {
        try {
            String mode = P.get("cfg.mode", null);
            if (mode == null) return null;
            return new GameConfig(GameConfig.Mode.valueOf(mode), P.getInt("cfg.human", 0),
                    P.getInt("cfg.minutes", GameConfig.NO_CLOCK), P.getInt("cfg.level", engine.Skill.DEFAULT),
                    P.getInt("cfg.undo", GameConfig.DEFAULT_UNDO_LIMIT));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String onlineName() {
        try { return P.get("online.name", System.getProperty("user.name", "Player")); }
        catch (RuntimeException e) { return "Player"; }
    }

    static String onlineAddress() {
        try { return P.get("online.address", "localhost:" + net.Protocol.DEFAULT_PORT); }
        catch (RuntimeException e) { return "localhost:" + net.Protocol.DEFAULT_PORT; }
    }

    static void saveOnline(String name, String address) {
        try {
            P.put("online.name", name);
            P.put("online.address", address);
        } catch (RuntimeException ignored) { }
    }

    static void saveLastConfig(GameConfig c) {
        try {
            P.put("cfg.mode", c.mode().name());
            P.putInt("cfg.human", c.humanColor());
            P.putInt("cfg.minutes", c.minutesPerSide());
            P.putInt("cfg.level", c.aiLevel());
            P.putInt("cfg.undo", c.undoLimit());
        } catch (RuntimeException ignored) { }
    }
}

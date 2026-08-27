package game;

import engine.Move;
import engine.Pieces;

import java.util.ArrayList;
import java.util.List;

/**
 * A game in progress, serialised to a small line-oriented text file so it
 * can be resumed later: the configuration, the time each side has used,
 * and the moves in long algebraic notation. Moves are replayed through
 * {@link GameSession} on load, so a tampered file can only fail loudly,
 * never produce an illegal position.
 *
 * <pre>
 * chess-save 1
 * mode HUMAN_VS_AI
 * human WHITE
 * minutes 10
 * depth 4
 * used 12345 6789
 * moves e2e4 e7e5 g1f3
 * </pre>
 */
public record SavedGame(GameConfig config, List<String> moves, long whiteUsedMillis, long blackUsedMillis) {

    public static final String HEADER = "chess-save 1";

    public SavedGame {
        moves = List.copyOf(moves);
        if (whiteUsedMillis < 0 || blackUsedMillis < 0) throw new IllegalArgumentException("used time >= 0");
    }

    /** Snapshot of a live session and clock. */
    public static SavedGame of(GameConfig config, GameSession session, ChessClock clock) {
        List<String> lan = new ArrayList<>();
        for (Move m : session.history()) lan.add(m.toString());
        return new SavedGame(config, lan, clock.elapsedMillis(Pieces.WHITE), clock.elapsedMillis(Pieces.BLACK));
    }

    public String serialize() {
        return HEADER + "\n"
                + "mode " + config.mode() + "\n"
                + "human " + GameConfig.colorName(config.humanColor()).toUpperCase() + "\n"
                + "minutes " + config.minutesPerSide() + "\n"
                + "depth " + config.aiDepth() + "\n"
                + "used " + whiteUsedMillis + " " + blackUsedMillis + "\n"
                + "moves " + String.join(" ", moves) + "\n";
    }

    /** @throws IllegalArgumentException on any malformed content */
    public static SavedGame parse(String text) {
        String[] lines = text.strip().split("\\R");
        if (lines.length < 7 || !lines[0].strip().equals(HEADER)) {
            throw new IllegalArgumentException("not a chess save file");
        }
        GameConfig.Mode mode = null;
        int human = -1, minutes = -1, depth = -1;
        long whiteUsed = -1, blackUsed = -1;
        List<String> moves = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) continue;
            int sp = line.indexOf(' ');
            String key = sp < 0 ? line : line.substring(0, sp);
            String value = sp < 0 ? "" : line.substring(sp + 1).strip();
            try {
                switch (key) {
                    case "mode" -> mode = GameConfig.Mode.valueOf(value);
                    case "human" -> human = switch (value) {
                        case "WHITE" -> Pieces.WHITE;
                        case "BLACK" -> Pieces.BLACK;
                        default -> throw new IllegalArgumentException("bad colour " + value);
                    };
                    case "minutes" -> minutes = Integer.parseInt(value);
                    case "depth" -> depth = Integer.parseInt(value);
                    case "used" -> {
                        String[] parts = value.split("\\s+");
                        whiteUsed = Long.parseLong(parts[0]);
                        blackUsed = Long.parseLong(parts[1]);
                    }
                    case "moves" -> moves = value.isEmpty() ? List.of() : List.of(value.split("\\s+"));
                    default -> throw new IllegalArgumentException("unknown key " + key);
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("bad save line " + (i + 1) + ": " + line, e);
            }
        }
        if (mode == null || human < 0 || minutes < 0 || depth < 0 || whiteUsed < 0 || moves == null) {
            throw new IllegalArgumentException("incomplete save file");
        }
        for (String m : moves) {
            if (!m.matches("[a-h][1-8][a-h][1-8][nbrq]?")) throw new IllegalArgumentException("bad move token " + m);
        }
        return new SavedGame(new GameConfig(mode, human, minutes, depth), moves, whiteUsed, blackUsed);
    }
}

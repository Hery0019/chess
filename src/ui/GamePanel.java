package ui;

import engine.Board;
import engine.Move;
import engine.OpeningBook;
import engine.Skill;
import engine.Pieces;
import engine.Search;
import game.ChessClock;
import game.GameConfig;
import game.GameSession;
import game.Notation;
import game.SavedGame;
import net.ChessClient;
import net.Protocol;
import net.Protocol.Message;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One live game: board view, two clocks, status line, move list, controls,
 * and either the AI worker lifecycle or the online opponent. All game-state
 * mutation happens on the EDT; the worker thread only ever reads a private
 * {@link Board#copy()}, and network messages arrive on the EDT too.
 *
 * CONCURRENCY DESIGN (reviewed, points 8-9):
 *  - The 100 ms Swing timer is a *sampler*: it repaints clock labels and
 *    checks expiry. Timekeeping itself is nanoTime-anchored in ChessClock, so
 *    timer drift cannot corrupt the clocks.
 *  - Exactly one AiWorker may be live at a time ({@code activeWorker}).
 *  - Stale-worker race: a worker may complete after the game ended (timeout,
 *    resignation via New Game) or after this panel was replaced. done()
 *    therefore re-checks that (a) this worker is still the active one,
 *    (b) the search wasn't cancelled, (c) the game is still ongoing. The
 *    session's own legality validation is the final backstop.
 *  - Cancellation is cooperative: the search polls an AtomicBoolean. We never
 *    interrupt or kill the thread; a cancelled worker returns null quickly
 *    and its result is discarded. The shared Search is synchronized, so a
 *    late worker simply finishes before the next one starts.
 *
 * ONLINE games: our moves are applied locally and sent; the opponent's
 * arrive as MOVE messages already validated by the server and are applied
 * through the same session (which re-validates). Each side runs its own
 * clock; a player reports their <em>own</em> flag fall (TIMEOUT), which
 * avoids disputes between two honest clients. The client connection is
 * owned by {@link MainFrame}, which routes messages here.
 */
public final class GamePanel extends JPanel {

    /** What the panel needs from the application shell. */
    public interface Host {
        /** Back to the start screen (closes any online session). */
        void newGame();
        /** Start a fresh local game with this configuration (rematch). */
        void startGame(GameConfig config);
    }

    private static final int TIMER_PERIOD_MS = 100;
    /** Minimum wall time per AI move in AI-vs-AI, so games are watchable. */
    private static final long AI_VS_AI_MIN_MOVE_MS = 500;
    /** Untimed games: the fixed depth decides, this only stops a runaway think. */
    private static final long UNTIMED_BUDGET_MS = 15_000;
    /** Clocked games: spend about 1/30 of the remaining time, within bounds. */
    private static final long CLOCK_FRACTION = 30, MIN_BUDGET_MS = 100, MAX_BUDGET_MS = 8_000;

    private final GameConfig config;
    private final Host host;
    private final GameSession session;
    private final ChessClock clock;
    /** One engine (and transposition table) per game; findBest is synchronized. */
    private final Search engine = new Search();
    private String lastAiInfo;
    private final BoardPanel boardPanel;
    private final JLabel topClockLabel = new JLabel();
    private final JLabel bottomClockLabel = new JLabel();
    private final JLabel topNameLabel = new JLabel();
    private final JLabel bottomNameLabel = new JLabel();
    private final JLabel statusLabel = new JLabel();
    private final JButton pauseButton = new JButton("Pause");
    private final JButton undoButton = new JButton("Undo");
    private final JButton resignButton = new JButton("Resign");
    private final JButton drawButton = new JButton("Offer draw");
    /** Score of the AI's last real search from its own point of view; null after a book move. */
    private Integer lastAiScore;
    private String notice;
    private long noticeUntilMillis;
    private final MoveTableModel moveModel = new MoveTableModel();
    private final JTable moveTable = new JTable(moveModel);
    private final Timer uiTimer;

    // ---- online ----
    private final ChessClient online;          // null for local games
    private final String[] names = new String[2];
    private boolean onlineGone;                // opponent left or connection dropped
    private boolean rematchRequested;
    private boolean opponentWantsRematch;

    private int topColor;            // color shown at the top of the window
    private AiWorker activeWorker;
    private boolean paused = false;
    private boolean disposed = false;
    private boolean endDialogShown = false;
    /** Takebacks spent so far; the game allows {@code config.undoLimit()} of them. */
    private int takebacksUsed;
    private int shownTakebacksLeft = -1;   // allowance last rendered on the Undo button

    /** Local game (fresh or resumed). */
    public GamePanel(GameConfig config, SavedGame saved, Host host) {
        this(config, saved, host, null, null, null);
    }

    /**
     * @param saved     a game to resume, or null for a fresh one
     * @param online    the connection for an ONLINE game, else null
     * @param whiteName player names for an online game (shown and used in PGN)
     * @throws IllegalArgumentException if the saved moves are not a legal sequence
     */
    public GamePanel(GameConfig config, SavedGame saved, Host host, ChessClient online,
                     String whiteName, String blackName) {
        super(new BorderLayout(8, 8));
        this.config = config;
        this.host = host;
        this.online = online;
        this.names[Pieces.WHITE] = whiteName;
        this.names[Pieces.BLACK] = blackName;
        this.session = new GameSession();
        // Untimed games keep a clock object so the tick/pause/label flow is
        // unchanged; it simply never expires and displays elapsed time.
        this.clock = config.hasClock() ? new ChessClock(config.millisPerSide()) : ChessClock.unlimited();
        if (saved != null) {
            for (String lan : saved.moves()) {
                Move move = null;
                for (Move m : session.legalMoves()) if (m.toString().equals(lan)) { move = m; break; }
                if (move == null) throw new IllegalArgumentException("saved game: illegal move " + lan);
                session.applyMove(move);
            }
            clock.restoreUsed(saved.whiteUsedMillis(), saved.blackUsedMillis());
            takebacksUsed = saved.undoUsed();
        }

        boolean flipped = config.isHuman(Pieces.BLACK);
        this.topColor = flipped ? Pieces.WHITE : Pieces.BLACK;
        // Premoves are only meaningful when a human is waiting on the other side.
        int humanColor = config.mode() == GameConfig.Mode.AI_VS_AI ? -1 : config.humanColor();
        this.boardPanel = new BoardPanel(session, flipped, humanColor, this::onHumanMove);

        Font clockFont = new Font(Font.MONOSPACED, Font.BOLD, 22);
        topClockLabel.setFont(clockFont);
        bottomClockLabel.setFont(clockFont);
        if (!config.hasClock()) {
            String tip = "Untimed game — showing time used by this side";
            topClockLabel.setToolTipText(tip);
            bottomClockLabel.setToolTipText(tip);
        }
        statusLabel.setFont(statusLabel.getFont().deriveFont(14f));

        updateNameLabels();
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(topNameLabel);
        top.add(topClockLabel);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottom.add(bottomNameLabel);
        bottom.add(bottomClockLabel);
        bottom.add(statusLabel);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(top, BorderLayout.NORTH);
        center.add(boardPanel, BorderLayout.CENTER);
        center.add(bottom, BorderLayout.SOUTH);

        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(center, BorderLayout.CENTER);
        add(buildSidePanel(), BorderLayout.EAST);

        uiTimer = new Timer(TIMER_PERIOD_MS, e -> onTick());
    }

    private boolean isOnline() { return config.mode() == GameConfig.Mode.ONLINE; }

    private int opponentColor() { return config.humanColor() ^ 1; }

    /** Move list plus the game controls, to the right of the board. */
    private JComponent buildSidePanel() {
        JPanel side = new JPanel(new BorderLayout(0, 8));
        side.setPreferredSize(new Dimension(250, 0));

        JLabel title = new JLabel("Moves");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        title.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 0));
        JCheckBox sound = new JCheckBox("Sound", Sounds.isEnabled());
        sound.setFocusable(false);
        sound.setOpaque(false);
        sound.addActionListener(e -> Sounds.setEnabled(sound.isSelected()));
        JPanel header = new JPanel(new BorderLayout());
        header.add(title, BorderLayout.WEST);
        header.add(sound, BorderLayout.EAST);

        moveTable.setRowHeight(22);
        moveTable.setShowGrid(false);
        moveTable.setIntercellSpacing(new Dimension(0, 0));
        moveTable.setFillsViewportHeight(true);
        moveTable.setRowSelectionAllowed(false);
        moveTable.setFocusable(false);
        moveTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        moveTable.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        moveTable.getTableHeader().setReorderingAllowed(false);
        moveTable.getColumnModel().getColumn(0).setMaxWidth(44);
        moveTable.setDefaultRenderer(Object.class, new MoveCellRenderer());
        JScrollPane scroll = new JScrollPane(moveTable);

        boolean aiVsAi = config.mode() == GameConfig.Mode.AI_VS_AI;
        boolean canUndo = config.undoEnabled();   // Human vs AI with takebacks switched on
        pauseButton.setVisible(aiVsAi);
        pauseButton.addActionListener(e -> togglePause());
        undoButton.setVisible(canUndo);
        undoButton.addActionListener(e -> undo());
        JButton flipBoard = new JButton("Flip Board");
        flipBoard.addActionListener(e -> flipBoard());
        resignButton.setVisible(!aiVsAi);
        resignButton.addActionListener(e -> resign());
        drawButton.setVisible(!aiVsAi);
        drawButton.setToolTipText(isOnline() ? "Offer your opponent a draw"
                : "The AI accepts when its own evaluation is not better than equal");
        drawButton.addActionListener(e -> offerDraw());
        JButton exportPgn = new JButton("Export PGN…");
        exportPgn.addActionListener(e -> exportPgn());
        JButton save = new JButton("Save…");
        save.setToolTipText("Save this game to resume it later");
        save.addActionListener(e -> saveGame());
        save.setVisible(!isOnline());
        JButton newGame = new JButton(isOnline() ? "Leave" : "New Game");
        newGame.addActionListener(e -> host.newGame());

        JPanel buttons = new JPanel(new GridLayout(0, 2, 6, 6));
        if (aiVsAi) buttons.add(pauseButton);
        if (canUndo) buttons.add(undoButton);
        buttons.add(flipBoard);
        if (!aiVsAi) {
            buttons.add(resignButton);
            buttons.add(drawButton);
        }
        if (!isOnline()) buttons.add(save);
        buttons.add(exportPgn);
        buttons.add(newGame);

        // Ctrl+Z (Cmd+Z on macOS) anywhere in the window takes back a move.
        int shortcutMask;
        try {
            shortcutMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        } catch (java.awt.HeadlessException e) {
            shortcutMask = java.awt.event.InputEvent.CTRL_DOWN_MASK;   // headless tests
        }
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcutMask), "undo");
        getActionMap().put("undo", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { if (undoButton.isVisible()) undo(); }
        });

        side.add(header, BorderLayout.NORTH);
        side.add(scroll, BorderLayout.CENTER);
        side.add(buttons, BorderLayout.SOUTH);
        return side;
    }

    private String playerName(int color) {
        String base = GameConfig.colorName(color);
        if (isOnline()) return names[color] + " (" + base + (color == config.humanColor() ? ", you)" : ")");
        return config.isAi(color) ? base + " (AI, " + Skill.eloLabel(config.aiLevel()) + ")" : base;
    }

    /** Name for the PGN tags: who actually played the side. */
    private String pgnName(int color) {
        if (isOnline()) return names[color];
        return config.isAi(color) ? "AI (level " + config.aiLevel() + ", " + Skill.eloLabel(config.aiLevel()) + ")" : "Human";
    }

    private void updateNameLabels() {
        topNameLabel.setText(playerName(topColor) + ":");
        bottomNameLabel.setText(playerName(topColor ^ 1) + ":");
    }

    /** Rotates the board view 180° and swaps the clock/name rows to match.
     *  Pure view change: game state, clocks and interaction are untouched. */
    private void flipBoard() {
        topColor ^= 1;
        boardPanel.setFlipped(topColor == Pieces.WHITE);
        updateNameLabels();
        refresh();
    }

    private void exportPgn() {
        writeToChosenFile("Export PGN", "game.pgn",
                Notation.pgn(session, pgnName(Pieces.WHITE), pgnName(Pieces.BLACK)));
    }

    private void saveGame() {
        writeToChosenFile("Save game", "game.chess", SavedGame.of(config, session, clock, takebacksUsed).serialize());
    }

    private void writeToChosenFile(String title, String defaultName, String content) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setSelectedFile(new java.io.File(defaultName));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path target = chooser.getSelectedFile().toPath();
        try {
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not write " + target + ":\n" + ex.getMessage(),
                    title, JOptionPane.ERROR_MESSAGE);
        }
    }

    private void resign() {
        if (session.result().isOver() || config.mode() == GameConfig.Mode.AI_VS_AI) return;
        int answer = JOptionPane.showConfirmDialog(this, "Resign this game?", "Resign",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.YES_OPTION || session.result().isOver()) return;
        cancelWorker();
        if (online != null) online.resign();
        session.resign(config.humanColor());
        Sounds.play(Sounds.Kind.GAME_END);
        endGame();
        refresh();
    }

    /**
     * Against the AI: accepted when its last search did not rate its own
     * position as better than roughly equal (+0.30) and the game has left the
     * opening; otherwise declined in the status line. Online: the offer is
     * sent and the opponent decides.
     */
    private void offerDraw() {
        if (session.result().isOver()) return;
        if (online != null) {
            if (onlineGone) return;
            online.offerDraw();
            notice("Draw offered — waiting for " + names[opponentColor()], 6000);
            refresh();
            return;
        }
        if (!config.isHuman(session.sideToMove()) || activeWorker != null) return;
        boolean accepts = lastAiScore != null && lastAiScore <= 30 && session.plyCount() >= 20;
        if (accepts) {
            session.agreeDraw();
            Sounds.play(Sounds.Kind.GAME_END);
            endGame();
        } else {
            notice("Draw offer declined", 3000);
        }
        refresh();
    }

    private void notice(String text, long millis) {
        notice = text;
        noticeUntilMillis = System.currentTimeMillis() + millis;
    }

    /** Shown once per game end (after the last slide): rematch, new game, or stay and review. */
    private void showEndDialog() {
        if (disposed || !session.result().isOver() || !canShowDialogs()) return;
        boolean canRematch = !isOnline() || !onlineGone;
        String[] options = canRematch ? new String[]{"Rematch", isOnline() ? "Leave" : "New Game", "Review"}
                                      : new String[]{"Leave", "Review"};
        int choice = JOptionPane.showOptionDialog(this, session.result().message(), "Game over",
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
        String picked = choice < 0 ? "Review" : options[choice];
        switch (picked) {
            case "Rematch" -> {
                if (isOnline()) requestRematch();
                else host.startGame(rematchConfig());
            }
            case "New Game", "Leave" -> host.newGame();
            default -> { }
        }
    }

    private void requestRematch() {
        if (online == null || onlineGone || rematchRequested) return;
        rematchRequested = true;
        online.requestRematch();
        notice(opponentWantsRematch ? "Starting the rematch…" : "Rematch requested — waiting for " + names[opponentColor()], 10_000);
        refresh();
    }

    /** Same settings, colours swapped (Human vs AI). */
    private GameConfig rematchConfig() {
        if (config.mode() != GameConfig.Mode.HUMAN_VS_AI) return config;
        return new GameConfig(config.mode(), config.humanColor() ^ 1, config.minutesPerSide(), config.aiLevel(),
                config.undoLimit());
    }

    /** Called once by MainFrame after the panel is shown. */
    public void startGame() {
        clock.startTurn(session.sideToMove());
        refresh();
        uiTimer.start();
        if (session.result().isOver()) endGame();   // a resumed game that had already finished
        else maybeStartAi();
    }

    /** Stops timers and abandons any in-flight search. Idempotent. The online connection stays with MainFrame. */
    public void dispose() {
        disposed = true;
        uiTimer.stop();
        boardPanel.stopAnimation();
        cancelWorker();
    }

    private void cancelWorker() {
        if (activeWorker != null) {
            activeWorker.cancelFlag.set(true);
            activeWorker = null;
        }
    }

    // ---- move flow ----

    private void onHumanMove(Move m) {
        if (session.result().isOver() || !config.isHuman(session.sideToMove())) return;
        playLocalMove(m);
    }

    /** Applies one of our own moves (clicked, dropped or premoved) and sends it online if needed. */
    private void playLocalMove(Move m) {
        session.applyMove(m);
        if (online != null) online.sendMove(m);
        afterMoveApplied();
    }

    private void afterMoveApplied() {
        playMoveSound();
        if (session.result().isOver()) {
            endGame();
        } else {
            clock.startTurn(session.sideToMove());
            maybeStartAi();
        }
        refresh();
    }

    private void playMoveSound() {
        Move last = session.lastMove();
        if (last == null) return;
        Sounds.Kind kind = session.result().isOver() ? Sounds.Kind.GAME_END
                         : session.inCheckNow()      ? Sounds.Kind.CHECK
                         : last.isCapture()          ? Sounds.Kind.CAPTURE
                         : Sounds.Kind.MOVE;
        Sounds.play(kind);
    }

    private void endGame() {
        clock.stop();
        boardPanel.setInteractionEnabled(false);
        // The timer keeps running only to no purpose; stop it.
        uiTimer.stop();
        if (!endDialogShown) {
            endDialogShown = true;
            Timer t = new Timer(BoardPanel.ANIMATION_MS + 250, e -> showEndDialog());
            t.setRepeats(false);
            t.start();
        }
    }

    /** Takebacks still available in this game: 0 when Undo is off or the allowance is spent. */
    private int takebacksLeft() {
        return config.undoEnabled() ? Math.max(0, config.undoLimit() - takebacksUsed) : 0;
    }

    /** Number of plies a takeback removes right now, 0 when nothing can be taken back. */
    private int undoPlies() {
        if (takebacksLeft() == 0) return 0;
        int n = session.plyCount();
        boolean humanToMove = session.sideToMove() == config.humanColor();
        if (humanToMove && !session.result().isOver()) {
            // Our turn: take back the AI's reply and our move before it.
            return n >= 2 ? 2 : 0;
        }
        // AI to move (thinking, or the game just ended on the AI's or our
        // move): take back up to and including our last move.
        int plies = session.sideToMove() == config.humanColor() ? 2 : 1;
        return n >= plies ? plies : 0;
    }

    /**
     * Takeback (Human vs AI only). Cancels a search in progress, removes the
     * last human move together with the AI reply that followed it (if any),
     * and resumes the game from the restored position — including a game
     * that had already ended. Every takeback counts against the allowance
     * chosen on the start screen ({@link GameConfig#undoLimit()}).
     */
    private void undo() {
        int plies = undoPlies();
        if (plies == 0) {
            if (config.undoEnabled() && takebacksLeft() == 0) {   // Ctrl+Z once the allowance is spent
                notice("No takebacks left — this game allows " + config.undoLimit(), 4_000);
                refresh();
            }
            return;
        }
        takebacksUsed++;
        cancelWorker();
        boolean wasOver = session.result().isOver();
        for (int i = 0; i < plies; i++) session.undoLastMove();
        boardPanel.stopAnimation();
        boardPanel.cancelPremove();
        boardPanel.clearSelection();
        lastAiInfo = null;
        if (wasOver) {
            endDialogShown = false;   // the game may end again
            if (!uiTimer.isRunning()) uiTimer.start();
        }
        clock.startTurn(session.sideToMove());
        maybeStartAi();
        refresh();
    }

    /**
     * Reacts to the side to move: enables the board for the local human
     * (and schedules a queued premove), waits for the network opponent, or
     * starts the AI worker.
     */
    private void maybeStartAi() {
        if (session.result().isOver() || paused) return;
        int stm = session.sideToMove();
        if (config.isHuman(stm)) {
            boardPanel.setInteractionEnabled(true);
            // A premove entered while waiting is played once the opponent's
            // move has finished sliding, so both moves are visible.
            if (boardPanel.hasPremove()) {
                int delay = boardPanel.isAnimating() ? BoardPanel.ANIMATION_MS + 40 : 1;
                Timer t = new Timer(delay, e -> playPremove());
                t.setRepeats(false);
                t.start();
            }
            return;
        }
        boardPanel.setInteractionEnabled(false);
        if (config.isRemote(stm)) return;   // the opponent's move will arrive over the network
        if (activeWorker != null) return;   // one at a time; defensive
        long minMillis = config.mode() == GameConfig.Mode.AI_VS_AI ? AI_VS_AI_MIN_MOVE_MS : 0;
        activeWorker = new AiWorker(session.board().copy(), session.priorPositionKeys(),
                config.aiLevel(), timeBudgetMillis(stm), minMillis);
        activeWorker.execute();
        refresh();
    }

    /**
     * Plays the held premove if it is legal in the current position (the
     * user may have moved or cancelled in the meantime — then nothing
     * happens). Goes through the same path as a clicked move.
     */
    private void playPremove() {
        if (disposed || session.result().isOver() || paused) return;
        if (!config.isHuman(session.sideToMove()) || activeWorker != null) return;
        Move premove = boardPanel.consumePremove();
        if (premove == null) return;
        boardPanel.animate(premove);
        playLocalMove(premove);
    }

    private long timeBudgetMillis(int color) {
        if (!config.hasClock()) return UNTIMED_BUDGET_MS;
        long budget = clock.remainingMillis(color) / CLOCK_FRACTION;
        return Math.max(MIN_BUDGET_MS, Math.min(budget, MAX_BUDGET_MS));
    }

    /** One-line summary of a finished search, eval from White's point of view. */
    private static String describe(Search.Result r, int aiColor) {
        if (r.depth() == 0) return "book move";
        int whiteScore = aiColor == Pieces.WHITE ? r.score() : -r.score();
        String eval;
        if (r.isMate()) {
            int m = aiColor == Pieces.WHITE ? r.mateIn() : -r.mateIn();
            eval = (m > 0 ? "+M" : "-M") + Math.abs(m);
        } else {
            eval = String.format("%+.2f", whiteScore / 100.0);
        }
        String nodes = r.nodes() >= 1_000_000 ? String.format("%.1fM", r.nodes() / 1e6)
                     : r.nodes() >= 1_000     ? (r.nodes() / 1_000) + "k"
                     : String.valueOf(r.nodes());
        return String.format("eval %s · depth %d · %s nodes · %.1fs",
                eval, r.depth(), nodes, r.millis() / 1000.0);
    }

    private void togglePause() {
        if (session.result().isOver()) return;
        paused = !paused;
        pauseButton.setText(paused ? "Resume" : "Pause");
        if (paused) {
            // Abandon the in-flight search rather than freezing mid-think:
            // the position is cheap to re-search on resume, and "paused"
            // must mean the clock is genuinely stopped.
            cancelWorker();
            clock.stop();
        } else {
            clock.startTurn(session.sideToMove());
            maybeStartAi();
        }
        refresh();
    }

    // ---- online messages (routed here by MainFrame, on the EDT) ----

    /** Everything except START, which MainFrame turns into a new panel. */
    public void onOnlineMessage(Message m) {
        if (online == null || disposed) return;
        int opp = opponentColor();
        boolean over = session.result().isOver();
        switch (m.type()) {
            case Protocol.MOVE -> {
                if (over || !config.isRemote(session.sideToMove())) return;
                Move move = null;
                for (Move legal : session.legalMoves()) if (legal.toString().equals(m.arg(0))) { move = legal; break; }
                if (move == null) {
                    desync("The server sent a move that is not legal here: " + m.arg(0));
                    return;
                }
                session.applyMove(move);
                boardPanel.animate(move);
                afterMoveApplied();
            }
            case Protocol.RESIGN -> {
                if (!over) { session.resign(opp); Sounds.play(Sounds.Kind.GAME_END); endGame(); }
                refresh();
            }
            case Protocol.DRAW_OFFER -> {
                if (over) return;
                if (!canShowDialogs()) { online.declineDraw(); return; }
                int answer = JOptionPane.showConfirmDialog(this, names[opp] + " offers a draw. Accept?",
                        "Draw offer", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (session.result().isOver()) return;   // ended while the dialog was open
                if (answer == JOptionPane.YES_OPTION) {
                    online.acceptDraw();
                    session.agreeDraw();
                    Sounds.play(Sounds.Kind.GAME_END);
                    endGame();
                } else {
                    online.declineDraw();
                }
                refresh();
            }
            case Protocol.DRAW_ACCEPT -> {
                if (!over) { session.agreeDraw(); Sounds.play(Sounds.Kind.GAME_END); endGame(); }
                refresh();
            }
            case Protocol.DRAW_DECLINE -> { notice(names[opp] + " declined the draw", 4000); refresh(); }
            case Protocol.TIMEOUT -> {
                if (!over) { session.timeout(opp); Sounds.play(Sounds.Kind.GAME_END); endGame(); }
                refresh();
            }
            case Protocol.REMATCH -> {
                opponentWantsRematch = true;
                if (rematchRequested) { notice("Starting the rematch…", 5000); refresh(); return; }
                if (!canShowDialogs()) { notice(names[opp] + " wants a rematch", 8000); refresh(); return; }
                int answer = JOptionPane.showConfirmDialog(this, names[opp] + " wants a rematch. Accept?",
                        "Rematch", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (answer == JOptionPane.YES_OPTION) requestRematch();
                else notice("Rematch declined — " + names[opp] + " is still waiting", 6000);
                refresh();
            }
            case Protocol.OPPONENT_LEFT -> {
                onlineGone = true;
                if (!over) { session.abandon(opp); Sounds.play(Sounds.Kind.GAME_END); endGame(); }
                else notice(names[opp] + " left the game", 8000);
                refresh();
            }
            case Protocol.ERROR -> {
                String text = String.join(" ", m.args());
                if (text.contains("illegal") || text.contains("turn")) desync("Server refused our move: " + text);
                else { notice("Server: " + text, 6000); refresh(); }
            }
            default -> { }
        }
    }

    public void onOnlineDisconnected(String reason) {
        if (disposed) return;
        onlineGone = true;
        if (!session.result().isOver()) {
            session.abort();
            endGame();
        }
        notice("Disconnected: " + reason, 15_000);
        refresh();
        if (canShowDialogs()) {
            JOptionPane.showMessageDialog(this, "Connection lost: " + reason, "Online game", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** The two ends disagree about the game: stop rather than continue on a wrong position. */
    private void desync(String detail) {
        System.err.println("online desync: " + detail + " (moves so far: " + String.join(" ", session.sanHistory()) + ")");
        onlineGone = true;
        if (!session.result().isOver()) {
            session.abort();
            endGame();
        }
        notice(detail, 15_000);
        refresh();
        if (canShowDialogs()) {
            JOptionPane.showMessageDialog(this, detail + "\nThe game has been aborted.", "Online game",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Dialogs are skipped in headless runs (tests); the status line carries the message instead. */
    private static boolean canShowDialogs() { return !java.awt.GraphicsEnvironment.isHeadless(); }

    // ---- clock tick ----

    private void onTick() {
        if (!session.result().isOver() && !paused) {
            int running = clock.runningSide();
            if (running != -1 && clock.isExpired(running) && !config.isRemote(running)) {
                // Our own (or the local AI's) flag fell. A remote opponent
                // reports their own flag; we never adjudicate it for them.
                cancelWorker();
                session.timeout(running);
                if (online != null) online.reportTimeout();
                Sounds.play(Sounds.Kind.GAME_END);
                endGame();
            }
        }
        refresh();
    }

    private void refresh() {
        topClockLabel.setText(clock.format(topColor));
        bottomClockLabel.setText(clock.format(topColor ^ 1));
        String status;
        if (session.result().isOver()) {
            status = session.result().message();
        } else if (paused) {
            status = "Paused";
        } else if (activeWorker != null) {
            status = "AI is thinking…";
            if (boardPanel.hasPremove()) status += "   (premove: " + boardPanel.premoveText() + ")";
        } else if (isOnline() && config.isRemote(session.sideToMove())) {
            status = "Waiting for " + names[opponentColor()] + "…";
            if (boardPanel.hasPremove()) status += "   (premove: " + boardPanel.premoveText() + ")";
        } else {
            status = session.statusText();
            if (lastAiInfo != null) status += "   ·   AI " + lastAiInfo;
        }
        if (notice != null) {
            if (System.currentTimeMillis() < noticeUntilMillis) status += "   ·   " + notice;
            else notice = null;
        }
        statusLabel.setText("   " + status);
        undoButton.setEnabled(undoPlies() > 0);
        int left = takebacksLeft();
        if (config.undoEnabled() && left != shownTakebacksLeft) {   // the label carries the remaining allowance
            shownTakebacksLeft = left;
            undoButton.setText("Undo (" + left + " left)");
            undoButton.setToolTipText(left == 0 ? "No takebacks left in this game"
                    : "Take back your last move (Ctrl+Z) — " + left + " of " + config.undoLimit() + " left");
        }
        boolean over = session.result().isOver();
        boolean humanTurn = !over && config.isHuman(session.sideToMove()) && activeWorker == null;
        resignButton.setEnabled(!over && !onlineGone);
        drawButton.setEnabled(isOnline() ? !over && !onlineGone : humanTurn);
        syncMoveList();
        boardPanel.repaint();
    }

    private void syncMoveList() {
        if (moveModel.plies() == session.plyCount()) return;
        moveModel.setMoves(session.sanHistory());
        int last = moveModel.getRowCount() - 1;
        if (last >= 0) moveTable.scrollRectToVisible(moveTable.getCellRect(last, 0, true));
    }

    // ---- move list ----

    /** Rows of "n. white black"; the last ply is rendered in bold. */
    private static final class MoveTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"#", "White", "Black"};
        private List<String> sans = new ArrayList<>();

        int plies() { return sans.size(); }

        void setMoves(List<String> moves) {
            sans = new ArrayList<>(moves);
            fireTableDataChanged();
        }

        boolean isLastPly(int row, int col) {
            return col > 0 && (row * 2 + col - 1) == sans.size() - 1;
        }

        @Override public int getRowCount() { return (sans.size() + 1) / 2; }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int c) { return COLUMNS[c]; }
        @Override public Object getValueAt(int row, int col) {
            if (col == 0) return (row + 1) + ".";
            int ply = row * 2 + col - 1;
            return ply < sans.size() ? sans.get(ply) : "";
        }
    }

    private final class MoveCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, v, false, false, row, col);
            boolean last = moveModel.isLastPly(row, col);
            c.setFont(c.getFont().deriveFont(last ? Font.BOLD : Font.PLAIN));
            setHorizontalAlignment(col == 0 ? RIGHT : LEFT);
            setForeground(col == 0 ? java.awt.Color.GRAY : t.getForeground());
            return c;
        }
    }

    // ---- AI worker ----

    private final class AiWorker extends SwingWorker<Search.Result, Void> {
        final AtomicBoolean cancelFlag = new AtomicBoolean(false);
        private final Board snapshot;
        private final long[] priorKeys;
        private final int level;
        private final long budgetMillis;
        private final long minMillis;

        AiWorker(Board snapshot, long[] priorKeys, int level, long budgetMillis, long minMillis) {
            this.snapshot = snapshot;
            this.priorKeys = priorKeys;
            this.level = level;
            this.budgetMillis = budgetMillis;
            this.minMillis = minMillis;
        }

        @Override
        protected Search.Result doInBackground() {
            long t0 = System.currentTimeMillis();
            // Opening book first (strong levels only): a book move is reported as depth 0.
            Move book = Skill.level(level).book() ? OpeningBook.probe(snapshot) : null;
            Search.Result r = book != null
                    ? new Search.Result(book, 0, 0, 0, List.of(book), 0)
                    : Skill.choose(engine, level, snapshot, budgetMillis, priorKeys, cancelFlag);
            if (r == null) return null;   // cancelled
            // Pacing (AI-vs-AI): instant replies make games unwatchable.
            long elapsed = System.currentTimeMillis() - t0;
            if (elapsed < minMillis) {
                try {
                    Thread.sleep(minMillis - elapsed);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return r;
        }

        @Override
        protected void done() {
            // Stale-worker guard: only the current worker of a live game may act.
            if (activeWorker != this) return;
            activeWorker = null;
            if (cancelFlag.get() || session.result().isOver()) { refresh(); return; }
            try {
                Search.Result r = get();
                if (r != null && r.bestMove() != null) {
                    lastAiInfo = describe(r, snapshot.sideToMove());
                    lastAiScore = r.depth() > 0 ? r.score() : null;
                    session.applyMove(r.bestMove());   // validates legality as a backstop
                    boardPanel.animate(r.bestMove());
                    afterMoveApplied();
                }
            } catch (Exception ex) {
                // A search failure must not silently hang the game.
                statusLabel.setText("   AI error: " + ex.getMessage());
                ex.printStackTrace();
            }
            refresh();
        }
    }
}

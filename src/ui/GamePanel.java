package ui;

import engine.Board;
import engine.Move;
import engine.OpeningBook;
import engine.Pieces;
import engine.Search;
import game.ChessClock;
import game.GameConfig;
import game.GameSession;
import game.Notation;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
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
 * and the AI worker lifecycle. All game-state mutation happens on the EDT;
 * the worker thread only ever reads a private {@link Board#copy()}.
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
 */
public final class GamePanel extends JPanel {

    private static final int TIMER_PERIOD_MS = 100;
    /** Minimum wall time per AI move in AI-vs-AI, so games are watchable. */
    private static final long AI_VS_AI_MIN_MOVE_MS = 500;
    /** Untimed games: the fixed depth decides, this only stops a runaway think. */
    private static final long UNTIMED_BUDGET_MS = 15_000;
    /** Clocked games: spend about 1/30 of the remaining time, within bounds. */
    private static final long CLOCK_FRACTION = 30, MIN_BUDGET_MS = 100, MAX_BUDGET_MS = 8_000;

    private final GameConfig config;
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
    private final MoveTableModel moveModel = new MoveTableModel();
    private final JTable moveTable = new JTable(moveModel);
    private final Timer uiTimer;

    private int topColor;            // color shown at the top of the window
    private AiWorker activeWorker;
    private boolean paused = false;

    public GamePanel(GameConfig config, Runnable onNewGame) {
        super(new BorderLayout(8, 8));
        this.config = config;
        this.session = new GameSession();
        // Untimed games keep a clock object so the tick/pause/label flow is
        // unchanged; it simply never expires and displays elapsed time.
        this.clock = config.hasClock() ? new ChessClock(config.millisPerSide()) : ChessClock.unlimited();

        boolean flipped = config.mode() == GameConfig.Mode.HUMAN_VS_AI
                && config.humanColor() == Pieces.BLACK;
        this.topColor = flipped ? Pieces.WHITE : Pieces.BLACK;
        // Premoves are only meaningful when a human is waiting on the AI.
        int humanColor = config.mode() == GameConfig.Mode.HUMAN_VS_AI ? config.humanColor() : -1;
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
        add(buildSidePanel(onNewGame), BorderLayout.EAST);

        uiTimer = new Timer(TIMER_PERIOD_MS, e -> onTick());
    }

    /** Move list plus the game controls, to the right of the board. */
    private JComponent buildSidePanel(Runnable onNewGame) {
        JPanel side = new JPanel(new BorderLayout(0, 8));
        side.setPreferredSize(new Dimension(250, 0));

        JLabel title = new JLabel("Moves");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        title.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 0));

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
        pauseButton.setVisible(aiVsAi);
        pauseButton.addActionListener(e -> togglePause());
        undoButton.setVisible(!aiVsAi);
        undoButton.setToolTipText("Take back your last move (Ctrl+Z)");
        undoButton.addActionListener(e -> undo());
        JButton flipBoard = new JButton("Flip Board");
        flipBoard.addActionListener(e -> flipBoard());
        JButton exportPgn = new JButton("Export PGN…");
        exportPgn.addActionListener(e -> exportPgn());
        JButton newGame = new JButton("New Game");
        newGame.addActionListener(e -> onNewGame.run());

        JPanel buttons = new JPanel(new GridLayout(0, 2, 6, 6));
        buttons.add(aiVsAi ? pauseButton : undoButton);
        buttons.add(flipBoard);
        buttons.add(exportPgn);
        buttons.add(newGame);

        // Ctrl+Z anywhere in the window takes back a move.
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "undo");
        getActionMap().put("undo", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { if (undoButton.isVisible()) undo(); }
        });

        side.add(title, BorderLayout.NORTH);
        side.add(scroll, BorderLayout.CENTER);
        side.add(buttons, BorderLayout.SOUTH);
        return side;
    }

    private String playerName(int color) {
        String base = GameConfig.colorName(color);
        return config.isAi(color) ? base + " (AI, depth " + config.aiDepth() + ")" : base;
    }

    /** Name for the PGN tags: who actually played the side. */
    private String pgnName(int color) {
        return config.isAi(color) ? "AI (depth " + config.aiDepth() + ")" : "Human";
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
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export PGN");
        chooser.setSelectedFile(new java.io.File("game.pgn"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path target = chooser.getSelectedFile().toPath();
        try {
            Files.writeString(target, Notation.pgn(session, pgnName(Pieces.WHITE), pgnName(Pieces.BLACK)),
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not write " + target + ":\n" + ex.getMessage(),
                    "Export PGN", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Called once by MainFrame after the panel is shown. */
    public void startGame() {
        clock.startTurn(session.sideToMove());
        refresh();
        uiTimer.start();
        maybeStartAi();
    }

    /** Stops timers and abandons any in-flight search. Idempotent. */
    public void dispose() {
        uiTimer.stop();
        if (activeWorker != null) {
            activeWorker.cancelFlag.set(true);
            activeWorker = null;
        }
    }

    // ---- move flow ----

    private void onHumanMove(Move m) {
        if (session.result().isOver() || !config.isHuman(session.sideToMove())) return;
        session.applyMove(m);
        afterMoveApplied();
    }

    private void afterMoveApplied() {
        if (session.result().isOver()) {
            endGame();
        } else {
            clock.startTurn(session.sideToMove());
            maybeStartAi();
        }
        refresh();
    }

    private void endGame() {
        clock.stop();
        boardPanel.setInteractionEnabled(false);
        // The timer keeps running only to no purpose; stop it.
        uiTimer.stop();
    }

    /** Number of plies a takeback removes right now, 0 when nothing can be taken back. */
    private int undoPlies() {
        if (config.mode() != GameConfig.Mode.HUMAN_VS_AI) return 0;
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
     * that had already ended.
     */
    private void undo() {
        int plies = undoPlies();
        if (plies == 0) return;
        if (activeWorker != null) {
            activeWorker.cancelFlag.set(true);
            activeWorker = null;
        }
        boolean wasOver = session.result().isOver();
        for (int i = 0; i < plies; i++) session.undoLastMove();
        boardPanel.cancelPremove();
        boardPanel.clearSelection();
        lastAiInfo = null;
        if (wasOver && !uiTimer.isRunning()) uiTimer.start();
        clock.startTurn(session.sideToMove());
        maybeStartAi();
        refresh();
    }

    private void maybeStartAi() {
        if (session.result().isOver() || paused) return;
        int stm = session.sideToMove();
        if (!config.isAi(stm)) {
            boardPanel.setInteractionEnabled(true);
            // A premove entered while the AI was thinking is played at once
            // if it is legal here; consumePremove() drops it otherwise. It
            // goes through the same path as a clicked move, so the AI reply
            // is kicked off by the nested afterMoveApplied().
            Move premove = boardPanel.consumePremove();
            if (premove != null) {
                session.applyMove(premove);
                afterMoveApplied();
            }
            return;
        }
        boardPanel.setInteractionEnabled(false);
        if (activeWorker != null) return;   // one at a time; defensive
        long minMillis = config.mode() == GameConfig.Mode.AI_VS_AI ? AI_VS_AI_MIN_MOVE_MS : 0;
        activeWorker = new AiWorker(session.board().copy(), session.priorPositionKeys(),
                config.aiDepth(), timeBudgetMillis(stm), minMillis);
        activeWorker.execute();
        refresh();
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
            if (activeWorker != null) {
                activeWorker.cancelFlag.set(true);
                activeWorker = null;
            }
            clock.stop();
        } else {
            clock.startTurn(session.sideToMove());
            maybeStartAi();
        }
        refresh();
    }

    // ---- clock tick ----

    private void onTick() {
        if (!session.result().isOver() && !paused) {
            int running = clock.runningSide();
            if (running != -1 && clock.isExpired(running)) {
                if (activeWorker != null) {
                    activeWorker.cancelFlag.set(true);
                    activeWorker = null;
                }
                session.timeout(running);
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
        } else {
            status = session.statusText();
            if (lastAiInfo != null) status += "   ·   AI " + lastAiInfo;
        }
        statusLabel.setText("   " + status);
        undoButton.setEnabled(undoPlies() > 0);
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
        private final int depth;
        private final long budgetMillis;
        private final long minMillis;

        AiWorker(Board snapshot, long[] priorKeys, int depth, long budgetMillis, long minMillis) {
            this.snapshot = snapshot;
            this.priorKeys = priorKeys;
            this.depth = depth;
            this.budgetMillis = budgetMillis;
            this.minMillis = minMillis;
        }

        @Override
        protected Search.Result doInBackground() {
            long t0 = System.currentTimeMillis();
            // Opening book first: a book move is reported as depth 0.
            Move book = OpeningBook.probe(snapshot);
            Search.Result r = book != null
                    ? new Search.Result(book, 0, 0, 0, List.of(book), 0)
                    : engine.findBest(snapshot, depth, budgetMillis, priorKeys, cancelFlag);
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
                    session.applyMove(r.bestMove());   // validates legality as a backstop
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

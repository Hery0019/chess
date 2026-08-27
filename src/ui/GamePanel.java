package ui;

import engine.Board;
import engine.Move;
import engine.Pieces;
import engine.Search;
import game.ChessClock;
import game.GameConfig;
import game.GameSession;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One live game: board view, two clocks, status line, controls, and the AI
 * worker lifecycle. All game-state mutation happens on the EDT; the worker
 * thread only ever reads a private {@link Board#copy()}.
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
 *    and its result is discarded.
 */
public final class GamePanel extends JPanel {

    private static final int TIMER_PERIOD_MS = 100;
    /** Minimum wall time per AI move in AI-vs-AI, so games are watchable. */
    private static final long AI_VS_AI_MIN_MOVE_MS = 500;

    private final GameConfig config;
    private final GameSession session;
    private final ChessClock clock;
    private final BoardPanel boardPanel;
    private final JLabel topClockLabel = new JLabel();
    private final JLabel bottomClockLabel = new JLabel();
    private final JLabel topNameLabel = new JLabel();
    private final JLabel bottomNameLabel = new JLabel();
    private final JLabel statusLabel = new JLabel();
    private final JButton pauseButton = new JButton("Pause");
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

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        pauseButton.setVisible(config.mode() == GameConfig.Mode.AI_VS_AI);
        pauseButton.addActionListener(e -> togglePause());
        JButton flipBoard = new JButton("Flip Board");
        flipBoard.addActionListener(e -> flipBoard());
        JButton newGame = new JButton("New Game");
        newGame.addActionListener(e -> onNewGame.run());
        controls.add(flipBoard);
        controls.add(pauseButton);
        controls.add(newGame);

        JPanel bottom = new JPanel(new BorderLayout());
        JPanel bottomLeft = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottomLeft.add(bottomNameLabel);
        bottomLeft.add(bottomClockLabel);
        bottomLeft.add(statusLabel);
        bottom.add(bottomLeft, BorderLayout.CENTER);
        bottom.add(controls, BorderLayout.EAST);

        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(top, BorderLayout.NORTH);
        add(boardPanel, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        uiTimer = new Timer(TIMER_PERIOD_MS, e -> onTick());
    }

    private String playerName(int color) {
        String base = GameConfig.colorName(color);
        return config.isAi(color) ? base + " (AI, depth " + config.aiDepth() + ")" : base;
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
        activeWorker = new AiWorker(session.board().copy(), config.aiDepth(), minMillis);
        activeWorker.execute();
        refresh();
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
            status = "AI is thinking\u2026";
            if (boardPanel.hasPremove()) status += "   (premove: " + boardPanel.premoveText() + ")";
        } else {
            status = session.statusText();
        }
        statusLabel.setText("   " + status);
        boardPanel.repaint();
    }

    // ---- AI worker ----

    private final class AiWorker extends SwingWorker<Move, Void> {
        final AtomicBoolean cancelFlag = new AtomicBoolean(false);
        private final Board snapshot;
        private final int depth;
        private final long minMillis;

        AiWorker(Board snapshot, int depth, long minMillis) {
            this.snapshot = snapshot;
            this.depth = depth;
            this.minMillis = minMillis;
        }

        @Override
        protected Move doInBackground() {
            long t0 = System.currentTimeMillis();
            Search.Result r = new Search().findBest(snapshot, depth, cancelFlag);
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
            return r.bestMove();
        }

        @Override
        protected void done() {
            // Stale-worker guard: only the current worker of a live game may act.
            if (activeWorker != this) return;
            activeWorker = null;
            if (cancelFlag.get() || session.result().isOver()) { refresh(); return; }
            try {
                Move m = get();
                if (m != null) {
                    session.applyMove(m);   // validates legality as a backstop
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

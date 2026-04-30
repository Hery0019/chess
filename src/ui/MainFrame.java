package ui;

import game.GameConfig;
import game.SavedGame;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import java.awt.CardLayout;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * Application shell: start screen <-> game panel via CardLayout. Owns the
 * lifecycle handoff — the outgoing GamePanel is always disposed (timers
 * stopped, worker cancelled) before being replaced, so no orphaned timers or
 * workers can outlive their game. Remembers window placement and the last
 * start-screen settings between runs.
 */
public final class MainFrame extends JFrame implements GamePanel.Host {

    private final CardLayout cards = new CardLayout();
    private GamePanel currentGame;

    public MainFrame() {
        super("Chess");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(cards);
        add(new StartScreen(Prefs.lastConfig(), this::startGame, this::resumeGame), "start");

        Rectangle remembered = Prefs.windowBounds();
        if (remembered != null && fitsOnAScreen(remembered)) {
            setBounds(remembered);
        } else {
            setSize(900, 760);
            setLocationRelativeTo(null);
        }
        addComponentListener(new ComponentAdapter() {
            @Override public void componentMoved(ComponentEvent e) { remember(); }
            @Override public void componentResized(ComponentEvent e) { remember(); }
            private void remember() {
                if (isShowing() && (getExtendedState() & MAXIMIZED_BOTH) == 0) Prefs.saveWindowBounds(getBounds());
            }
        });
        cards.show(getContentPane(), "start");
    }

    /** A remembered position is only reused if it is (partly) visible on some current screen. */
    private static boolean fitsOnAScreen(Rectangle r) {
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle screen = gd.getDefaultConfiguration().getBounds();
            Rectangle visible = screen.intersection(r);
            if (visible.width >= 200 && visible.height >= 100) return true;
        }
        return false;
    }

    @Override
    public void startGame(GameConfig config) {
        Prefs.saveLastConfig(config);
        launch(config, null);
    }

    private void resumeGame(SavedGame saved) { launch(saved.config(), saved); }

    private void launch(GameConfig config, SavedGame saved) {
        disposeCurrentGame();
        GamePanel panel;
        try {
            panel = new GamePanel(config, saved, this);
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, "Cannot resume this game:\n" + ex.getMessage(),
                    "Resume game", JOptionPane.ERROR_MESSAGE);
            cards.show(getContentPane(), "start");
            return;
        }
        currentGame = panel;
        add(currentGame, "game");
        cards.show(getContentPane(), "game");
        currentGame.startGame();
    }

    @Override
    public void newGame() {
        disposeCurrentGame();
        cards.show(getContentPane(), "start");
    }

    private void disposeCurrentGame() {
        if (currentGame != null) {
            currentGame.dispose();
            remove(currentGame);
            currentGame = null;
        }
    }
}

package ui;

import game.GameConfig;
import game.SavedGame;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import java.awt.CardLayout;

/**
 * Application shell: start screen <-> game panel via CardLayout. Owns the
 * lifecycle handoff — the outgoing GamePanel is always disposed (timers
 * stopped, worker cancelled) before being replaced, so no orphaned timers or
 * workers can outlive their game.
 */
public final class MainFrame extends JFrame implements GamePanel.Host {

    private final CardLayout cards = new CardLayout();
    private GamePanel currentGame;

    public MainFrame() {
        super("Chess");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(cards);
        add(new StartScreen(this::startGame, this::resumeGame), "start");
        setSize(900, 760);
        setLocationRelativeTo(null);
        cards.show(getContentPane(), "start");
    }

    @Override
    public void startGame(GameConfig config) { launch(config, null); }

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

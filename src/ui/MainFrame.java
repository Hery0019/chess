package ui;

import game.GameConfig;

import javax.swing.JFrame;
import java.awt.CardLayout;

/**
 * Application shell: start screen <-> game panel via CardLayout. Owns the
 * lifecycle handoff — the outgoing GamePanel is always disposed (timers
 * stopped, worker cancelled) before being replaced, so no orphaned timers or
 * workers can outlive their game.
 */
public final class MainFrame extends JFrame {

    private final CardLayout cards = new CardLayout();
    private GamePanel currentGame;

    public MainFrame() {
        super("Chess");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(cards);
        add(new StartScreen(this::startGame), "start");
        setSize(900, 760);
        setLocationRelativeTo(null);
        cards.show(getContentPane(), "start");
    }

    private void startGame(GameConfig config) {
        disposeCurrentGame();
        currentGame = new GamePanel(config, this::backToStart);
        add(currentGame, "game");
        cards.show(getContentPane(), "game");
        currentGame.startGame();
    }

    private void backToStart() {
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

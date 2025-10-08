package app;

import ui.MainFrame;

import javax.swing.SwingUtilities;

/** Entry point. All Swing construction happens on the EDT, per contract. */
public final class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MainFrame().setVisible(true));
    }
}

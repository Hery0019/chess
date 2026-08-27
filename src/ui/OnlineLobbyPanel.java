package ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;

/**
 * Shown between "Host / Join" and the first START message: connection
 * status, the addresses to share when hosting, and a Cancel button.
 */
final class OnlineLobbyPanel extends JPanel {

    private static final Color BG_TOP = new Color(0x3B, 0x34, 0x2E);
    private static final Color BG_BOTTOM = new Color(0x1B, 0x18, 0x16);
    private static final Color TEXT = new Color(0xF8, 0xF3, 0xEA);
    private static final Color MUTED = new Color(0xC9, 0xBD, 0xAA);

    private final JLabel title = new JLabel("Online game");
    private final JLabel status = new JLabel(" ");
    private final JLabel shareLabel = new JLabel("<html><div style=text-align:center>Share one of these addresses with your opponent.<br>"
            + "In a browser it just works (nothing to install); in this app, Join game with the part after http://</div></html>");
    private final JTextArea addresses = new JTextArea();

    OnlineLobbyPanel(Runnable onCancel) {
        super(new GridBagLayout());
        title.setFont(new Font("Segoe UI", Font.BOLD, 26));
        title.setForeground(TEXT);
        status.setFont(new Font("Segoe UI", Font.PLAIN, 15));
        status.setForeground(TEXT);
        shareLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        shareLabel.setForeground(MUTED);
        addresses.setEditable(false);
        addresses.setFont(new Font(Font.MONOSPACED, Font.BOLD, 16));
        addresses.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        addresses.setBackground(new Color(0xF8, 0xF3, 0xEA));
        addresses.setForeground(new Color(0x2B, 0x25, 0x20));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> onCancel.run());

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.insets = new Insets(6, 0, 6, 0);
        add(title, c);
        add(status, c);
        c.insets = new Insets(18, 0, 4, 0);
        add(shareLabel, c);
        c.insets = new Insets(0, 0, 18, 0);
        add(addresses, c);
        c.insets = new Insets(6, 0, 6, 0);
        add(cancel, c);
        setShareInfo(List.of(), -1);
    }

    void setStatus(String text) { status.setText(text); }

    /** Lists {@code http://ip:port} lines (the browser client; the app joins the same ip:port); hidden when joining. */
    void setShareInfo(List<String> hosts, int port) {
        boolean show = port > 0 && !hosts.isEmpty();
        shareLabel.setVisible(show);
        addresses.setVisible(show);
        if (show) {
            StringBuilder sb = new StringBuilder();
            for (String h : hosts) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("http://").append(h).append(':').append(port);
            }
            addresses.setText(sb.toString());
        }
        revalidate();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setPaint(new GradientPaint(0, 0, BG_TOP, 0, getHeight(), BG_BOTTOM));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.dispose();
    }
}

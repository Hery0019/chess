package ui;

import engine.Pieces;
import game.GameConfig;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JToggleButton;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.HierarchyEvent;
import java.awt.font.TextAttribute;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Pre-game configuration screen. Emits an immutable {@link GameConfig} via
 * the callback; performs no game logic itself.
 *
 * Presentation: a warm dark background carrying a faint board texture and
 * ghosted piece silhouettes, with a single cream "card" holding the options
 * as segmented pill controls (mode, side, time, AI strength) and one large
 * primary Start button. Everything is custom-painted with plain Java2D so
 * the look does not depend on the platform look-and-feel; no assets.
 */
public final class StartScreen extends JPanel {

    // ---- palette (shares the board's warm wood tones) ----
    private static final Color BG_TOP       = new Color(0x3B, 0x34, 0x2E);
    private static final Color BG_BOTTOM    = new Color(0x1B, 0x18, 0x16);
    private static final Color BG_CHECK     = new Color(0xFF, 0xFF, 0xFF, 8);
    private static final Color CARD         = new Color(0xF8, 0xF3, 0xEA);
    private static final Color CARD_EDGE    = new Color(0xD6, 0xC8, 0xB2);
    private static final Color CARD_SHADOW  = new Color(0, 0, 0, 22);
    private static final Color TEXT         = new Color(0x2B, 0x25, 0x20);
    private static final Color MUTED        = new Color(0x84, 0x77, 0x69);
    private static final Color TRACK        = new Color(0xE9, 0xE0, 0xD0);
    private static final Color PILL_ON      = new Color(0x4A, 0x3A, 0x2E);
    private static final Color PILL_HOVER   = new Color(0xDB, 0xCE, 0xB9);
    private static final Color FOCUS_RING   = new Color(0x7E, 0x5A, 0x3C, 160);
    private static final Color START        = new Color(0x81, 0xB6, 0x4C);
    private static final Color START_HOVER  = new Color(0x6F, 0xA2, 0x3F);
    private static final Color START_DOWN   = new Color(0x5E, 0x8C, 0x33);

    private static final int CONTENT_WIDTH = 400;

    private final PieceRenderer renderer = UnicodePieceRenderer.createBest();

    /** Combo entry: minutes per side, {@link GameConfig#NO_CLOCK} for untimed. */
    private record TimeControl(int minutes) {
        String label() { return minutes == GameConfig.NO_CLOCK ? "None" : String.valueOf(minutes); }
    }

    private static final String[] STRENGTH_HINTS = {
            "",
            "Depth 1 — beginner, instant moves",
            "Depth 2 — casual, instant moves",
            "Depth 3 — club player, quick replies",
            "Depth 4 — strong, a few seconds per move",
            "Depth 5 — master, slow but deepest search",
    };

    public StartScreen(Consumer<GameConfig> onStart) {
        super(new GridBagLayout());
        setOpaque(true);

        // ---- controls ----
        Segmented<GameConfig.Mode> mode = new Segmented<>(
                List.of(GameConfig.Mode.HUMAN_VS_AI, GameConfig.Mode.AI_VS_AI),
                List.of("Human vs AI", "AI vs AI"), null, 0);

        Segmented<Integer> side = new Segmented<>(
                List.of(Pieces.WHITE, Pieces.BLACK),
                List.of("White", "Black"),
                List.of(pieceIcon(Pieces.make(Pieces.KING, Pieces.WHITE), 26),
                        pieceIcon(Pieces.make(Pieces.KING, Pieces.BLACK), 26)), 0);

        List<TimeControl> times = List.of(new TimeControl(GameConfig.NO_CLOCK), new TimeControl(3),
                new TimeControl(5), new TimeControl(10), new TimeControl(15), new TimeControl(30));
        Segmented<TimeControl> time = new Segmented<>(times,
                times.stream().map(TimeControl::label).toList(), null, 0);   // untimed by default

        Segmented<Integer> depth = new Segmented<>(
                List.of(1, 2, 3, 4, 5), List.of("1", "2", "3", "4", "5"), null, 3);
        JLabel strengthHint = label(STRENGTH_HINTS[depth.value()], font(Font.PLAIN, 12f), MUTED);
        depth.onChange(() -> strengthHint.setText(STRENGTH_HINTS[depth.value()]));

        // Side choice is meaningless in AI-vs-AI: disabled, not hidden, so the
        // layout stays stable and the causality is visible to the user.
        mode.onChange(() -> side.setEnabled(mode.value() == GameConfig.Mode.HUMAN_VS_AI));

        PrimaryButton start = new PrimaryButton("Start Game");
        start.addActionListener(e -> onStart.accept(new GameConfig(
                mode.value(), side.value(), time.value().minutes(), depth.value())));

        // ---- card layout ----
        Card card = new Card();
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 0);

        card.add(Box.createHorizontalStrut(CONTENT_WIDTH), c);
        card.add(header(), c);

        c.insets = new Insets(22, 0, 0, 0);
        card.add(caption("Mode"), c);
        c.insets = new Insets(6, 0, 0, 0);
        card.add(mode, c);

        c.insets = new Insets(18, 0, 0, 0);
        card.add(caption("Play as"), c);
        c.insets = new Insets(6, 0, 0, 0);
        card.add(side, c);

        c.insets = new Insets(18, 0, 0, 0);
        card.add(caption("Time per side (minutes)"), c);
        c.insets = new Insets(6, 0, 0, 0);
        card.add(time, c);

        c.insets = new Insets(18, 0, 0, 0);
        card.add(caption("AI strength"), c);
        c.insets = new Insets(6, 0, 0, 0);
        card.add(depth, c);
        c.insets = new Insets(6, 2, 0, 0);
        card.add(strengthHint, c);

        c.insets = new Insets(26, 0, 0, 0);
        card.add(start, c);

        c.insets = new Insets(14, 0, 0, 0);
        JLabel hint = label("Drag & drop or click-click  ·  premove while the AI thinks  ·  Enter starts",
                font(Font.PLAIN, 11f), MUTED);
        hint.setHorizontalAlignment(JLabel.CENTER);
        card.add(hint, c);

        add(card, new GridBagConstraints());

        // Enter starts the game — but only while this screen is the visible
        // card, otherwise Enter during a game would restart it.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
            JRootPane root = getRootPane();
            if (root != null) root.setDefaultButton(isShowing() ? start : null);
        });
    }

    // ---- pieces of the card ----

    private JComponent header() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        JLabel knight = new JLabel(pieceIcon(Pieces.make(Pieces.KNIGHT, Pieces.WHITE), 60));
        knight.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 14));
        row.add(knight);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel title = label("Chess", font(Font.BOLD, 34f), TEXT);
        JLabel subtitle = label("Minimax · alpha-beta · quiescence search", font(Font.PLAIN, 13f), MUTED);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(title);
        text.add(Box.createVerticalStrut(2));
        text.add(subtitle);
        row.add(text);
        return row;
    }

    private static JLabel caption(String text) {
        Font f = font(Font.BOLD, 11f).deriveFont(Map.of(TextAttribute.TRACKING, 0.10f));
        return label(text.toUpperCase(), f, MUTED);
    }

    private static JLabel label(String text, Font font, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(font);
        l.setForeground(color);
        return l;
    }

    /** "Segoe UI" on Windows; the JVM silently maps an unknown family to Dialog. */
    private static Font font(int style, float size) {
        return new Font("Segoe UI", style, Math.round(size));
    }

    /**
     * A piece as an icon. Small sizes are supersampled: the renderer's
     * minimum stroke width turns a 26 px glyph into a dark blob, so the piece
     * is drawn at 4x and reduced with area averaging, which keeps the look of
     * the large glyph in miniature.
     */
    private Icon pieceIcon(int piece, int size) {
        int scale = size < 48 ? 4 : 1;
        BufferedImage big = new BufferedImage(size * scale, size * scale, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = big.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        renderer.draw(g2, piece, 0, 0, size * scale);
        g2.dispose();
        Image image = scale == 1 ? big : big.getScaledInstance(size, size, Image.SCALE_SMOOTH);
        return new ImageIcon(image);
    }

    // ---- background ----

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        int w = getWidth(), h = getHeight();
        g.setPaint(new GradientPaint(0, 0, BG_TOP, 0, h, BG_BOTTOM));
        g.fillRect(0, 0, w, h);

        // Faint oversized chessboard texture.
        int s = 72;
        g.setColor(BG_CHECK);
        for (int r = 0; r * s < h; r++) {
            for (int c = 0; c * s < w; c++) {
                if (((r + c) & 1) == 0) g.fillRect(c * s, r * s, s, s);
            }
        }

        // Ghosted piece silhouettes in two corners.
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.08f));
        renderer.draw(g, Pieces.make(Pieces.KNIGHT, Pieces.WHITE), -60, h - 330, 380);
        renderer.draw(g, Pieces.make(Pieces.QUEEN, Pieces.WHITE), w - 250, -70, 340);
        g.dispose();
    }

    // ---- custom components ----

    /** Rounded cream panel with a soft drop shadow. */
    private static final class Card extends JPanel {
        Card() {
            super(new GridBagLayout());
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(30, 36, 28, 36));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight(), pad = 12, arc = 26;
            g.setColor(CARD_SHADOW);
            for (int i = pad; i > 0; i -= 2) {
                g.fill(new RoundRectangle2D.Float(pad - i, pad - i + 4, w - 2 * (pad - i), h - 2 * (pad - i), arc + i, arc + i));
            }
            g.setColor(CARD);
            g.fill(new RoundRectangle2D.Float(pad, pad, w - 2 * pad, h - 2 * pad, arc, arc));
            g.setColor(CARD_EDGE);
            g.draw(new RoundRectangle2D.Float(pad + 0.5f, pad + 0.5f, w - 2 * pad - 1, h - 2 * pad - 1, arc, arc));
            g.dispose();
        }

        @Override
        public Insets getInsets() {
            Insets i = super.getInsets();
            return new Insets(i.top + 12, i.left + 12, i.bottom + 12, i.right + 12);
        }
    }

    /**
     * A row of mutually exclusive pills on a rounded track. Values are
     * parallel to labels; {@link #value()} returns the selected one.
     */
    private static final class Segmented<T> extends JPanel {
        private final List<T> values;
        private final List<Pill> pills = new ArrayList<>();
        private final List<Runnable> listeners = new ArrayList<>();

        Segmented(List<T> values, List<String> labels, List<Icon> icons, int selected) {
            super(new GridLayout(1, values.size(), 0, 0));
            this.values = values;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
            ButtonGroup group = new ButtonGroup();
            for (int i = 0; i < values.size(); i++) {
                Pill p = new Pill(labels.get(i), icons == null ? null : icons.get(i));
                p.addItemListener(e -> { if (p.isSelected()) listeners.forEach(Runnable::run); });
                group.add(p);
                pills.add(p);
                add(p);
            }
            pills.get(selected).setSelected(true);
        }

        T value() {
            for (int i = 0; i < pills.size(); i++) if (pills.get(i).isSelected()) return values.get(i);
            return values.get(0);
        }

        void onChange(Runnable r) { listeners.add(r); }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            for (Pill p : pills) p.setEnabled(enabled);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (!isEnabled()) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
            g.setColor(TRACK);
            g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 16, 16));
            g.dispose();
        }
    }

    /** One segment: a flat toggle painted as a pill (dark when selected). */
    private static final class Pill extends JToggleButton {
        private static final int H_PAD = 12, V_PAD = 8, ICON_GAP = 6;

        Pill(String text, Icon icon) {
            super(text, icon);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            setFont(font(Font.BOLD, 13f));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            Icon icon = getIcon();
            int w = fm.stringWidth(getText()) + 2 * H_PAD + (icon == null ? 0 : icon.getIconWidth() + ICON_GAP);
            int h = Math.max(fm.getHeight(), icon == null ? 0 : icon.getIconHeight()) + 2 * V_PAD;
            return new Dimension(w, h);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            boolean on = isSelected(), enabled = isEnabled();
            if (!enabled) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));

            if (on) {
                g.setColor(PILL_ON);
                g.fill(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, 13, 13));
            } else if (enabled && getModel().isRollover()) {
                g.setColor(PILL_HOVER);
                g.fill(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, 13, 13));
            }
            if (enabled && hasFocus()) {
                g.setColor(FOCUS_RING);
                g.draw(new RoundRectangle2D.Float(1.5f, 1.5f, w - 3, h - 3, 13, 13));
            }

            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            Icon icon = getIcon();
            String text = getText();
            int iconW = icon == null ? 0 : icon.getIconWidth() + ICON_GAP;
            int x = (w - fm.stringWidth(text) - iconW) / 2;
            if (icon != null) {
                icon.paintIcon(this, g, x, (h - icon.getIconHeight()) / 2);
                x += iconW;
            }
            g.setColor(on ? Color.WHITE : TEXT);
            g.drawString(text, x, (h - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }
    }

    /** Large rounded call-to-action button. */
    private static final class PrimaryButton extends JButton {
        PrimaryButton(String text) {
            super(text);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            setFont(font(Font.BOLD, 17f));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(super.getPreferredSize().width, 48);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            var m = getModel();
            Color fill = m.isArmed() && m.isPressed() ? START_DOWN : m.isRollover() ? START_HOVER : START;
            // Bottom shadow line gives the button a slight 3D lift (chess.com style).
            g.setColor(START_DOWN);
            g.fill(new RoundRectangle2D.Float(0, 3, w, h - 3, 14, 14));
            g.setColor(fill);
            g.fill(new RoundRectangle2D.Float(0, 0, w, h - 4, 14, 14));
            if (hasFocus()) {
                g.setColor(new Color(255, 255, 255, 120));
                g.draw(new RoundRectangle2D.Float(1.5f, 1.5f, w - 3, h - 7, 13, 13));
            }
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            g.setColor(Color.WHITE);
            g.drawString(getText(), (w - fm.stringWidth(getText())) / 2,
                    (h - 4 - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }
    }
}

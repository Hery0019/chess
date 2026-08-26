package ui;

import engine.Pieces;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Draws pieces as Unicode chess glyphs.
 *
 * Two known Unicode-rendering hazards, both handled (per the rendering
 * decision):
 *  1) Glyph availability varies by platform/JVM font setup. The factory scans
 *     a preference list of fonts that are actually installed (a logical
 *     fallback such as "Dialog" is only tried last); if no candidate can
 *     display the glyphs, {@link #createBest} returns a plain letter-based
 *     fallback renderer and logs a warning instead of producing tofu boxes.
 *  2) The "white" codepoints (U+2654..) render hollow and nearly invisible on
 *     light squares with some fonts. Both colors therefore use the FILLED
 *     glyph set (U+265A..265F) and are drawn as vector outlines via
 *     {@link TextLayout#getOutline}.
 *
 * Colour separation. Simply filling the glyph outline and stroking every
 * contour makes the two sides look alike: the filled glyphs carry their
 * interior detail as thin double contours, so a light stroke on a dark piece
 * covers it in light lines and it reads as "white with a border". Instead
 * each piece is built from its solid <em>silhouette</em> (union of all glyph
 * contours, holes closed):
 * <ul>
 *   <li>White: ivory silhouette, thick charcoal edge, thin charcoal detail.</li>
 *   <li>Black: solid near-black silhouette with no light border at all —
 *       only faint dark-grey detail lines so the shape stays readable.</li>
 * </ul>
 * Both get a soft drop shadow so they lift off the square. Geometry is
 * cached per (type, size) because the board repaints on a 100 ms timer.
 */
public final class UnicodePieceRenderer implements PieceRenderer {

    /** Filled glyphs indexed by piece TYPE (index 0 unused). */
    private static final String[] GLYPHS = {"", "\u265F", "\u265E", "\u265D", "\u265C", "\u265B", "\u265A"};
    private static final String[] FONT_CANDIDATES = {
            "Segoe UI Symbol", "DejaVu Sans", "Noto Sans Symbols 2", "FreeSerif", "Symbola"
    };
    private static final String[] LOGICAL_FALLBACKS = {Font.DIALOG, Font.SERIF, Font.SANS_SERIF};

    // ---- palette ----
    static final Color WHITE_FILL   = new Color(0xFB, 0xF6, 0xEA);
    static final Color WHITE_EDGE   = new Color(0x1C, 0x1C, 0x1C);
    static final Color BLACK_FILL   = new Color(0x0E, 0x0E, 0x0E);
    static final Color BLACK_EDGE   = new Color(0x2A, 0x2A, 0x2A);   // fallback disc edge only
    static final Color BLACK_DETAIL = new Color(0x3C, 0x3C, 0x3C);
    static final Color BLACK_LETTER = new Color(0xC8, 0xC8, 0xC8);   // fallback letter only
    static final Color SHADOW       = new Color(0, 0, 0, 70);

    private final Font baseFont;
    /** key = type * 4096 + size. */
    private final Map<Integer, Glyph> cache = new HashMap<>();

    /** Glyph geometry pre-centred in a size x size box whose origin is (0,0). */
    private record Glyph(Shape outline, Area silhouette) {}

    private UnicodePieceRenderer(Font baseFont) { this.baseFont = baseFont; }

    /** Returns a glyph renderer if a capable font exists, else a letter fallback. */
    public static PieceRenderer createBest() {
        Set<String> installed = Set.of(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String name : FONT_CANDIDATES) {
            if (!installed.contains(name)) continue;
            Font f = new Font(name, Font.PLAIN, 64);
            if (canDisplayAll(f)) return new UnicodePieceRenderer(f);
        }
        for (String name : LOGICAL_FALLBACKS) {
            Font f = new Font(name, Font.PLAIN, 64);
            if (canDisplayAll(f)) return new UnicodePieceRenderer(f);
        }
        System.err.println("WARNING: no installed font renders chess glyphs; using letter fallback.");
        return new LetterPieceRenderer();
    }

    private static boolean canDisplayAll(Font f) {
        for (int t = Pieces.PAWN; t <= Pieces.KING; t++) {
            if (!f.canDisplay(GLYPHS[t].codePointAt(0))) return false;
        }
        return true;
    }

    @Override
    public void draw(Graphics2D g, int piece, int x, int y, int size) {
        Glyph glyph = glyph(g, Pieces.typeOf(piece), size);
        boolean white = Pieces.colorOf(piece) == Pieces.WHITE;

        AffineTransform saved = g.getTransform();
        g.translate(x, y);
        try {
            // Soft shadow, offset down-right, lifts the piece off the square.
            double off = size / 32.0;
            g.translate(off, off);
            g.setColor(SHADOW);
            g.fill(glyph.silhouette());
            g.translate(-off, -off);

            if (white) {
                g.setColor(WHITE_EDGE);
                g.setStroke(stroke(size / 19f));
                g.draw(glyph.silhouette());           // bold outer edge
                g.setColor(WHITE_FILL);
                g.fill(glyph.silhouette());            // solid ivory body
                g.setColor(WHITE_EDGE);
                g.setStroke(stroke(size / 48f));
                g.draw(glyph.outline());               // interior detail lines
            } else {
                g.setColor(BLACK_FILL);
                g.setStroke(stroke(size / 24f));
                g.draw(glyph.silhouette());           // same-colour edge: matches white's bulk
                g.fill(glyph.silhouette());            // solid black body
                g.setColor(BLACK_DETAIL);
                g.setStroke(stroke(size / 90f));
                g.draw(glyph.outline());               // faint dark-grey detail only
            }
        } finally {
            g.setTransform(saved);
        }
    }

    private static BasicStroke stroke(float width) {
        return new BasicStroke(Math.max(0.75f, width), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    private Glyph glyph(Graphics2D g, int type, int size) {
        int key = type * 4096 + size;
        Glyph cached = cache.get(key);
        if (cached != null) return cached;

        Font font = baseFont.deriveFont(size * 0.78f);
        FontRenderContext frc = g.getFontRenderContext();
        TextLayout layout = new TextLayout(GLYPHS[type], font, frc);
        Shape raw = layout.getOutline(null);
        Rectangle2D b = raw.getBounds2D();
        // Centre the ink box in the square, nudged 3% upward: the shadow and
        // halo add visual weight below, so a true centre reads as sitting low.
        double tx = (size - b.getWidth()) / 2 - b.getX();
        double ty = (size - b.getHeight()) / 2 - b.getY() - size * 0.03;
        Shape outline = AffineTransform.getTranslateInstance(tx, ty).createTransformedShape(raw);
        Glyph glyph = new Glyph(outline, silhouette(outline));
        cache.put(key, glyph);
        return glyph;
    }

    /**
     * Union of every closed sub-path of the glyph, each filled independently.
     * Interior contours (holes, detail lines) lie inside the outer contour, so
     * the union is the solid piece silhouette with all detail closed.
     */
    private static Area silhouette(Shape outline) {
        Area result = new Area();
        Path2D.Double current = null;
        double[] c = new double[6];
        for (PathIterator it = outline.getPathIterator(null); !it.isDone(); it.next()) {
            int seg = it.currentSegment(c);
            if (seg == PathIterator.SEG_MOVETO) {
                if (current != null) result.add(new Area(current));
                current = new Path2D.Double(Path2D.WIND_NON_ZERO);
                current.moveTo(c[0], c[1]);
            } else if (current == null) {
                continue;
            } else if (seg == PathIterator.SEG_LINETO) {
                current.lineTo(c[0], c[1]);
            } else if (seg == PathIterator.SEG_QUADTO) {
                current.quadTo(c[0], c[1], c[2], c[3]);
            } else if (seg == PathIterator.SEG_CUBICTO) {
                current.curveTo(c[0], c[1], c[2], c[3], c[4], c[5]);
            } else if (seg == PathIterator.SEG_CLOSE) {
                current.closePath();
            }
        }
        if (current != null) result.add(new Area(current));
        return result;
    }

    /** Last-resort fallback: bold letters on a disc. Ugly but unambiguous. */
    private static final class LetterPieceRenderer implements PieceRenderer {
        private static final String LETTERS = ".PNBRQK";

        @Override
        public void draw(Graphics2D g, int piece, int x, int y, int size) {
            boolean white = Pieces.colorOf(piece) == Pieces.WHITE;
            int pad = size / 8;
            int d = size - 2 * pad;
            g.setColor(SHADOW);
            g.fillOval(x + pad + size / 32, y + pad + size / 32, d, d);
            g.setColor(white ? WHITE_FILL : BLACK_FILL);
            g.fillOval(x + pad, y + pad, d, d);
            g.setColor(white ? WHITE_EDGE : BLACK_EDGE);
            g.setStroke(stroke(size / 20f));
            g.drawOval(x + pad, y + pad, d, d);
            g.setColor(white ? WHITE_EDGE : BLACK_LETTER);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size / 2));
            String s = String.valueOf(LETTERS.charAt(Pieces.typeOf(piece)));
            var fm = g.getFontMetrics();
            g.drawString(s,
                    x + (size - fm.stringWidth(s)) / 2,
                    y + (size + fm.getAscent() - fm.getDescent()) / 2);
        }
    }
}

package ui;

import engine.Pieces;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws pieces from the SVG piece set shipped with the browser client
 * ({@code /web/pieces/wK.svg} ... {@code bP.svg}), so the Swing app and the
 * web page show the very same pieces.
 *
 * Java2D has no SVG support, so this reads the small subset those files use:
 * {@code <path>} and {@code <rect>} elements with fill, stroke, stroke-width,
 * line join/cap, opacity and fill-rule, plus the full path syntax (arcs
 * included). Each file is parsed once into shapes in viewBox units and drawn
 * scaled to the square, so pieces stay crisp at any board size.
 *
 * If any file is missing or uses something outside that subset,
 * {@link #createBest} falls back to {@link UnicodePieceRenderer}.
 */
public final class SvgPieceRenderer implements PieceRenderer {

    private static final String SET_DIR = "/web/pieces/";
    private static final String TYPE_LETTERS = ".PNBRQK";

    /** One painted element: its outline in viewBox units and how to paint it. */
    private record Layer(Shape shape, Color fill, Color stroke, BasicStroke strokeStyle, float opacity) {}

    /** One parsed file: its viewBox and its layers in document order. */
    private record Drawing(double minX, double minY, double width, double height, List<Layer> layers) {}

    /** Indexed by full piece code (type | color << 3). */
    private final Drawing[] drawings = new Drawing[16];

    private SvgPieceRenderer() {}

    /** The SVG set if it is packaged and readable, otherwise the Unicode glyphs. */
    public static PieceRenderer createBest() {
        try {
            SvgPieceRenderer r = new SvgPieceRenderer();
            for (int color : new int[]{Pieces.WHITE, Pieces.BLACK}) {
                for (int type = Pieces.PAWN; type <= Pieces.KING; type++) {
                    String name = (color == Pieces.WHITE ? "w" : "b") + TYPE_LETTERS.charAt(type) + ".svg";
                    r.drawings[Pieces.make(type, color)] = load(SET_DIR + name);
                }
            }
            return r;
        } catch (Exception e) {
            System.err.println("WARNING: piece set not usable (" + e.getMessage() + "); using Unicode glyphs.");
            return UnicodePieceRenderer.createBest();
        }
    }

    @Override
    public void draw(Graphics2D g, int piece, int x, int y, int size) {
        Drawing d = drawings[piece];
        AffineTransform savedTransform = g.getTransform();
        Composite savedComposite = g.getComposite();
        Stroke savedStroke = g.getStroke();
        Object savedStrokeControl = g.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL);
        // A caller drawing translucently (e.g. a faded piece) keeps its alpha under each layer's opacity.
        float baseAlpha = savedComposite instanceof AlphaComposite ac && ac.getRule() == AlphaComposite.SRC_OVER
                ? ac.getAlpha() : 1f;
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            // Fit the viewBox into the square, centred, keeping its aspect ratio.
            double scale = size / Math.max(d.width(), d.height());
            g.translate(x + (size - d.width() * scale) / 2, y + (size - d.height() * scale) / 2);
            g.scale(scale, scale);
            g.translate(-d.minX(), -d.minY());
            for (Layer l : d.layers()) {
                g.setComposite(l.opacity() < 1
                        ? AlphaComposite.getInstance(AlphaComposite.SRC_OVER, baseAlpha * l.opacity())
                        : savedComposite);
                if (l.fill() != null) {
                    g.setColor(l.fill());
                    g.fill(l.shape());
                }
                if (l.stroke() != null) {
                    g.setColor(l.stroke());
                    g.setStroke(l.strokeStyle());
                    g.draw(l.shape());
                }
            }
        } finally {
            g.setTransform(savedTransform);
            g.setComposite(savedComposite);
            g.setStroke(savedStroke);
            if (savedStrokeControl != null) g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, savedStrokeControl);
        }
    }

    // ---- parsing ----

    private static Drawing load(String resource) throws Exception {
        Element svg;
        try (InputStream in = SvgPieceRenderer.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException(resource + " not packaged");
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            svg = f.newDocumentBuilder().parse(in).getDocumentElement();
        }
        String[] vb = svg.getAttribute("viewBox").trim().split("[\\s,]+");
        if (vb.length != 4) throw new IllegalStateException(resource + ": no viewBox");

        List<Layer> layers = new ArrayList<>();
        NodeList children = svg.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) children.item(i);
            Shape shape = switch (e.getTagName()) {
                case "path" -> path(e.getAttribute("d"), windingRule(attr(e, svg, "fill-rule", "nonzero")));
                case "rect" -> rect(e);
                default -> throw new IllegalStateException(resource + ": unsupported <" + e.getTagName() + ">");
            };
            float opacity = Float.parseFloat(attr(e, svg, "opacity", "1"));
            Color fill = color(attr(e, svg, "fill", "#000"), Float.parseFloat(attr(e, svg, "fill-opacity", "1")));
            Color stroke = color(attr(e, svg, "stroke", "none"), Float.parseFloat(attr(e, svg, "stroke-opacity", "1")));
            BasicStroke style = new BasicStroke(
                    Float.parseFloat(attr(e, svg, "stroke-width", "1")),
                    switch (attr(e, svg, "stroke-linecap", "butt")) {
                        case "round" -> BasicStroke.CAP_ROUND;
                        case "square" -> BasicStroke.CAP_SQUARE;
                        default -> BasicStroke.CAP_BUTT;
                    },
                    switch (attr(e, svg, "stroke-linejoin", "miter")) {
                        case "round" -> BasicStroke.JOIN_ROUND;
                        case "bevel" -> BasicStroke.JOIN_BEVEL;
                        default -> BasicStroke.JOIN_MITER;
                    },
                    4f);
            layers.add(new Layer(shape, fill, stroke, style, opacity));
        }
        return new Drawing(Double.parseDouble(vb[0]), Double.parseDouble(vb[1]),
                Double.parseDouble(vb[2]), Double.parseDouble(vb[3]), layers);
    }

    /** The element's own attribute, else the one set on the root {@code <svg>}, else the SVG default. */
    private static String attr(Element e, Element root, String name, String fallback) {
        if (e.hasAttribute(name)) return e.getAttribute(name);
        if (root.hasAttribute(name)) return root.getAttribute(name);
        return fallback;
    }

    private static int windingRule(String fillRule) {
        return fillRule.equals("evenodd") ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO;
    }

    /** {@code #rgb}, {@code #rrggbb}, white, black or none (null). */
    private static Color color(String value, float alpha) {
        String v = value.trim().toLowerCase();
        if (v.equals("none")) return null;
        if (v.equals("white")) v = "#fff";
        if (v.equals("black")) v = "#000";
        if (!v.startsWith("#")) throw new IllegalStateException("unsupported color " + value);
        String hex = v.substring(1);
        if (hex.length() == 3) {
            hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
        }
        int rgb = Integer.parseInt(hex, 16);
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, Math.round(alpha * 255));
    }

    private static Shape rect(Element e) {
        double rx = e.hasAttribute("rx") ? Double.parseDouble(e.getAttribute("rx")) : -1;
        double ry = e.hasAttribute("ry") ? Double.parseDouble(e.getAttribute("ry")) : -1;
        if (rx < 0) rx = Math.max(ry, 0);   // a missing radius takes the other one
        if (ry < 0) ry = rx;
        return new RoundRectangle2D.Double(
                Double.parseDouble(e.getAttribute("x")), Double.parseDouble(e.getAttribute("y")),
                Double.parseDouble(e.getAttribute("width")), Double.parseDouble(e.getAttribute("height")),
                2 * rx, 2 * ry);
    }

    /** SVG path data, every command: M L H V C S Q T A Z, absolute and relative. */
    static Path2D.Double path(String d, int windingRule) {
        Path2D.Double p = new Path2D.Double(windingRule);
        PathReader r = new PathReader(d);
        double cx = 0, cy = 0;          // current point
        double sx = 0, sy = 0;          // start of the current subpath
        double qx = 0, qy = 0;          // last control point, for S / T reflection
        char cmd = 0, prev = 0;
        while (r.skipSeparators()) {
            char c = r.peek();
            if (Character.isLetter(c)) {
                cmd = c;
                r.advance();
            } else if (cmd == 0) {
                throw new IllegalArgumentException("path data must start with a command: " + d);
            } else if (cmd == 'M') {
                cmd = 'L';              // extra pairs after a moveto are linetos
            } else if (cmd == 'm') {
                cmd = 'l';
            }
            boolean rel = Character.isLowerCase(cmd);
            double ox = rel ? cx : 0, oy = rel ? cy : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M' -> {
                    cx = ox + r.number(); cy = oy + r.number();
                    p.moveTo(cx, cy);
                    sx = cx; sy = cy;
                }
                case 'L' -> { cx = ox + r.number(); cy = oy + r.number(); p.lineTo(cx, cy); }
                case 'H' -> { cx = ox + r.number(); p.lineTo(cx, cy); }
                case 'V' -> { cy = oy + r.number(); p.lineTo(cx, cy); }
                case 'C' -> {
                    double x1 = ox + r.number(), y1 = oy + r.number();
                    qx = ox + r.number(); qy = oy + r.number();
                    cx = ox + r.number(); cy = oy + r.number();
                    p.curveTo(x1, y1, qx, qy, cx, cy);
                }
                case 'S' -> {
                    boolean smooth = "CcSs".indexOf(prev) >= 0;
                    double x1 = smooth ? 2 * cx - qx : cx, y1 = smooth ? 2 * cy - qy : cy;
                    qx = ox + r.number(); qy = oy + r.number();
                    cx = ox + r.number(); cy = oy + r.number();
                    p.curveTo(x1, y1, qx, qy, cx, cy);
                }
                case 'Q' -> {
                    qx = ox + r.number(); qy = oy + r.number();
                    cx = ox + r.number(); cy = oy + r.number();
                    p.quadTo(qx, qy, cx, cy);
                }
                case 'T' -> {
                    boolean smooth = "QqTt".indexOf(prev) >= 0;
                    qx = smooth ? 2 * cx - qx : cx; qy = smooth ? 2 * cy - qy : cy;
                    cx = ox + r.number(); cy = oy + r.number();
                    p.quadTo(qx, qy, cx, cy);
                }
                case 'A' -> {
                    double rx = r.number(), ry = r.number(), phi = r.number();
                    boolean large = r.flag(), sweep = r.flag();
                    double x2 = ox + r.number(), y2 = oy + r.number();
                    arc(p, cx, cy, rx, ry, phi, large, sweep, x2, y2);
                    cx = x2; cy = y2;
                }
                case 'Z' -> { p.closePath(); cx = sx; cy = sy; }
                default -> throw new IllegalArgumentException("unknown path command " + cmd);
            }
            prev = cmd;
        }
        return p;
    }

    /** Endpoint arc to Arc2D, per the SVG spec's implementation notes (F.6.5 / F.6.6). */
    private static void arc(Path2D.Double p, double x1, double y1, double rx, double ry, double phiDeg,
                            boolean large, boolean sweep, double x2, double y2) {
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        if (rx == 0 || ry == 0 || (x1 == x2 && y1 == y2)) {
            p.lineTo(x2, y2);
            return;
        }
        double phi = Math.toRadians(phiDeg), cos = Math.cos(phi), sin = Math.sin(phi);
        double dx = (x1 - x2) / 2, dy = (y1 - y2) / 2;
        double x1p = cos * dx + sin * dy, y1p = -sin * dx + cos * dy;
        double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lambda > 1) {               // radii too small to reach: scale them up
            rx *= Math.sqrt(lambda);
            ry *= Math.sqrt(lambda);
        }
        double num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double coef = Math.sqrt(Math.max(0, num / den)) * (large == sweep ? -1 : 1);
        double cxp = coef * rx * y1p / ry, cyp = -coef * ry * x1p / rx;
        double centerX = cos * cxp - sin * cyp + (x1 + x2) / 2;
        double centerY = sin * cxp + cos * cyp + (y1 + y2) / 2;
        double theta1 = Math.atan2((y1p - cyp) / ry, (x1p - cxp) / rx);
        double delta = Math.atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx) - theta1;
        if (!sweep && delta > 0) delta -= 2 * Math.PI;
        if (sweep && delta < 0) delta += 2 * Math.PI;
        // Arc2D angles grow counter-clockwise on screen (y down), SVG's clockwise: negate both.
        Arc2D.Double a = new Arc2D.Double(-rx, -ry, 2 * rx, 2 * ry,
                Math.toDegrees(-theta1), Math.toDegrees(-delta), Arc2D.OPEN);
        AffineTransform at = AffineTransform.getTranslateInstance(centerX, centerY);
        at.rotate(phi);
        p.append(a.getPathIterator(at), true);
    }

    /** Tokenizer for minified path data: "1.5.5" is two numbers, "-1-2" too, arc flags may touch. */
    private static final class PathReader {
        private final String s;
        private int i;

        PathReader(String s) { this.s = s; }

        char peek() { return s.charAt(i); }

        void advance() { i++; }

        /** Skips whitespace and commas; false at the end of the data. */
        boolean skipSeparators() {
            while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
            return i < s.length();
        }

        boolean flag() {
            if (!skipSeparators()) throw new IllegalArgumentException("arc flag expected: " + s);
            char c = s.charAt(i++);
            if (c != '0' && c != '1') throw new IllegalArgumentException("bad arc flag '" + c + "': " + s);
            return c == '1';
        }

        double number() {
            if (!skipSeparators()) throw new IllegalArgumentException("number expected: " + s);
            int start = i;
            if (s.charAt(i) == '+' || s.charAt(i) == '-') i++;
            boolean dot = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isDigit(c)) i++;
                else if (c == '.' && !dot) { dot = true; i++; }
                else break;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            if (i == start) throw new IllegalArgumentException("number expected at " + start + ": " + s);
            return Double.parseDouble(s.substring(start, i));
        }
    }
}

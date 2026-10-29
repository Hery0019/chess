package ui;

import java.awt.Graphics2D;

/**
 * Renders one piece into a square. An interface (approved rendering decision)
 * so the piece look can change without touching BoardPanel: the SVG set
 * ({@link SvgPieceRenderer}) by default, the Unicode glyphs as a fallback.
 */
public interface PieceRenderer {
    /**
     * @param piece full engine piece code (never EMPTY)
     * @param x,y   top-left of the square
     * @param size  square side length in pixels
     */
    void draw(Graphics2D g, int piece, int x, int y, int size);
}

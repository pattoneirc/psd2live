package io.github.psd2live.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.geom.Arc2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage

/**
 * Custom canvas cursors AWT has no stock shape for. Drawn twice - a white halo, then a dark core - so
 * they stay legible over light, dark and busy artwork alike.
 */
internal object CanvasCursors {
    private val DARK_CORE = Color(0x1B, 0x1F, 0x27)
    private val WHITE_HALO = Color.WHITE

    /** Photoshop's pipette: the hotspot is the tip, so the pixel read is the one the tip touches. */
    val eyedropper: Cursor by lazy {
        cursor("psd2live-eyedropper", 3, 28) { g ->
            // A tube from the tip (lower left) up to the bulb (upper right), in a canonical 32x32 space.
            val tube = Path2D.Float().apply {
                moveTo(3.0, 28.0)
                lineTo(5.0, 22.0)
                lineTo(17.0, 10.0)
                lineTo(21.0, 14.0)
                lineTo(9.0, 26.0)
                closePath()
            }
            val collar = Path2D.Float().apply {
                moveTo(14.5, 9.5)
                lineTo(21.5, 16.5)
            }
            val bulb = Path2D.Float().apply {
                moveTo(18.0, 8.5)
                lineTo(23.5, 3.0)
                quadTo(27.5, 1.5, 29.0, 3.0)
                quadTo(30.5, 4.5, 29.0, 8.5)
                lineTo(23.5, 14.0)
                closePath()
            }

            g.color = WHITE_HALO
            g.stroke = BasicStroke(4.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(tube)
            g.draw(collar)
            g.draw(bulb)
            g.fill(bulb)

            g.color = DARK_CORE
            g.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(tube)
            g.fill(bulb)
            g.stroke = BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(collar)
        }
    }

    /** A turn: three quarters of a circle with an arrowhead at each end. The hotspot is its centre. */
    val rotate: Cursor by lazy {
        cursor("psd2live-rotate", 16, 16) { g ->
            val arc = Arc2D.Float(7f, 7f, 18f, 18f, 120f, 270f, Arc2D.OPEN)
            // Arrowheads at both ends of the arc, pointing on along it: back past 120°, and on past 30°.
            fun head(deg: Double, along: Double): Path2D.Float {
                val r = Math.toRadians(deg)
                val x = 16.0 + 9.0 * Math.cos(r)
                val y = 16.0 - 9.0 * Math.sin(r)
                val a = Math.toRadians(along)
                val dx = Math.cos(a); val dy = -Math.sin(a)
                return Path2D.Float().apply {
                    moveTo(x + dx * 4.5, y + dy * 4.5)
                    lineTo(x - dy * 4.0 - dx * 1.5, y + dx * 4.0 - dy * 1.5)
                    lineTo(x + dy * 4.0 - dx * 1.5, y - dx * 4.0 - dy * 1.5)
                    closePath()
                }
            }
            val heads = listOf(head(120.0, 30.0), head(30.0, 120.0))
            g.color = WHITE_HALO
            g.stroke = BasicStroke(4.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(arc)
            heads.forEach { g.draw(it); g.fill(it) }
            g.color = DARK_CORE
            g.stroke = BasicStroke(1.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
            g.draw(arc)
            heads.forEach { g.fill(it) }
        }
    }

    /**
     * The pointer a transform box handle shows: resize arrows along the handle's own direction through the
     * box's turn [angleDeg], the turn cursor on the rotate grip and the turn zones, a cross on the anchor.
     */
    fun transform(handle: BoundingHandle, angleDeg: Float): Cursor {
        fun stock(type: Int) = Cursor.getPredefinedCursor(type)
        val base = when (handle) {
            BoundingHandle.RIGHT, BoundingHandle.LEFT -> 0f
            BoundingHandle.BOTTOM_RIGHT, BoundingHandle.TOP_LEFT -> 45f
            BoundingHandle.BOTTOM, BoundingHandle.TOP -> 90f
            BoundingHandle.BOTTOM_LEFT, BoundingHandle.TOP_RIGHT -> 135f
            BoundingHandle.BODY -> return stock(Cursor.MOVE_CURSOR)
            BoundingHandle.ROTATE -> return rotate
            BoundingHandle.ANCHOR -> return stock(Cursor.CROSSHAIR_CURSOR)
            BoundingHandle.NONE -> return Cursor.getDefaultCursor()
        }
        // AWT has four resize axes; the handle's axis, turned with the box, picks the nearest one.
        val axis = Math.floorMod(Math.round((base + angleDeg) / 45f), 4)
        return stock(when (axis) {
            0 -> Cursor.E_RESIZE_CURSOR
            1 -> Cursor.NW_RESIZE_CURSOR
            2 -> Cursor.N_RESIZE_CURSOR
            else -> Cursor.NE_RESIZE_CURSOR
        })
    }

    private fun cursor(name: String, hotX: Int, hotY: Int, paint: (Graphics2D) -> Unit): Cursor {
        val size = Toolkit.getDefaultToolkit().getBestCursorSize(32, 32).let { dim ->
            if (dim.width <= 0 || dim.height <= 0) 32 else maxOf(dim.width, dim.height)
        }
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        if (size != 32) g.scale(size / 32.0, size / 32.0)
        paint(g)
        g.dispose()
        return Toolkit.getDefaultToolkit().createCustomCursor(image, Point(hotX * size / 32, hotY * size / 32), name)
    }
}

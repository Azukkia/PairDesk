package io.github.azukkia.pairdesk.viewer

import kotlin.math.floor
import kotlin.math.min

/** A point of the remote screen, normalized to the displayed picture (`0 ≤ x, y ≤ 1`, 4 decimals like viewer.js). */
data class RemotePoint(val x: Double, val y: Double) {
    companion object {
        /** `Math.round(v * 10000) / 10000` of viewer.js (half up). */
        fun round4(v: Double): Double = floor(v * 10_000 + 0.5) / 10_000

        /** A rounded point, clamped to the picture. */
        fun of(x: Double, y: Double): RemotePoint = RemotePoint(round4(x.coerceIn(0.0, 1.0)), round4(y.coerceIn(0.0, 1.0)))

        val CENTER = RemotePoint(0.5, 0.5)
    }
}

/** Where the remote picture is drawn in the view, in view pixels. */
data class Placement(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/** A point in view pixels. */
data class ViewPoint(val x: Float, val y: Float)

/**
 * The zoom state of the view: [zoom] (1 = the whole picture fits the view,
 * letterboxed) and the point of the picture shown at the center of the view
 * ([centerX], [centerY], normalized). Independent of the view size, so a
 * rotation keeps the same part of the remote screen in the middle.
 */
data class ViewZoom(val zoom: Float = 1f, val centerX: Float = 0.5f, val centerY: Float = 0.5f) {
    val isZoomed: Boolean get() = zoom > 1.001f

    companion object {
        const val MIN = 1f
        const val MAX = 5f
        val FIT = ViewZoom()
    }
}

/**
 * Remote screen ⇄ view coordinates for a picture of [frameWidth]×[frameHeight]
 * shown "fit" (letterboxed) in a view of [viewWidth]×[viewHeight] pixels, then
 * zoomed and panned (pinch). Pure and immutable: the same instance computes
 * the drawing transform and maps touches, so both always agree.
 *
 * Panning keeps the picture inside the view: along an axis where the zoomed
 * picture is smaller than the view it stays centered, otherwise it always
 * covers the view.
 */
class ViewGeometry(
    val viewWidth: Float,
    val viewHeight: Float,
    val frameWidth: Int,
    val frameHeight: Int,
) {
    val isReady: Boolean = viewWidth > 0f && viewHeight > 0f && frameWidth > 0 && frameHeight > 0

    /** Scale of the picture at zoom 1 (view pixels per frame pixel). */
    val fitScale: Float = if (isReady) min(viewWidth / frameWidth, viewHeight / frameHeight) else 0f

    fun placement(z: ViewZoom): Placement {
        val w = frameWidth * fitScale * z.zoom
        val h = frameHeight * fitScale * z.zoom
        return Placement(axisStart(viewWidth, w, z.centerX), axisStart(viewHeight, h, z.centerY), w, h)
    }

    private fun axisStart(view: Float, content: Float, center: Float): Float =
        if (content <= view) (view - content) / 2 else (view / 2 - center * content).coerceIn(view - content, 0f)

    /** [z] with its zoom clamped and its center moved where the picture really is (canonical form). */
    fun normalize(z: ViewZoom): ViewZoom {
        if (!isReady) return ViewZoom.FIT
        val zoom = z.zoom.coerceIn(ViewZoom.MIN, ViewZoom.MAX)
        val p = placement(z.copy(zoom = zoom))
        return ViewZoom(zoom, centerOf(viewWidth, p.left, p.width), centerOf(viewHeight, p.top, p.height))
    }

    private fun centerOf(view: Float, start: Float, size: Float): Float = if (size <= 0f) 0.5f else (view / 2 - start) / size

    private fun fromPlacement(zoom: Float, left: Float, top: Float): ViewZoom {
        val w = frameWidth * fitScale * zoom
        val h = frameHeight * fitScale * zoom
        return normalize(ViewZoom(zoom, centerOf(viewWidth, left, w), centerOf(viewHeight, top, h)))
    }

    /**
     * The remote point under the view point ([x], [y]): null outside the
     * picture, unless [clamp] (then the nearest point of the picture).
     */
    fun toRemote(z: ViewZoom, x: Float, y: Float, clamp: Boolean = false): RemotePoint? {
        if (!isReady) return null
        val p = placement(z)
        val nx = ((x - p.left) / p.width).toDouble()
        val ny = ((y - p.top) / p.height).toDouble()
        if (!clamp && (nx < 0.0 || nx > 1.0 || ny < 0.0 || ny > 1.0)) return null
        return RemotePoint.of(nx, ny)
    }

    /** Unrounded normalized coordinates of a view point (may be outside 0..1). */
    fun toNormalized(z: ViewZoom, x: Float, y: Float): Pair<Double, Double>? {
        if (!isReady) return null
        val p = placement(z)
        return ((x - p.left) / p.width).toDouble() to ((y - p.top) / p.height).toDouble()
    }

    /** The view point showing the remote point ([nx], [ny]). */
    fun toView(z: ViewZoom, nx: Double, ny: Double): ViewPoint {
        val p = placement(z)
        return ViewPoint(p.left + (nx * p.width).toFloat(), p.top + (ny * p.height).toFloat())
    }

    /** Zooms to [zoom] keeping the remote point under the view point ([focusX], [focusY]) where it is. */
    fun zoomAround(z: ViewZoom, focusX: Float, focusY: Float, zoom: Float): ViewZoom {
        if (!isReady) return ViewZoom.FIT
        val (fx, fy) = toNormalized(z, focusX, focusY) ?: return z
        return anchored(zoom, fx, fy, focusX, focusY)
    }

    /**
     * Pinch: at [zoom], the picture is placed so that its normalized point
     * ([anchorX], [anchorY]) (the one under the fingers when the pinch began)
     * is under the fingers' current center ([atX], [atY]).
     */
    fun anchored(zoom: Float, anchorX: Double, anchorY: Double, atX: Float, atY: Float): ViewZoom {
        if (!isReady) return ViewZoom.FIT
        val zz = zoom.coerceIn(ViewZoom.MIN, ViewZoom.MAX)
        val w = frameWidth * fitScale * zz
        val h = frameHeight * fitScale * zz
        return fromPlacement(zz, atX - (anchorX * w).toFloat(), atY - (anchorY * h).toFloat())
    }

    /** Moves the picture by ([dx], [dy]) view pixels (a finger dragging it). */
    fun panBy(z: ViewZoom, dx: Float, dy: Float): ViewZoom {
        if (!isReady) return ViewZoom.FIT
        val p = placement(z)
        return fromPlacement(z.zoom, p.left + dx, p.top + dy)
    }

    /** Pans as little as possible so that the remote point ([nx], [ny]) is at least [margin] pixels inside the view. */
    fun ensureVisible(z: ViewZoom, nx: Double, ny: Double, margin: Float): ViewZoom {
        if (!isReady || !z.isZoomed) return z
        val p = placement(z)
        val v = toView(z, nx, ny)
        val mx = min(margin, viewWidth / 2)
        val my = min(margin, viewHeight / 2)
        var left = p.left
        var top = p.top
        if (v.x < mx) left += mx - v.x else if (v.x > viewWidth - mx) left -= v.x - (viewWidth - mx)
        if (v.y < my) top += my - v.y else if (v.y > viewHeight - my) top -= v.y - (viewHeight - my)
        if (left == p.left && top == p.top) return z
        return fromPlacement(z.zoom, left, top)
    }

    override fun equals(other: Any?): Boolean =
        other is ViewGeometry && other.viewWidth == viewWidth && other.viewHeight == viewHeight &&
            other.frameWidth == frameWidth && other.frameHeight == frameHeight

    override fun hashCode(): Int = ((viewWidth.hashCode() * 31 + viewHeight.hashCode()) * 31 + frameWidth) * 31 + frameHeight

    override fun toString(): String = "ViewGeometry(view=${viewWidth}x$viewHeight, frame=${frameWidth}x$frameHeight)"
}

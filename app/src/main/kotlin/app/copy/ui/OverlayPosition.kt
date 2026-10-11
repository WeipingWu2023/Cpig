package app.copy.ui

internal data class OverlayPosition(val x: Int, val y: Int)

/** Window coordinates remain usable when a panel resizes or the phone rotates. */
internal fun fitOverlay(
    x: Int, y: Int, width: Int, height: Int, viewportWidth: Int, viewportHeight: Int,
    dockRight: Boolean? = null,
): OverlayPosition {
    val maxX = (viewportWidth - width).coerceAtLeast(0)
    val maxY = (viewportHeight - height).coerceAtLeast(0)
    return OverlayPosition(
        when (dockRight) { true -> maxX; false -> 0; null -> x.coerceIn(0, maxX) },
        y.coerceIn(0, maxY),
    )
}

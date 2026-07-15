package com.orangepet.app.service

/** Pure overlay-position clamping math, kept free of `android.*` for unit testing. */
object OverlayMath {

    /** Never negative, never past the right edge of the screen. */
    fun maximumX(screenWidthPx: Int, overlayWidthPx: Int): Int =
        maxOf(0, screenWidthPx - overlayWidthPx)

    fun clampX(x: Int, screenWidthPx: Int, overlayWidthPx: Int): Int =
        x.coerceIn(0, maximumX(screenWidthPx, overlayWidthPx))
}

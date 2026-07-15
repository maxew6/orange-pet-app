package com.orangepet.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayMathTest {

    @Test
    fun maximumX_neverNegative_whenOverlayWiderThanScreen() {
        assertEquals(0, OverlayMath.maximumX(screenWidthPx = 100, overlayWidthPx = 400))
    }

    @Test
    fun maximumX_isScreenMinusOverlay_whenPositive() {
        assertEquals(872, OverlayMath.maximumX(screenWidthPx = 1000, overlayWidthPx = 128))
    }

    @Test
    fun clampX_belowZero_clampsToZero() {
        assertEquals(0, OverlayMath.clampX(-50, screenWidthPx = 1000, overlayWidthPx = 128))
    }

    @Test
    fun clampX_aboveMaximum_clampsToMaximum() {
        assertEquals(872, OverlayMath.clampX(5000, screenWidthPx = 1000, overlayWidthPx = 128))
    }

    @Test
    fun clampX_withinRange_isUnchanged() {
        assertEquals(500, OverlayMath.clampX(500, screenWidthPx = 1000, overlayWidthPx = 128))
    }
}

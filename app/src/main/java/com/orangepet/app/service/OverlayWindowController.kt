package com.orangepet.app.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.SavedStateRegistryOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Owns the `WindowManager`-attached `ComposeView`: adds it, moves it
 * during walking, and removes it safely. [isAttached] is checked before
 * every `updateViewLayout`/`removeView` call so nothing ever touches a
 * detached view.
 *
 * A `ComposeView` attached directly to `WindowManager` has no Activity to
 * inherit lifecycle/saved-state/view-model owners from, so the three owner
 * interfaces are installed explicitly before `setContent`/`addView`.
 *
 * **Caller contract:** [lifecycleOwner]'s lifecycle must already be at
 * least `STARTED` *before* calling [attach]. Compose's Recomposer only
 * actively recomposes once the associated lifecycle reaches `STARTED`;
 * attaching first and moving to `STARTED` afterward risks the first frame
 * rendering from the initial state and then never updating again. See
 * `FloatingPetService.initializeOverlay()`.
 */
class OverlayWindowController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedStateRegistryOwner: SavedStateRegistryOwner,
    private val viewModelStoreOwner: ViewModelStoreOwner
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    var isAttached: Boolean = false
        private set
    var overlayWidthPx: Int = 0
        private set
    var overlayHeightPx: Int = 0
        private set

    fun attach(widthDp: Int, heightDp: Int, bottomMarginDp: Int, content: @Composable () -> Unit) {
        val density = context.resources.displayMetrics.density
        overlayWidthPx = (widthDp * density).toInt()
        overlayHeightPx = (heightDp * density).toInt()
        val bottomMarginPx = (bottomMarginDp * density).toInt()

        val composeView = ComposeView(context)
        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
        composeView.setViewTreeViewModelStoreOwner(viewModelStoreOwner)
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnLifecycleDestroyed(lifecycleOwner)
        )
        composeView.setContent(content)

        val params = WindowManager.LayoutParams(
            overlayWidthPx,
            overlayHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.BOTTOM
            y = bottomMarginPx
            x = 0
        }

        try {
            windowManager.addView(composeView, params)
            isAttached = true
            overlayView = composeView
            layoutParams = params
        } catch (t: Throwable) {
            // Overlay permission may have been revoked after the service started; fail safely.
            isAttached = false
            overlayView = null
            layoutParams = null
        }
    }

    fun moveTo(xPx: Int) {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        if (!isAttached) return
        params.x = xPx
        try {
            windowManager.updateViewLayout(view, params)
        } catch (t: Throwable) {
            // Concurrently detached (e.g. during shutdown); avoid crashing the movement loop.
        }
    }

    fun currentScreenWidthPx(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.currentWindowMetrics.bounds.width()
        }
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(displayMetrics)
        return displayMetrics.widthPixels
    }

    fun detach() {
        val view = overlayView
        if (isAttached && view != null) {
            try {
                windowManager.removeView(view)
            } catch (t: Throwable) {
                // Already detached by the system; ignore.
            }
        }
        isAttached = false
        overlayView = null
        layoutParams = null
    }
}

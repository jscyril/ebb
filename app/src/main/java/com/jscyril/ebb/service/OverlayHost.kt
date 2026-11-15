package com.jscyril.ebb.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** Accessibility overlay hosting Compose content; Back calls [onBack]. Main thread only. */
class OverlayHost(private val service: AccessibilityService, private val onBack: () -> Unit) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var compose: ComposeView? = null
    private var owner: Owner? = null
    private var shownBounds: Rect? = null

    val isShowing get() = root != null

    /**
     * Full-screen and focusable when [bounds] is null. With [bounds] (a watched app visible only in PiP or
     * split screen) it covers just that rect and lets touches elsewhere through, so the user is never trapped.
     */
    fun show(bounds: Rect? = null, content: @Composable () -> Unit) {
        root?.let { r ->
            // only on change: a same-rect relayout could feed back as another windows-changed event
            if (bounds != shownBounds) { runCatching { wm.updateViewLayout(r, params(bounds)) }; shownBounds = bounds }
            compose?.setContent(content)
            return
        }
        val o = Owner()
        val cv = ComposeView(service).apply { setContent(content) }
        val frame = object : FrameLayout(service) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e)
                if (e.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
        }
        frame.setViewTreeLifecycleOwner(o)
        frame.setViewTreeSavedStateRegistryOwner(o)
        frame.setViewTreeViewModelStoreOwner(o)
        frame.addView(cv)
        try {
            wm.addView(frame, params(bounds))
        } catch (e: RuntimeException) {
            o.destroy()
            throw e
        }
        root = frame; compose = cv; owner = o; shownBounds = bounds
    }

    private fun params(b: Rect?): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            b?.width() ?: WindowManager.LayoutParams.MATCH_PARENT, b?.height() ?: WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or (if (b == null) 0 else
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL),
            PixelFormat.TRANSLUCENT,
        )
        if (b != null) { lp.gravity = Gravity.TOP or Gravity.START; lp.x = b.left; lp.y = b.top }
        else {
            // Edge to edge: content insets itself with WindowInsets.safeDrawing.
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (Build.VERSION.SDK_INT >= 30) lp.fitInsetsTypes = 0
        }
        return lp
    }

    fun dismiss() {
        val f = root ?: return
        root = null; compose = null
        owner?.destroy(); owner = null
        runCatching { wm.removeView(f) }
    }

    private class Owner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        override val viewModelStore = ViewModelStore()

        init {
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}

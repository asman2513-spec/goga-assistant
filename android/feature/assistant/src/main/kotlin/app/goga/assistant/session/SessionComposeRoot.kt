package app.goga.assistant.session

import android.content.Context
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

/**
 * VoiceInteractionSession has no Activity, so Compose needs lifecycle and saved state
 * owners installed on the view before it attaches.
 */
class SessionComposeRoot(context: Context) : FrameLayout(context) {
    private val owners = SessionOwners()
    private val composeView = ComposeView(context)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setViewTreeLifecycleOwner(owners)
        setViewTreeViewModelStoreOwner(owners)
        setViewTreeSavedStateRegistryOwner(owners)
        composeView.isFocusable = true
        composeView.isFocusableInTouchMode = true
        composeView.setViewTreeLifecycleOwner(owners)
        composeView.setViewTreeViewModelStoreOwner(owners)
        composeView.setViewTreeSavedStateRegistryOwner(owners)
        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        owners.create()
    }

    fun setSessionContent(content: @Composable () -> Unit) {
        composeView.setContent(content)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        owners.resume()
    }

    override fun onDetachedFromWindow() {
        owners.destroy()
        super.onDetachedFromWindow()
    }
}

private class SessionOwners :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedController.savedStateRegistry

    fun create() {
        if (registry.currentState != Lifecycle.State.INITIALIZED) return
        savedController.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun resume() {
        create()
        if (registry.currentState == Lifecycle.State.CREATED) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (registry.currentState == Lifecycle.State.STARTED) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    fun destroy() {
        if (registry.currentState == Lifecycle.State.DESTROYED ||
            registry.currentState == Lifecycle.State.INITIALIZED
        ) {
            viewModelStore.clear()
            return
        }
        if (registry.currentState == Lifecycle.State.RESUMED) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        if (registry.currentState == Lifecycle.State.STARTED) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        if (registry.currentState == Lifecycle.State.CREATED) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        viewModelStore.clear()
    }
}

package androidx.lifecycle.compose

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** A lifecycle that stays created: the screens never count as started, so no phone server starts. */
object RenderLifecycleOwner : LifecycleOwner {
    override val lifecycle = object : Lifecycle() {
        override val currentState = State.CREATED
        override fun addObserver(observer: LifecycleObserver) {}
        override fun removeObserver(observer: LifecycleObserver) {}
    }
}

val LocalLifecycleOwner = staticCompositionLocalOf<LifecycleOwner> { RenderLifecycleOwner }

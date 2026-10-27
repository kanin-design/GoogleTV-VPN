package androidx.lifecycle

/** Stands in for AndroidX's lifecycle, with the parts the screens use. */
abstract class Lifecycle {
    enum class Event { ON_CREATE, ON_START, ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY, ON_ANY }

    enum class State {
        DESTROYED, INITIALIZED, CREATED, STARTED, RESUMED;

        fun isAtLeast(state: State) = compareTo(state) >= 0
    }

    abstract val currentState: State
    abstract fun addObserver(observer: LifecycleObserver)
    abstract fun removeObserver(observer: LifecycleObserver)
}

interface LifecycleObserver

fun interface LifecycleEventObserver : LifecycleObserver {
    fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event)
}

interface LifecycleOwner {
    val lifecycle: Lifecycle
}

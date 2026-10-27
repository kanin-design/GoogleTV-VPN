package render

/** Android's main-thread Handler. There is no main looper here, so what is posted to it is dropped. */
object MainThread {
    fun post(r: Runnable): Boolean = true
    fun postDelayed(r: Runnable, delayMillis: Long): Boolean = true
    fun removeCallbacks(r: Runnable) {}
}

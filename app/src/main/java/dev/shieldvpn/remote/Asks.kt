package dev.shieldvpn.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The questions a phone makes the TV ask: "Pair Pixel 8?", "Pixel 8 wants to delete Work login."
 * The screens show the first one as a sheet, and the remote answers it. While the screens are not
 * in front nobody can answer, so nothing is asked and the phone is told where to go.
 */
object Asks {
    data class Ask(
        val id: Long,
        val eyebrow: String,
        val title: String,
        val text: String,
        val yes: String,
        val no: String = "Not now",
        val danger: Boolean = false,
        /** Asked by a phone that is paired already, rather than one pairing with a code. */
        val paired: Boolean = true,
    )

    private class Waiting(val ask: Ask) {
        var answer: Boolean? = null
    }

    private val lock = Object()
    private val queue = ArrayList<Waiting>()
    private var next = 1L

    private val _current = MutableStateFlow<Ask?>(null)

    /** The question the TV shows now. */
    val current: StateFlow<Ask?> = _current.asStateFlow()

    /** Set by the screens: true while they are in front and can show a question. */
    @Volatile var screensInFront = false

    fun ask(eyebrow: String, title: String, text: String, yes: String, danger: Boolean = false, paired: Boolean = true): Ask =
        synchronized(lock) { Ask(next++, eyebrow, title, text, yes, danger = danger, paired = paired) }

    /**
     * Shows [ask] on the TV and waits on this thread for the answer: true for [Ask.yes], false for
     * no, null when nobody can answer (the screens are not in front) or no answer came in [ms].
     */
    fun await(ask: Ask, ms: Long): Boolean? {
        if (!screensInFront) return null
        val w = Waiting(ask)
        val end = System.currentTimeMillis() + ms
        synchronized(lock) {
            queue += w
            publish()
            while (w.answer == null && screensInFront) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                lock.wait(minOf(left, 1_000))
            }
            queue -= w
            publish()
            return w.answer
        }
    }

    /** The remote answered the question [id]. */
    fun answer(id: Long, yes: Boolean) = synchronized(lock) {
        queue.firstOrNull { it.ask.id == id }?.answer = yes
        lock.notifyAll()
    }

    /** The screens went away: every question waiting ends without an answer. */
    fun dropAll() = synchronized(lock) {
        lock.notifyAll()
    }

    private fun publish() {
        _current.value = queue.firstOrNull { it.answer == null }?.ask
    }
}

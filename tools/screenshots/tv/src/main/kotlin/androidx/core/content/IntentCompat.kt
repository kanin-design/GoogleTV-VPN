package androidx.core.content

import android.content.Intent

/** Stands in for AndroidX's: nothing arrives with an intent here. */
object IntentCompat {
    fun <T> getParcelableExtra(intent: Intent, name: String, clazz: Class<T>): T? = null
}

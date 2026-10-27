package androidx.compose.ui.platform

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.staticCompositionLocalOf

/** Stands in for Compose for Android's: a context with nothing behind it, for screens that only hold on to one. */
val LocalContext = staticCompositionLocalOf<Context> { ContextWrapper(null) }

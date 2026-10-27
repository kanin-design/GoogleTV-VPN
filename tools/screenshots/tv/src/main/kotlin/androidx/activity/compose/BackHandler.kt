package androidx.activity.compose

import androidx.compose.runtime.Composable

/** Stands in for AndroidX's: a still picture has no Back to handle. */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {}

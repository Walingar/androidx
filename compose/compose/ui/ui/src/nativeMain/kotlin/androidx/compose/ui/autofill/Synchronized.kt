package androidx.compose.ui.autofill

// TODO: Instead of multiple syncronized for different packages
// maybe have a common in the common compose.
inline fun <R> synchronized(lock: Any, block: () -> R): R = block()


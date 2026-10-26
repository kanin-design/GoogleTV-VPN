package dev.shieldvpn.vpn

import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.IOException

/*
 * The stores' files are replaced whole: written beside the old one, flushed to disk, then
 * renamed over it. A TV unplugged in the middle keeps the old file instead of half a new one.
 */

fun writeAtomically(file: File, text: String) {
    val af = AtomicFile(file)
    val out = af.startWrite()
    try {
        out.write(text.toByteArray())
        af.finishWrite(out)
    } catch (e: IOException) {
        af.failWrite(out)
        throw e
    }
}

/** The file's text, or null when it is missing or can't be read. */
fun readAtomically(file: File): String? =
    runCatching { String(AtomicFile(file).readFully()) }.getOrNull()

/**
 * Keeps a copy of a store's file that could not be read, so the next write does not lose it,
 * and says so in the system log. The app then starts as if the file were empty.
 */
fun setAside(file: File, problem: Throwable) {
    Log.e("Stores", "${file.name} could not be read: ${problem.message}")
    runCatching { file.copyTo(File(file.parentFile, "${file.name}.damaged"), overwrite = true) }
}

package dev.shieldvpn.control

import android.graphics.BitmapFactory
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.shieldvpn.vpn.Backup
import dev.shieldvpn.vpn.Certificates
import dev.shieldvpn.vpn.Inspected
import dev.shieldvpn.vpn.ServerStore
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * What arrived, before the stores look at it: a picture is read for a QR code (as a WireGuard
 * server's app shows one) and a .zip is opened for the profile inside. Everything else goes on
 * as it came. The file's name never decides; only its content does.
 */
object Detect {

    sealed interface Unwrapped {
        /** [note] says what was done to get here, for the review ("From the QR code in photo.jpg"). */
        data class File(val name: String, val bytes: ByteArray, val note: String? = null) : Unwrapped
        data class Problem(val message: String) : Unwrapped
    }

    private const val MAX_ENTRY = 256 * 1024
    private const val MAX_ENTRIES = 64

    fun unwrap(name: String, bytes: ByteArray): Unwrapped = when {
        isImage(bytes) -> fromPicture(name, bytes)
        isZip(bytes) -> fromZip(name, bytes)
        else -> Unwrapped.File(name, bytes)
    }

    /**
     * What a file is, once unwrapped: a backup, a .p12 certificate, or a profile with its review.
     * The bytes returned are the unwrapped ones, the file inside a .zip or the text of a QR code.
     */
    fun inspect(name: String, bytes: ByteArray): Pair<Inspected, ByteArray> {
        val (file, data, note) = when (val u = unwrap(name, bytes)) {
            is Unwrapped.Problem -> return Inspected.Problem(u.message) to bytes
            is Unwrapped.File -> Triple(u.name, u.bytes, u.note)
        }
        val what = when {
            Backup.isBackup(data) -> Inspected.Backup
            Certificates.isPkcs12(file, data) -> Inspected.Pkcs12
            else -> ServerStore.review(file.ifBlank { "pasted.ovpn" }, data).let { r ->
                r.problem?.let { Inspected.Problem(it) } ?: Inspected.Profile(r.copy(note = note))
            }
        }
        return what to data
    }

    private fun isImage(b: ByteArray): Boolean =
        b.size > 12 && (
            (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte()) ||
                (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) ||
                (String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WEBP")
            )

    private fun isZip(b: ByteArray): Boolean = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte()

    private fun fromPicture(name: String, bytes: ByteArray): Unwrapped {
        val text = readQr(bytes) ?: return Unwrapped.Problem("There is no QR code that can be read in this picture. Take the picture straight on, close enough that the code fills most of it.")
        // A WireGuard config, or a link to a profile: either way it is text from here on.
        val ext = if (text.lines().any { it.trim().equals("[Interface]", ignoreCase = true) }) "conf" else "ovpn"
        // A plain name, so the profile is named after its server.
        return Unwrapped.File("qr.$ext", text.toByteArray(), "From the QR code in $name")
    }

    /** The text in the first QR code in a picture, or null. */
    fun readQr(bytes: ByteArray): String? = runCatching {
        // Large photos are read smaller: a QR code needs far fewer pixels than a camera takes.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        bitmap.recycle()
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    }.getOrNull()

    private fun fromZip(name: String, bytes: ByteArray): Unwrapped {
        val found = mutableListOf<Pair<String, ByteArray>>()
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var count = 0
                while (count++ < MAX_ENTRIES) {
                    val e = zip.nextEntry ?: break
                    val entry = e.name.substringAfterLast('/')
                    if (e.isDirectory || entry.startsWith(".") || entry.startsWith("__MACOSX")) continue
                    if (!entry.endsWith(".conf", true) && !entry.endsWith(".ovpn", true)) continue
                    // Not readNBytes: Android has it only from 13, and before that every .zip failed here.
                    val data = ServerStore.readLimited(zip)
                    if (data.size <= MAX_ENTRY) found += entry to data
                }
            }
        }.onFailure { return Unwrapped.Problem("This .zip file can’t be opened. Send the profile inside it on its own.") }
        val first = found.firstOrNull() ?: return Unwrapped.Problem("There is no .ovpn or .conf profile in this .zip file.")
        val note = if (found.size > 1) "The .zip holds ${found.size} profiles; this is the first, ${first.first}. Send the others one at a time." else "From $name"
        return Unwrapped.File(first.first, first.second, note)
    }
}

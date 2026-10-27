package dev.shieldvpn.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pairing a phone: Settings › Phone access shows a QR code with a one-time secret in the link's
 * fragment, which the browser never sends. The phone seals its name with keys made from that
 * secret; the TV asks "Pair Pixel 8?", and on Pair hands it a device key, sealed the same way.
 * A secret works once, for one phone, for five minutes.
 */
object Pairing {
    private const val VALID_MS = 5 * 60_000L

    class Offer(val id: String, val secret: ByteArray, val until: Long) {
        val keys = Seal.Keys.of(secret, "pair")
        val window = Seal.Window(0, 0)

        /** The phone that sent the first sealed request: only it can finish pairing. */
        @Volatile var claim: String? = null
        @Volatile var name: String? = null
        @Volatile var asked = false
        @Volatile var result: Result? = null

        val expired: Boolean get() = System.currentTimeMillis() > until && result == null
    }

    sealed interface Result {
        class Paired(val device: Device, val key: ByteArray) : Result
        data object Refused : Result
    }

    private val _offer = MutableStateFlow<Offer?>(null)

    /** The code the TV shows now, while its pairing sheet is open. */
    val offer: StateFlow<Offer?> = _offer.asStateFlow()

    /** A new code; the one before it stops working. */
    fun show(): Offer = Offer("p_" + Seal.b64url(Seal.randomBytes(6)), Seal.randomBytes(32), System.currentTimeMillis() + VALID_MS).also { _offer.value = it }

    /** The pairing sheet closed. */
    fun close() {
        _offer.value = null
    }

    fun current(id: String): Offer? = _offer.value?.takeIf { it.id == id }

    /** What the phone scans: the panel, with the secret where the browser keeps it to itself. */
    fun link(address: String, o: Offer) = "http://$address/panel/#pair=${o.id}.${Seal.b64url(o.secret)}"

    private val _result = MutableStateFlow<Pair<String, Result>?>(null)

    /** How the last pairing ended, by offer id, for the sheet that showed it. */
    val result: StateFlow<Pair<String, Result>?> = _result.asStateFlow()

    fun finished(o: Offer, r: Result) {
        o.result = r
        _result.value = o.id to r
    }
}

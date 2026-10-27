package dev.shieldvpn.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shieldvpn.phone.PhoneSession
import kotlinx.coroutines.launch

/** One field of a form sheet. An [optional] field may stay empty, and Done passes it by. */
class FormField(
    val id: String,
    val label: String,
    val initial: String = "",
    val secret: Boolean = false,
    val mono: Boolean = false,
    val max: Int = 256,
    val optional: Boolean = false,
)

/** A switch beside the buttons, such as Remember on this device. */
class FormToggle(val label: String, val sub: String, val initial: Boolean)

/** A second action beside Cancel, such as Forget or Use the file’s server; with [confirm], the red bar asks first. */
class FormExtra(val label: String, val danger: Boolean = false, val confirm: String? = null, val confirmLabel: String = label, val run: () -> FormResult)

sealed interface FormResult {
    data object Done : FormResult

    /** What is wrong, by field: shown under each, with the sheet left open and the text kept. */
    data class Problems(val errors: Map<String, String>) : FormResult {
        constructor(field: String, text: String) : this(mapOf(field to text))
    }

    /** Opens [next] in place of this sheet. */
    class Open(val next: Overlay) : FormResult
}

/**
 * What a form sheet asks for and what its main button does. [phone] is the screen's phone
 * session whose code shows beside the keyboard, and [phoneSection] the part of its page this
 * sheet edits: the page points at it, and saving it on the phone closes the sheet.
 */
class FormSpec(
    val eyebrow: String,
    val title: String,
    val sub: String,
    val fields: List<FormField>,
    val primary: String,
    val primaryIcon: Ic = Ic.Check,
    val busyLabel: String? = null,
    val phone: String? = null,
    val phoneSection: String? = null,
    val toggle: FormToggle? = null,
    val extra: FormExtra? = null,
    /** What happens to this sheet when the phone saves what it edits: it closes, or opens what comes next. */
    val phoneSaved: () -> FormResult = { FormResult.Done },
    val cancelled: () -> Unit = {},
    val submit: suspend (values: Map<String, String>, toggle: Boolean) -> FormResult,
)

/**
 * The one sheet for typing: one to three fields, the keyboard, the buttons, and the phone's code
 * on the right. Done on the keyboard moves to the next empty field, then to the main button.
 */
@Composable
fun FormSheet(nav: Nav, req: Requesters, spec: FormSpec, close: () -> Unit, session: PhoneSession? = phoneSession(spec.phone)) {
    val values = remember(spec) { mutableStateMapOf<String, String>().apply { spec.fields.forEach { put(it.id, it.initial) } } }
    var field by remember(spec) { mutableStateOf(spec.fields.firstOrNull { it.initial.isEmpty() && !it.optional }?.id ?: spec.fields.first().id) }
    val errors = remember(spec) { mutableStateMapOf<String, String>() }
    var show by remember(spec) { mutableStateOf(false) }
    var toggle by remember(spec) { mutableStateOf(spec.toggle?.initial ?: false) }
    var busy by remember(spec) { mutableStateOf(false) }
    var warned by remember(spec) { mutableStateOf(false) }
    var confirming by remember(spec) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun type(transform: (String) -> String) {
        val f = spec.fields.firstOrNull { it.id == field } ?: return
        values[f.id] = transform(values[f.id].orEmpty()).take(f.max)
        errors.remove(f.id)
        warned = false
    }

    val finish: (FormResult) -> Unit = { r ->
        when (r) {
            FormResult.Done -> close()
            is FormResult.Open -> nav.overlay = r.next
            is FormResult.Problems -> {
                errors.clear()
                // A problem with the whole task shows under the first field.
                r.errors.forEach { (k, v) -> errors[if (spec.fields.any { f -> f.id == k }) k else spec.fields.first().id] = v }
                errors.keys.firstOrNull()?.let { field = it }
            }
        }
    }
    val submit: () -> Unit = {
        if (!busy) {
            busy = true
            scope.launch {
                // A store that fails says so under the field instead of ending the app.
                finish(runCatching { spec.submit(values.toMap(), toggle) }.getOrElse { FormResult.Problems("", "That didn’t work: ${it.message ?: "an error"}") })
                busy = false
            }
        }
    }
    // Done, on screen or a keyboard's Enter: on to the next empty field, else to Save. True when it moved.
    val next: () -> Boolean = {
        val i = spec.fields.indexOfFirst { it.id == field }
        val rest = spec.fields.drop(i + 1) + spec.fields.take(i)
        val empty = rest.firstOrNull { values[it.id].isNullOrEmpty() && !it.optional }
        if (empty != null) {
            field = empty.id
            true
        } else {
            req["ov-go"].takeFocus()
        }
    }
    TypeInto(nav, { type { it.dropLast(1) } }, onEnter = next) { ch -> type { it + ch } }

    // What is typed on the phone shows here as it is typed; saving it there closes this sheet.
    DisposableEffect(session, spec) {
        val stopTyping = session?.listenTyping { f, v ->
            spec.fields.firstOrNull { it.id == f }?.let { ff ->
                values[f] = v.take(ff.max)
                errors.remove(f)
            }
        }
        val stopSaved = session?.listenSaved { section -> if (section == spec.phoneSection) finish(spec.phoneSaved()) }
        // A task the phone runs on this sheet's part shows here too: busy while it runs, then its problems.
        val stopActions = session?.listenActions { section, reply ->
            if (section != spec.phoneSection) return@listenActions
            if (reply == null) {
                busy = true
            } else {
                busy = false
                reply.errors.forEach { (k, v) -> errors[if (spec.fields.any { f -> f.id == k }) k else spec.fields.first().id] = v }
                if (reply.status?.kind == dev.shieldvpn.phone.PhoneStatus.Kind.Error && reply.errors.isEmpty()) errors[spec.fields.first().id] = reply.status.text
            }
        }
        session?.point(spec.phoneSection)
        session?.clearOldStatus()
        onDispose {
            stopTyping?.invoke()
            stopSaved?.invoke()
            stopActions?.invoke()
            if (session?.focus == spec.phoneSection) session?.point(null)
        }
    }
    // Back with unsaved text first moves to the main button and says so; Back again closes.
    DisposableEffect(spec) {
        val mine: () -> Boolean = {
            val changed = spec.fields.any { values[it.id] != it.initial }
            if (changed && !warned && !confirming) {
                warned = true
                req["ov-go"].tryFocus()
                true
            } else {
                false
            }
        }
        nav.sheetBack = mine
        // Only this sheet's own: the next sheet may have set its handler already.
        onDispose { if (nav.sheetBack === mine) nav.sheetBack = null }
    }

    Sheet(if (spec.phone != null) 1100f else 820f) {
        Row(horizontalArrangement = Arrangement.spacedBy(34.dp)) {
            // An error line or the Back note takes room the buttons need on the tallest sheets.
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (errors.isEmpty() && !warned) 14.dp else 8.dp)) {
                PanelHeading(spec.eyebrow, spec.title, spec.sub, titleSize = 32f)
                @Composable
                fun box(f: FormField, modifier: Modifier) {
                    InputBox(
                        f.label, values[f.id].orEmpty(), field == f.id, { field = f.id }, modifier,
                        secret = f.secret, showSecret = show, mono = f.mono, error = errors[f.id],
                    )
                }
                when (spec.fields.size) {
                    1 -> box(spec.fields[0], Modifier)
                    2 -> Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        spec.fields.forEach { box(it, Modifier.weight(1f)) }
                    }
                    else -> {
                        box(spec.fields[0], Modifier)
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            spec.fields.drop(1).forEach { box(it, Modifier.weight(1f)) }
                        }
                    }
                }
                Keyboard(onType = { c -> type { it + c } }, onDelete = { type { it.dropLast(1) } }, onDone = { next() }, entry = req["ov-key"])
                val extra = spec.extra
                if (confirming && extra != null) {
                    ConfirmBar(
                        extra.confirm.orEmpty(), extra.confirmLabel, req["ov-extra-no"],
                        onConfirm = {
                            confirming = false
                            finish(extra.run())
                        },
                        onKeep = {
                            confirming = false
                            nav.refocus(req["ov-extra"])
                        },
                    )
                } else {
                    Row(Modifier.enterAt { req["ov-go"] }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TvButton(if (busy) spec.busyLabel ?: spec.primary else spec.primary, submit, icon = spec.primaryIcon, focusRequester = req["ov-go"])
                        TvButton("Cancel", {
                            spec.cancelled()
                            close()
                        }, glass = true)
                        if (spec.fields.any { it.secret }) {
                            TvButton(if (show) "Hide" else "Show", { show = !show }, icon = Ic.Eye, small = true, glass = true)
                        }
                        if (extra != null) {
                            TvButton(extra.label, { if (extra.confirm != null) confirming = true else finish(extra.run()) }, small = true, glass = true, danger = extra.danger, focusRequester = req["ov-extra"])
                        }
                        spec.toggle?.let { t ->
                            Spacer(Modifier.weight(1f))
                            ListRow(t.label, { toggle = !toggle }, subtitle = t.sub, modifier = Modifier.width(250.dp), trailing = { TvSwitch(toggle, it) })
                        }
                    }
                }
                if (warned) {
                    Text("Back again closes without saving.", style = TvType.ui(15f, color = Tv.soft(0.62f)), modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (spec.phone != null) PhoneBlock(session, PhoneBlockSize.Side, Modifier.width(300.dp).padding(top = 4.dp))
        }
    }
}

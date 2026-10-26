package dev.shieldvpn.phone

import org.json.JSONArray
import org.json.JSONObject

/*
 * What a phone page shows, described by the TV screen that owns it. The page draws it with the
 * same look everywhere, sends what is typed by field name, and draws it again when it changes.
 * Everything here is a value, so a screen can rebuild its form on every change and the session
 * only wakes the phone when something is different.
 */

/** A phone page: [place] names the TV screen, for "The TV left Import". */
data class PhoneForm(
    val place: String,
    val title: String,
    val lede: String,
    val sections: List<PhoneSection>,
)

/**
 * A card on the page. [showWhen] shows it only while that field has that value on the phone
 * (a tab, a switch); [folded] puts it behind its title until tapped.
 */
data class PhoneSection(
    val id: String,
    val title: String? = null,
    val note: String? = null,
    val fields: List<PhoneField> = emptyList(),
    val actions: List<PhoneAction> = emptyList(),
    val showWhen: Pair<String, String>? = null,
    val folded: Boolean = false,
)

sealed interface PhoneField {
    val id: String

    /**
     * One line of text. [input] picks the phone's keyboard: text, url, numeric or email. A
     * [secret] is never sent back to the phone; [mirror] sends it to the TV as it is typed.
     */
    data class Text(
        override val id: String,
        val label: String,
        val value: String = "",
        val input: String = "text",
        val max: Int = 256,
        val placeholder: String = "",
        val help: String? = null,
        val required: Boolean = false,
        val secret: Boolean = false,
        val mirror: Boolean = false,
    ) : PhoneField

    /** Several values, one per line: DNS servers, search domains. */
    /** One entry a line; with [mirror], what is typed shows in the TV's open sheet, one line after another. */
    data class Lines(override val id: String, val label: String, val values: List<String>, val help: String? = null, val placeholder: String = "", val mirror: Boolean = false) : PhoneField

    data class Switch(override val id: String, val label: String, val sub: String? = null, val value: Boolean) : PhoneField

    /** Tabs, such as File | Paste | Link. */
    data class Choice(override val id: String, val label: String?, val options: List<Pair<String, String>>, val value: String) : PhoneField

    /** One of a list, such as the profile a certificate is for. */
    data class Select(override val id: String, val label: String, val options: List<PhoneOption>, val value: String?) : PhoneField

    /** A file to send. [file] is what the TV holds already, if anything. */
    data class Upload(override val id: String, val label: String, val sub: String, val accept: String, val file: Staged? = null) : PhoneField

    /** Text pasted in place of a file; the phone sends it to the TV, which checks it like one. */
    data class Paste(override val id: String, val label: String, val placeholder: String = "", val file: Staged? = null) : PhoneField

    /** A file to download, fetched by [id] when the phone asks. */
    data class Download(override val id: String, val name: String, val size: Int, val note: String? = null, val label: String = "Download") : PhoneField

    /** Read-only facts or a sentence, such as the review of a file. */
    data class Info(override val id: String, val rows: List<Pair<String, String>> = emptyList(), val text: String? = null, val tone: String? = null) : PhoneField

    /** Text to copy, such as an automation command. */
    data class Copy(override val id: String, val label: String?, val text: String) : PhoneField
}

/** A choice in a [PhoneField.Select]; picking one with [confirm] asks before its action runs. */
data class PhoneOption(val value: String, val label: String, val sub: String? = null, val confirm: String? = null)

/** A file the TV holds for a form: its name, size and what it is. */
data class Staged(val name: String, val size: Int, val sub: String? = null)

/**
 * A button. It stays disabled until the fields in [needs] are filled; with [confirm], the phone
 * asks first, offering [confirmLabel] and Keep.
 */
data class PhoneAction(
    val id: String,
    val label: String,
    val primary: Boolean = false,
    val danger: Boolean = false,
    val confirm: String? = null,
    val confirmLabel: String? = null,
    val needs: List<String> = emptyList(),
)

/** What the phone shows after something happened, under [section] when it is about one. */
data class PhoneStatus(val kind: Kind, val text: String, val section: String? = null) {
    enum class Kind { Waiting, Busy, Done, Error }

    companion object {
        fun done(text: String) = PhoneStatus(Kind.Done, text)
        fun busy(text: String) = PhoneStatus(Kind.Busy, text)
        fun waiting(text: String) = PhoneStatus(Kind.Waiting, text)
        fun error(text: String) = PhoneStatus(Kind.Error, text)
    }
}

/**
 * A screen's answer to the phone: a status, problems by field (shown under the field, with what
 * was typed kept), fields to empty, and how long to keep answering after the screen closes.
 */
class PhoneReply(
    val status: PhoneStatus? = null,
    val errors: Map<String, String> = emptyMap(),
    val clear: List<String> = emptyList(),
    val holdMs: Long = 0,
    /** A file the phone downloads straight away, by id and name: the backup after its passphrase is chosen. */
    val download: Pair<String, String>? = null,
) {
    val ok: Boolean get() = errors.isEmpty() && status?.kind != PhoneStatus.Kind.Error

    internal fun toJson(rev: Int, section: String? = null): JSONObject = JSONObject()
        .put("ok", ok)
        .put("rev", rev)
        .put("status", status?.let { it.copy(section = it.section ?: section).toJson() } ?: JSONObject.NULL)
        .put("errors", JSONObject(errors))
        .put("clear", JSONArray(clear))
        .put("download", download?.let { JSONObject().put("id", it.first).put("name", it.second) } ?: JSONObject.NULL)

    companion object {
        fun done(text: String, clear: List<String> = emptyList()) = PhoneReply(PhoneStatus.done(text), clear = clear)

        /** A problem with [field], or with the whole form when it is null. */
        fun error(field: String?, text: String) =
            if (field != null) PhoneReply(errors = mapOf(field to text)) else PhoneReply(PhoneStatus.error(text))
    }
}

internal fun PhoneStatus.toJson(): JSONObject = JSONObject()
    .put("kind", kind.name.lowercase())
    .put("text", text)
    .put("section", section ?: JSONObject.NULL)

internal fun PhoneForm.toJson(): JSONObject = JSONObject()
    .put("place", place)
    .put("title", title)
    .put("lede", lede)
    .put("sections", JSONArray(sections.map { it.toJson() }))

private fun PhoneSection.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("title", title ?: JSONObject.NULL)
    .put("note", note ?: JSONObject.NULL)
    .put("folded", folded)
    .put("showWhen", showWhen?.let { JSONArray(listOf(it.first, it.second)) } ?: JSONObject.NULL)
    .put("fields", JSONArray(fields.map { it.toJson() }))
    .put(
        "actions",
        JSONArray(
            actions.map { a ->
                JSONObject()
                    .put("id", a.id).put("label", a.label).put("primary", a.primary).put("danger", a.danger)
                    .put("confirm", a.confirm ?: JSONObject.NULL).put("confirmLabel", a.confirmLabel ?: JSONObject.NULL)
                    .put("needs", JSONArray(a.needs))
            },
        ),
    )

private fun PhoneField.toJson(): JSONObject {
    val o = JSONObject().put("id", id)
    return when (this) {
        is PhoneField.Text -> o.put("type", "text").put("label", label)
            // A secret stays on the TV: the phone only ever sends one.
            .put("value", if (secret) "" else value)
            .put("input", input).put("max", max).put("placeholder", placeholder).put("help", help ?: JSONObject.NULL)
            .put("required", required).put("secret", secret).put("mirror", mirror)
        is PhoneField.Lines -> o.put("type", "lines").put("label", label).put("value", values.joinToString("\n"))
            .put("help", help ?: JSONObject.NULL).put("placeholder", placeholder).put("mirror", mirror)
        is PhoneField.Switch -> o.put("type", "switch").put("label", label).put("sub", sub ?: JSONObject.NULL).put("value", value)
        is PhoneField.Choice -> o.put("type", "choice").put("label", label ?: JSONObject.NULL).put("value", value)
            .put("options", JSONArray(options.map { (v, l) -> JSONArray(listOf(v, l)) }))
        is PhoneField.Select -> o.put("type", "select").put("label", label).put("value", value ?: JSONObject.NULL)
            .put(
                "options",
                JSONArray(
                    options.map {
                        JSONObject().put("value", it.value).put("label", it.label).put("sub", it.sub ?: JSONObject.NULL).put("confirm", it.confirm ?: JSONObject.NULL)
                    },
                ),
            )
        is PhoneField.Upload -> o.put("type", "upload").put("label", label).put("sub", sub).put("accept", accept)
            .put("file", file?.let { JSONObject().put("name", it.name).put("size", it.size).put("sub", it.sub ?: JSONObject.NULL) } ?: JSONObject.NULL)
        is PhoneField.Paste -> o.put("type", "paste").put("label", label).put("placeholder", placeholder)
            .put("file", file?.let { JSONObject().put("name", it.name).put("size", it.size).put("sub", it.sub ?: JSONObject.NULL) } ?: JSONObject.NULL)
        is PhoneField.Download -> o.put("type", "download").put("name", name).put("size", size).put("note", note ?: JSONObject.NULL).put("label", label)
        is PhoneField.Info -> o.put("type", "info").put("text", text ?: JSONObject.NULL).put("tone", tone ?: JSONObject.NULL)
            .put("rows", JSONArray(rows.map { (k, v) -> JSONArray(listOf(k, v)) }))
        is PhoneField.Copy -> o.put("type", "copy").put("label", label ?: JSONObject.NULL).put("text", text)
    }
}

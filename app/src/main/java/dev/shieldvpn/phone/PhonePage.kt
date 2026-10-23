package dev.shieldvpn.phone

import dev.shieldvpn.APP_NAME

/** The page a phone opens from the TV's QR code. One file, no outside resources, in Tunnel's look. */
object PhonePage {

    fun html(page: PhoneBridge.Page, code: String): String {
        val body = when (page) {
            is PhoneBridge.Page.Upload -> """
                <label class="pick"><input id="file" type="file" accept="${esc(page.accept)}"><span id="pickLabel">Choose a file</span></label>
                ${if (page.paste) """<p class="or">or paste the text</p><textarea id="text" rows="8" placeholder="client&#10;dev tun&#10;remote …" spellcheck="false" autocapitalize="off"></textarea>""" else ""}
                <button id="go" class="btn">Send to the TV</button>"""
            is PhoneBridge.Page.Type -> """
                <label class="lbl" for="text">${esc(page.label)}</label>
                <input id="text" class="field" type="${if (page.secret) "password" else "text"}" autocapitalize="off" autocorrect="off" spellcheck="false" autocomplete="off">
                <button id="go" class="btn">Send to the TV</button>"""
            is PhoneBridge.Page.Download -> """
                <a id="dl" class="btn" href="#">Download ${esc(page.fileName)}</a>"""
        }
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(APP_NAME)}</title>
<style>
:root{color-scheme:dark}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;background:#05070A;color:#EDF1F5;font:16px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif;padding:28px 20px 40px}
.brand{display:flex;align-items:center;gap:10px;font-weight:700;font-size:19px;margin-bottom:28px}
.mark{width:34px;height:34px;border-radius:11px;display:grid;place-items:center;background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.12)}
h1{margin:0;font-size:28px;line-height:1.15;letter-spacing:-.02em}
.lede{margin:8px 0 24px;color:#A5AFBB}
.card{max-width:520px;margin:0 auto}
.pick{display:block;padding:18px;border-radius:16px;border:1px dashed rgba(255,255,255,.25);text-align:center;font-weight:700}
.pick input{display:none}
.or{margin:14px 0 8px;color:#7F8B98;font-size:14px;text-align:center}
textarea,.field{width:100%;padding:14px 16px;border-radius:14px;border:1px solid rgba(255,255,255,.14);background:rgba(255,255,255,.06);color:#EDF1F5;font:15px/1.5 ui-monospace,Menlo,monospace}
.field{font:18px system-ui,sans-serif}
.lbl{display:block;margin-bottom:8px;font-weight:700;color:#A5AFBB;font-size:14px}
.code{display:flex;gap:10px;margin-bottom:20px}
.code input{flex:1}
.btn{display:block;width:100%;margin-top:18px;padding:16px;border:0;border-radius:999px;background:#F4F7FA;color:#071018;font:800 17px system-ui,sans-serif;text-align:center;text-decoration:none}
.msg{margin-top:16px;padding:12px 16px;border-radius:14px;font-weight:600;display:none}
.msg.ok{display:block;background:rgba(75,227,161,.14);color:#4BE3A1}
.msg.bad{display:block;background:rgba(255,138,122,.14);color:#FF8A7A}
</style></head><body><div class="card">
<div class="brand"><span class="mark"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#EDF1F5" stroke-width="2" stroke-linecap="round"><path d="M4 20v-9a8 8 0 0 1 16 0v9"/><path d="M8.5 20v-8a3.5 3.5 0 0 1 7 0v8"/></svg></span>${esc(APP_NAME)}</div>
<h1>${esc(page.title)}</h1><p class="lede">${esc(page.lede)}</p>
<div class="code" id="codeRow"${if (code.isNotEmpty()) " hidden" else ""}><input id="code" class="field" inputmode="numeric" maxlength="7" placeholder="Code on the TV"></div>
$body
<div id="msg" class="msg"></div>
</div>
<script>
const given = ${'"'}${code.filter { it.isDigit() }}${'"'};
const code = () => given || document.getElementById('code').value.replace(/\D/g, '');
const msg = (t, ok) => { const m = document.getElementById('msg'); m.textContent = t; m.className = 'msg ' + (ok ? 'ok' : 'bad'); };
const file = document.getElementById('file');
if (file) file.onchange = () => { document.getElementById('pickLabel').textContent = file.files[0] ? file.files[0].name : 'Choose a file'; };
const go = document.getElementById('go');
if (go) go.onclick = async () => {
  const f = file && file.files[0];
  const t = document.getElementById('text');
  const body = f || (t && t.value);
  if (!body) return msg('Nothing to send yet.', false);
  go.disabled = true;
  try {
    const r = await fetch('/send', { method: 'POST', headers: { 'X-Code': code(), 'X-Name': encodeURIComponent(f ? f.name : 'pasted.ovpn') }, body });
    msg(await r.text(), r.ok);
  } catch (e) { msg('The TV did not answer. Is the screen still open?', false); }
  go.disabled = false;
};
const dl = document.getElementById('dl');
if (dl) dl.onclick = (e) => { e.preventDefault(); location.href = '/file?c=' + code(); };
</script></body></html>"""
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}

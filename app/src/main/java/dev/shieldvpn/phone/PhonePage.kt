package dev.shieldvpn.phone

import dev.shieldvpn.APP_NAME

/**
 * The page a phone opens from the TV's QR code: one file, no outside resources, in Tunnel's look.
 * Opened from the QR code, the link carries the code, and the page shows it for the person to
 * check against the TV; typed in by hand, it asks for it.
 */
object PhonePage {

    fun html(page: PhoneBridge.Page, code: String): String {
        val given = code.filter { it.isDigit() }.take(6)
        val body = when (page) {
            is PhoneBridge.Page.Upload -> upload(page.accept, page.paste, page.login)
            is PhoneBridge.Page.Type -> typed(page.label, page.secret)
            is PhoneBridge.Page.Download -> download(page.fileName, page.body.size)
        }
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#05070A">
<title>${esc(APP_NAME)}</title>
<style>$CSS</style></head>
<body>
$WEB
<main>
  <div class="brand"><span class="mark">$MARK</span>${esc(APP_NAME)}</div>
  <h1>${esc(page.title)}</h1>
  <p class="lede">${esc(page.lede)}</p>
  ${codeCard(given)}
  <section class="card">$body
    <div id="status" class="status" role="status"></div>
  </section>
  <p class="foot">It stays on your home network. This page works while the TV shows its code.</p>
</main>
<script>
const given = "$given";
$SCRIPT
</script>
</body></html>"""
    }

    /** The code from the QR link, to check against the TV; or a box to type it into. */
    private fun codeCard(code: String): String = if (code.length == 6) {
        val digit = { d: Char -> """<span class="digit">$d</span>""" }
        """<section class="card"><div class="eyebrow">Code on the TV</div>
    <div class="digits">${code.take(3).map(digit).joinToString("")}<span class="gap"></span>${code.drop(3).map(digit).joinToString("")}</div>
    <p class="check">$CHECK Check that the TV shows this code.</p></section>"""
    } else {
        """<section class="card"><label class="eyebrow" for="code">Code on the TV</label>
    <input id="code" class="codein" inputmode="numeric" autocomplete="one-time-code" maxlength="7" placeholder="000 000"></section>"""
    }

    /**
     * Pick a file or paste it. With [login], a profile that asks for a username and password
     * (checked here, as the TV checks it) gets a second step for them before it is sent.
     */
    private fun upload(accept: String, paste: Boolean, login: Boolean): String {
        val what = "The ${kinds(accept)}"
        val modes = if (paste) {
            """<div class="seg" role="group"><button id="modeFile" type="button" aria-pressed="true">File</button><button id="modePaste" type="button" aria-pressed="false">Paste text</button></div>"""
        } else {
            ""
        }
        val area = if (paste) {
            """<textarea id="text" hidden placeholder="client&#10;dev tun&#10;remote vpn.example.net 1194" spellcheck="false" autocapitalize="off" autocorrect="off"></textarea>"""
        } else {
            ""
        }
        val signIn = if (login) {
            """
    <div id="step2" hidden>
      <div class="file chosen"><span class="icon">${doc("#4BE3A1")}</span><span class="txt"><b id="step2Name"></b><small id="step2Sub"></small></span><button id="back" class="link" type="button">Change</button></div>
      <label class="lbl" for="user">Username</label>
      <div class="fieldrow"><input id="user" class="field" type="text" autocapitalize="off" autocorrect="off" spellcheck="false" autocomplete="username"></div>
      <label class="lbl" for="pass">Password</label>
      <div class="fieldrow"><input id="pass" class="field" type="password" autocapitalize="off" autocorrect="off" spellcheck="false" autocomplete="current-password"><button id="eye2" class="eye" type="button" aria-label="Show">$EYE</button></div>
      <p class="hint">Saved with the profile on the TV, encrypted. Leave them empty to type them on the TV when you connect.</p>
    </div>"""
        } else {
            ""
        }
        return """<div id="step1">$modes
    <label class="pick" id="pick"><input id="file" type="file" accept="${esc(accept)}"><span class="icon">${doc("rgba(235,242,248,.8)")}</span>
      <span class="txt"><b id="pickName">Choose a file</b><small id="pickSub" data-sub="${esc(what)}">${esc(what)}</small></span><span class="change" id="pickChange" hidden>Change</span></label>
    $area</div>$signIn
    <button id="go" class="btn" type="button" disabled><span id="goLabel">Send to the TV</span>$ARROW</button>"""
    }

    private fun typed(label: String, secret: Boolean): String {
        val eye = if (secret) """<button id="eye" class="eye" type="button" aria-label="Show">$EYE</button>""" else ""
        return """<label class="lbl" for="text">${esc(label)}</label>
    <div class="fieldrow"><input id="text" class="field" type="${if (secret) "password" else "text"}" autocapitalize="off" autocorrect="off" spellcheck="false" autocomplete="off">$eye</div>
    <button id="go" class="btn" type="button" disabled>Send to the TV$ARROW</button>"""
    }

    private fun download(name: String, size: Int): String =
        """<div class="file"><span class="icon">${doc("#4BE3A1")}</span><span><b>${esc(name)}</b><small>${size(size)}</small></span></div>
    <a id="dl" class="btn" href="#">Download$DOWN</a>"""

    /** "the .ovpn, .conf or backup file", from an input's accept list. */
    private fun kinds(accept: String): String {
        val exts = accept.split(',').map { it.trim() }.filter { it.startsWith('.') }
            .map { if (it.endsWith("backup")) "backup" else it }.distinct()
        return when (exts.size) {
            0 -> "file"
            1 -> "${exts[0]} file"
            else -> exts.dropLast(1).joinToString(", ") + " or " + exts.last() + " file"
        }
    }

    private fun size(bytes: Int): String = when {
        bytes < 1024 -> "$bytes bytes"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
        else -> "%.1f MB".format(bytes / 1048576f)
    }

    private fun doc(stroke: String) =
        """<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="$stroke" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/></svg>"""

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private const val MARK =
        """<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#F3F6F8" stroke-width="2" stroke-linecap="round"><path d="M4 20v-9a8 8 0 0 1 16 0v9"/><path d="M8.5 20v-8a3.5 3.5 0 0 1 7 0v8"/></svg>"""
    private const val CHECK =
        """<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#4BE3A1" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M8.5 12.2l2.4 2.4 4.6-4.8"/></svg>"""
    private const val ARROW =
        """<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#071018" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14"/><path d="M13 6l6 6-6 6"/></svg>"""
    private const val DOWN =
        """<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#071018" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4v12"/><path d="M6 11l6 6 6-6"/><path d="M5 20h14"/></svg>"""
    private const val EYE =
        """<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="rgba(235,242,248,.6)" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/></svg>"""

    /** A faint corner of the TV's network background. */
    private const val WEB = """<svg class="web" viewBox="0 0 400 260" preserveAspectRatio="xMidYMin slice" aria-hidden="true">
  <g stroke="rgba(75,227,161,.35)" stroke-width=".8" fill="none"><path d="M232 46L284 22L338 58L392 34M284 22L300 92L338 58M300 92L360 124L392 96M232 46L250 118L300 92M250 118L206 150M360 124L338 176"/></g>
  <g fill="rgba(160,245,210,.7)"><circle cx="232" cy="46" r="1.8"/><circle cx="284" cy="22" r="1.8"/><circle cx="338" cy="58" r="2.2"/><circle cx="392" cy="34" r="1.6"/><circle cx="300" cy="92" r="1.8"/><circle cx="360" cy="124" r="1.8"/><circle cx="392" cy="96" r="1.6"/><circle cx="250" cy="118" r="1.6"/><circle cx="206" cy="150" r="1.4"/><circle cx="338" cy="176" r="1.4"/></g>
</svg>"""

    private const val CSS = """
:root{color-scheme:dark;--page:#05070A;--ink:#F3F6F8;--soft:rgba(235,242,248,.64);--faint:rgba(235,242,248,.42);--mint:#4BE3A1;--red:#FF8A7A;--glass:rgba(16,23,33,.72);--edge:rgba(255,255,255,.10);--well:rgba(255,255,255,.055)}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
[hidden]{display:none!important}
html{background:var(--page)}
body{margin:0;min-height:100dvh;color:var(--ink);font:16px/1.45 -apple-system,BlinkMacSystemFont,"SF Pro Text",Roboto,"Segoe UI",sans-serif;-webkit-font-smoothing:antialiased;background:radial-gradient(120% 60% at 85% -10%,rgba(75,227,161,.16),transparent 60%),radial-gradient(90% 50% at -20% 110%,rgba(75,227,161,.07),transparent 60%),var(--page)}
.web{position:fixed;inset:0 0 auto 0;height:260px;pointer-events:none;opacity:.5}
main{position:relative;max-width:440px;margin:0 auto;padding:calc(26px + env(safe-area-inset-top)) 20px calc(28px + env(safe-area-inset-bottom))}
.brand{display:flex;align-items:center;gap:11px;font-weight:700;font-size:17px;letter-spacing:-.01em}
.mark{width:36px;height:36px;border-radius:11px;display:grid;place-items:center;background:var(--glass);box-shadow:inset 0 0 0 1px var(--edge)}
h1{margin:34px 0 0;font-size:31px;line-height:1.08;letter-spacing:-.025em;font-weight:800}
.lede{margin:10px 0 0;color:var(--soft);font-size:16px}
.card{margin-top:22px;padding:18px;border-radius:24px;background:var(--glass);box-shadow:inset 0 0 0 1px var(--edge),0 24px 60px -30px rgba(0,0,0,.8);backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px)}
.eyebrow{display:block;font-size:12px;font-weight:700;letter-spacing:.14em;text-transform:uppercase;color:var(--faint)}
.digits{display:flex;justify-content:center;gap:8px;margin:14px 0 4px}
.digits .gap{width:10px}
.digit{width:44px;height:58px;border-radius:13px;display:grid;place-items:center;background:var(--well);box-shadow:inset 0 0 0 1px var(--edge);font:700 28px/1 ui-rounded,-apple-system,Roboto,sans-serif;font-variant-numeric:tabular-nums}
.check{display:flex;align-items:center;justify-content:center;gap:8px;margin:12px 0 0;color:var(--soft);font-size:14.5px;text-align:center}
.check svg{flex:none}
.codein{display:block;width:100%;margin-top:12px;padding:14px 0;border:0;border-radius:16px;background:var(--well);box-shadow:inset 0 0 0 1px var(--edge);color:var(--ink);text-align:center;font:700 30px/1 ui-rounded,-apple-system,Roboto,sans-serif;letter-spacing:.32em;font-variant-numeric:tabular-nums;outline:none}
.codein:focus{box-shadow:inset 0 0 0 2px var(--mint)}
.codein::placeholder{color:rgba(235,242,248,.18)}
.seg{display:grid;grid-template-columns:1fr 1fr;padding:4px;border-radius:14px;background:var(--well);margin-bottom:14px}
.seg button{padding:10px;border:0;border-radius:11px;background:none;color:var(--soft);font-family:inherit;font-size:15px;font-weight:600;line-height:1;cursor:pointer}
.seg button[aria-pressed=true]{background:rgba(255,255,255,.12);color:var(--ink)}
.pick{display:flex;align-items:center;gap:14px;padding:16px;border-radius:18px;background:var(--well);box-shadow:inset 0 0 0 1px var(--edge);cursor:pointer}
.pick input{display:none}
.icon{width:46px;height:46px;border-radius:14px;display:grid;place-items:center;flex:none;background:rgba(255,255,255,.07)}
.pick.chosen .icon{background:rgba(75,227,161,.14)}
.pick b{display:block;font-size:16px;font-weight:650;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.pick small{display:block;color:var(--faint);font-size:13.5px;margin-top:2px}
.pick .txt{min-width:0;flex:1}
.pick .change{color:var(--mint);font-weight:650;font-size:14.5px}
textarea,.field{width:100%;border:0;border-radius:16px;background:var(--well);box-shadow:inset 0 0 0 1px var(--edge);color:var(--ink);outline:none}
textarea{display:block;padding:14px 16px;min-height:190px;resize:vertical;font:13.5px/1.55 ui-monospace,SFMono-Regular,Menlo,monospace}
textarea:focus,.field:focus{box-shadow:inset 0 0 0 2px var(--mint)}
textarea::placeholder,.field::placeholder{color:rgba(235,242,248,.28)}
.lbl{display:block;margin:0 0 10px;font-size:14px;font-weight:650;color:var(--soft)}
.fieldrow{position:relative}
.field{padding:16px 54px 16px 16px;font-family:inherit;font-size:19px;font-weight:500;line-height:1.2}
.eye{position:absolute;right:8px;top:50%;transform:translateY(-50%);width:40px;height:40px;border:0;border-radius:12px;background:none;display:grid;place-items:center;cursor:pointer}
.file{display:flex;align-items:center;gap:14px}
.file b{display:block;font-size:16px;font-weight:650;word-break:break-all}
.file small{color:var(--faint);font-size:13.5px}
.file .txt{min-width:0;flex:1}
.chosen{margin-bottom:20px}
.link{padding:8px 4px;border:0;background:none;color:var(--mint);font-family:inherit;font-size:14.5px;font-weight:650;cursor:pointer}
.fieldrow+.lbl{margin-top:16px}
.hint{margin:12px 2px 0;color:var(--faint);font-size:13.5px}
.btn{display:flex;align-items:center;justify-content:center;gap:10px;width:100%;margin-top:16px;padding:17px;border:0;border-radius:999px;background:#F4F7FA;color:#071018;font-family:inherit;font-size:17px;font-weight:700;line-height:1;text-decoration:none;cursor:pointer;transition:transform .12s,opacity .2s}
.btn:active{transform:scale(.98)}
.btn:disabled{opacity:.35}
.status{display:none;align-items:flex-start;gap:10px;margin-top:14px;padding:13px 15px;border-radius:16px;font-size:15px;font-weight:550}
.status.show{display:flex}
.status.ok{background:rgba(75,227,161,.12);color:var(--mint)}
.status.bad{background:rgba(255,138,122,.12);color:var(--red)}
.status.busy{background:var(--well);color:var(--soft)}
.status svg{flex:none;margin-top:1px}
.spin{animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
.foot{margin:22px 4px 0;color:var(--faint);font-size:13px;text-align:center}
"""

    private const val SCRIPT = """
const ${'$'} = (id) => document.getElementById(id);
const code = () => given || (${'$'}('code') ? ${'$'}('code').value.replace(/\D/g, '') : '');
const ICON = {
  ok: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M8.5 12.2l2.4 2.4 4.6-4.8"/></svg>',
  bad: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M12 7.5v5.5"/><path d="M12 16.5h.01"/></svg>',
  busy: '<svg class="spin" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M21 12a9 9 0 1 1-9-9"/></svg>',
};
const status = (kind, text) => {
  const s = ${'$'}('status');
  s.className = 'status show ' + kind;
  s.innerHTML = ICON[kind] + '<span></span>';
  s.lastChild.textContent = text;
};
const size = (n) => n < 1024 ? n + ' bytes' : n < 1048576 ? (n / 1024).toFixed(1) + ' KB' : (n / 1048576).toFixed(1) + ' MB';
const file = ${'$'}('file'), text = ${'$'}('text'), go = ${'$'}('go'), step2 = ${'$'}('step2');
let mode = file ? 'file' : 'text';
// A profile asks for a login when it has an auth-user-pass line and no login inside it, as the TV reads it.
let needsLogin = false;
const asksLogin = (t) => !/<auth-user-pass>[\s\S]*?<\/auth-user-pass>/.test(t) && /^[ \t]*auth-user-pass\b/m.test(t);
const onStep2 = () => !!step2 && !step2.hidden;
const label = () => { if (${'$'}('goLabel')) ${'$'}('goLabel').textContent = needsLogin && !onStep2() ? 'Next' : 'Send to the TV'; };
const ready = () => code().length === 6 && (onStep2() || (mode === 'file' ? !!(file.files && file.files[0]) : !!(text && text.value.trim())));
const update = () => { if (go) go.disabled = !ready(); };
const check = async () => {
  if (!step2) return;
  const f = mode === 'file' && file.files && file.files[0];
  const t = mode === 'file' ? (f && f.size < 524288 ? await f.text() : '') : text.value;
  needsLogin = asksLogin(t);
  label();
};
const codeIn = ${'$'}('code');
if (codeIn) codeIn.oninput = () => {
  const d = codeIn.value.replace(/\D/g, '').slice(0, 6);
  codeIn.value = d.length > 3 ? d.slice(0, 3) + ' ' + d.slice(3) : d;
  update();
};
const showFile = (name, bytes) => {
  ${'$'}('pick').classList.toggle('chosen', !!name);
  ${'$'}('pickName').textContent = name || 'Choose a file';
  ${'$'}('pickSub').textContent = name ? size(bytes) : ${'$'}('pickSub').dataset.sub;
  ${'$'}('pickChange').hidden = !name;
};
if (file) file.onchange = () => { const f = file.files[0]; showFile(f && f.name, f && f.size); update(); check(); };
const setMode = (m) => {
  mode = m;
  ${'$'}('modeFile').setAttribute('aria-pressed', m === 'file');
  ${'$'}('modePaste').setAttribute('aria-pressed', m === 'text');
  ${'$'}('pick').hidden = m !== 'file';
  text.hidden = m !== 'text';
  update();
  check();
};
if (${'$'}('modeFile')) { ${'$'}('modeFile').onclick = () => setMode('file'); ${'$'}('modePaste').onclick = () => { setMode('text'); text.focus(); }; }
if (text) { text.oninput = () => { update(); check(); }; text.onkeydown = (e) => { if (e.key === 'Enter' && text.tagName === 'INPUT' && go && !go.disabled) go.click(); }; }
if (${'$'}('eye')) ${'$'}('eye').onclick = () => { text.type = text.type === 'password' ? 'text' : 'password'; text.focus(); };
const lede = document.querySelector('.lede'), ledeText = lede.textContent;
const showStep = (two) => {
  ${'$'}('step1').hidden = two;
  step2.hidden = !two;
  lede.textContent = two ? 'It asks for a username and password. Type them here, and they go to the TV with it.' : ledeText;
  if (two) {
    const f = mode === 'file' && file.files[0];
    ${'$'}('step2Name').textContent = f ? f.name : 'Pasted profile';
    ${'$'}('step2Sub').textContent = size(f ? f.size : new Blob([text.value]).size);
    ${'$'}('user').focus();
  }
  ${'$'}('status').className = 'status';
  label();
  update();
};
if (step2) {
  ${'$'}('back').onclick = () => showStep(false);
  ${'$'}('eye2').onclick = () => { const p = ${'$'}('pass'); p.type = p.type === 'password' ? 'text' : 'password'; p.focus(); };
  ${'$'}('pass').onkeydown = (e) => { if (e.key === 'Enter' && !go.disabled) go.click(); };
}
if (go) go.onclick = async () => {
  if (needsLogin && step2 && !onStep2()) return showStep(true);
  const f = mode === 'file' && file.files[0];
  const headers = { 'X-Code': code(), 'X-Name': encodeURIComponent(f ? f.name : 'pasted.ovpn') };
  if (onStep2() && ${'$'}('user').value.trim()) {
    headers['X-User'] = encodeURIComponent(${'$'}('user').value.trim());
    headers['X-Pass'] = encodeURIComponent(${'$'}('pass').value);
  }
  go.disabled = true;
  status('busy', 'Sending…');
  try {
    const r = await fetch('/send', { method: 'POST', headers, body: f || text.value });
    status(r.ok ? 'ok' : 'bad', await r.text());
    if (r.ok && step2) ${'$'}('pass').value = '';
  } catch (e) {
    status('bad', 'The TV did not answer. Is its screen still open?');
  }
  update();
};
const dl = ${'$'}('dl');
if (dl) dl.onclick = (e) => {
  e.preventDefault();
  if (code().length !== 6) return status('bad', 'Type the code on the TV first.');
  location.href = '/file?c=' + code();
  status('ok', 'Downloading. Look in your Downloads.');
};
update();
"""
}

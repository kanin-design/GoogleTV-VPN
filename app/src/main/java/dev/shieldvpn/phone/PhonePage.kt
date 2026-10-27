package dev.shieldvpn.phone

import dev.shieldvpn.APP_NAME

/**
 * The page a phone opens from the TV's QR code: one file, no outside resources, in the app's look.
 * It is the same for every screen. Opened from the QR code, the link carries the code in its
 * fragment, and the page shows it for the person to check against the TV; typed in by hand, it
 * asks for it. Then it asks the TV for the screen's form, draws it, and draws it again whenever the
 * TV changes it. Every request after the first is sealed (PhoneServer's [PhoneServer.handle]).
 */
object PhonePage {
    @Volatile private var page: String? = null

    /**
     * The page, with [noble] (the panel's noble.js, for HKDF and ChaCha20-Poly1305) inside it.
     * Nothing about a session is in it: that comes from the TV once the code checks out.
     */
    fun shell(noble: () -> String): String = page ?: build(noble()).also { page = it }

    private fun build(noble: String): String =
        """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#05070A">
<title>${esc(APP_NAME)}</title>
<style>$CSS</style></head>
<body>
$WEB
<main>
  <div class="brand"><span class="mark">$MARK</span>${esc(APP_NAME)}</div>
  <h1 id="title">${esc(APP_NAME)}</h1>
  <p class="lede" id="lede">Looking for the TV…</p>
  <section class="card" id="codecard"></section>
  <div id="view"></div>
  <p class="foot">It stays on your home network, encrypted between this page and the TV. This page works while the TV shows its code.</p>
</main>
<script>${noble.replace("</", "<\\/")}</script>
<script>
const APP = ${org.json.JSONObject.quote(APP_NAME).replace("</", "<\\/")};
$SCRIPT
</script>
</body></html>"""

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private const val MARK =
        """<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#F3F6F8" stroke-width="2" stroke-linecap="round"><path d="M4 20v-9a8 8 0 0 1 16 0v9"/><path d="M8.5 20v-8a3.5 3.5 0 0 1 7 0v8"/></svg>"""

    /** A faint corner of the TV's network background. */
    private const val WEB = """<svg class="web" viewBox="0 0 400 260" preserveAspectRatio="xMidYMin slice" aria-hidden="true">
  <g stroke="rgba(75,227,161,.35)" stroke-width=".8" fill="none"><path d="M232 46L284 22L338 58L392 34M284 22L300 92L338 58M300 92L360 124L392 96M232 46L250 118L300 92M250 118L206 150M360 124L338 176"/></g>
  <g fill="rgba(160,245,210,.7)"><circle cx="232" cy="46" r="1.8"/><circle cx="284" cy="22" r="1.8"/><circle cx="338" cy="58" r="2.2"/><circle cx="392" cy="34" r="1.6"/><circle cx="300" cy="92" r="1.8"/><circle cx="360" cy="124" r="1.8"/><circle cx="392" cy="96" r="1.6"/><circle cx="250" cy="118" r="1.6"/><circle cx="206" cy="150" r="1.4"/><circle cx="338" cy="176" r="1.4"/></g>
</svg>"""

    private const val CSS = """
:root{color-scheme:dark;--page:#05070A;--ink:#F3F6F8;--soft:rgba(235,242,248,.64);--faint:rgba(235,242,248,.42);--mint:#4BE3A1;--red:#FF8A7A;--amber:#FFB547;--glass:rgba(16,23,33,.72);--edge:rgba(255,255,255,.10);--well:rgba(255,255,255,.055)}
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
.card{margin-top:22px;padding:18px;border-radius:24px;background:var(--glass);box-shadow:inset 0 0 0 1px var(--edge),0 24px 60px -30px rgba(0,0,0,.8);backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px);transition:box-shadow .3s}
.bare{margin-top:22px}
.bare .seg{margin-bottom:0}
.card.editing{box-shadow:inset 0 0 0 2px rgba(75,227,161,.7),0 24px 60px -30px rgba(0,0,0,.8)}
.card h2{margin:0 0 14px;font-size:18px;line-height:1.25;font-weight:750;letter-spacing:-.01em}
.tvnow{display:inline-block;margin:-4px 0 12px;padding:4px 10px;border-radius:99px;background:rgba(75,227,161,.14);color:var(--mint);font-size:12.5px;font-weight:700}
.eyebrow{display:block;font-size:12px;font-weight:700;letter-spacing:.14em;text-transform:uppercase;color:var(--faint)}
.digits{display:flex;justify-content:center;gap:8px;margin:14px 0 4px}
.digits .gap{width:10px}
.digit{width:44px;height:58px;border-radius:13px;display:grid;place-items:center;background:var(--well);box-shadow:inset 0 0 0 1px var(--edge);font:700 28px/1 ui-rounded,-apple-system,Roboto,sans-serif;font-variant-numeric:tabular-nums}
.check{display:flex;align-items:center;justify-content:center;gap:8px;margin:12px 0 0;color:var(--soft);font-size:14.5px;text-align:center}
.check svg{flex:none}
.codein{display:block;width:100%;margin-top:12px;padding:14px 0;border:0;border-radius:16px;background:var(--well);box-shadow:inset 0 0 0 1px var(--edge);color:var(--ink);text-align:center;font:700 30px/1 ui-rounded,-apple-system,Roboto,sans-serif;letter-spacing:.32em;font-variant-numeric:tabular-nums;outline:none}
.codein:focus{box-shadow:inset 0 0 0 2px var(--mint)}
.codein::placeholder{color:rgba(235,242,248,.18)}
.seg{display:grid;grid-auto-flow:column;grid-auto-columns:1fr;padding:4px;border-radius:14px;background:var(--well);margin-bottom:14px}
.seg button{padding:10px 6px;border:0;border-radius:11px;background:none;color:var(--soft);font-family:inherit;font-size:15px;font-weight:600;line-height:1;cursor:pointer}
.seg button[aria-pressed=true]{background:rgba(255,255,255,.12);color:var(--ink)}
.pick{display:flex;align-items:center;gap:14px;padding:16px;border-radius:18px;background:var(--well);box-shadow:inset 0 0 0 1px var(--edge);cursor:pointer}
.pick input{display:none}
.pick.bad{box-shadow:inset 0 0 0 2px var(--red)}
.icon{width:46px;height:46px;border-radius:14px;display:grid;place-items:center;flex:none;background:rgba(255,255,255,.07)}
.pick.chosen .icon,.file .icon{background:rgba(75,227,161,.14)}
.pick b{display:block;font-size:16px;font-weight:650;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.pick small,.file small{display:block;color:var(--faint);font-size:13.5px;margin-top:2px}
.txt{min-width:0;flex:1}
.pick .change{color:var(--mint);font-weight:650;font-size:14.5px}
textarea,.field{width:100%;border:0;border-radius:16px;background:var(--well);box-shadow:inset 0 0 0 1px var(--edge);color:var(--ink);outline:none}
textarea{display:block;padding:14px 16px;min-height:120px;resize:vertical;font:14px/1.55 ui-monospace,SFMono-Regular,Menlo,monospace}
textarea.paste{min-height:190px;font-size:13.5px}
textarea:focus,.field:focus{box-shadow:inset 0 0 0 2px var(--mint)}
textarea.bad,.field.bad{box-shadow:inset 0 0 0 2px var(--red)}
textarea::placeholder,.field::placeholder{color:rgba(235,242,248,.28)}
textarea:disabled,.field:disabled{opacity:.55}
.lbl{display:block;margin:0 0 10px;font-size:14px;font-weight:650;color:var(--soft)}
.fld+.fld{margin-top:16px}
.fieldrow{position:relative}
.field{padding:16px 54px 16px 16px;font-family:inherit;font-size:19px;font-weight:500;line-height:1.2}
.eye{position:absolute;right:8px;top:50%;transform:translateY(-50%);width:40px;height:40px;border:0;border-radius:12px;background:none;display:grid;place-items:center;cursor:pointer}
.help{margin:8px 2px 0;color:var(--faint);font-size:13.5px}
.err{margin:8px 2px 0;color:var(--red);font-size:14px;font-weight:600}
.note{margin:0 0 14px;color:var(--soft);font-size:14.5px}
.file{display:flex;align-items:center;gap:14px}
.file b{display:block;font-size:16px;font-weight:650;word-break:break-all}
.swrow{display:flex;align-items:center;gap:14px;padding:6px 2px;cursor:pointer}
.swrow b{display:block;font-size:16px;font-weight:650}
.swrow small{display:block;color:var(--faint);font-size:13.5px;margin-top:2px}
.sw{appearance:none;-webkit-appearance:none;margin:0;width:52px;height:30px;border-radius:99px;background:rgba(255,255,255,.18);position:relative;flex:none;cursor:pointer;transition:background .2s}
.sw::after{content:"";position:absolute;top:4px;left:4px;width:22px;height:22px;border-radius:50%;background:rgba(235,242,248,.75);transition:transform .2s,background .2s}
.sw:checked{background:var(--mint)}
.sw:checked::after{transform:translateX(22px);background:#06140D}
.opts{display:flex;flex-direction:column;gap:8px}
.opt{display:flex;align-items:center;gap:12px;width:100%;padding:14px 16px;border:0;border-radius:16px;background:var(--well);box-shadow:inset 0 0 0 1px var(--edge);color:var(--ink);font-family:inherit;text-align:left;cursor:pointer}
.opt b{display:block;font-size:16px;font-weight:650}
.opt small{display:block;color:var(--faint);font-size:13.5px;margin-top:2px}
.opt .dot{width:22px;height:22px;border-radius:50%;flex:none;box-shadow:inset 0 0 0 2px rgba(235,242,248,.35);display:grid;place-items:center}
.opt[aria-pressed=true]{box-shadow:inset 0 0 0 2px var(--mint)}
.opt[aria-pressed=true] .dot{background:var(--mint);box-shadow:none}
.facts{margin:0;display:grid;gap:12px}
.facts dt{color:var(--faint);font-size:13px;font-weight:650}
.facts dd{margin:2px 0 0;font:15px/1.4 ui-monospace,SFMono-Regular,Menlo,monospace;word-break:break-word}
.info{margin:12px 0 0;padding:12px 14px;border-radius:14px;background:var(--well);color:var(--soft);font-size:14.5px}
.info.ok{background:rgba(75,227,161,.12);color:var(--mint)}
.info.warn{background:rgba(255,181,71,.12);color:var(--amber)}
.info.bad{background:rgba(255,138,122,.12);color:var(--red)}
.copy pre{margin:0;padding:14px;border-radius:14px;background:rgba(0,0,0,.35);white-space:pre-wrap;overflow-wrap:anywhere;font:13px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;color:rgba(235,242,248,.86)}
.copy .mini{margin-top:8px}
.mini{padding:9px 14px;border:0;border-radius:99px;background:var(--well);box-shadow:inset 0 0 0 1px var(--edge);color:var(--ink);font-family:inherit;font-size:14px;font-weight:650;cursor:pointer}
details.fold summary{list-style:none;display:flex;align-items:center;justify-content:space-between;font-size:16px;font-weight:700;cursor:pointer}
details.fold summary::-webkit-details-marker{display:none}
details.fold summary::after{content:"";width:9px;height:9px;border-right:2px solid var(--soft);border-bottom:2px solid var(--soft);transform:rotate(45deg);margin-right:6px;transition:transform .2s}
details.fold[open] summary{margin-bottom:14px}
details.fold[open] summary::after{transform:rotate(-135deg)}
.actions{display:flex;flex-direction:column;gap:10px;margin-top:18px}
.btn{display:flex;align-items:center;justify-content:center;gap:10px;width:100%;padding:17px;border:0;border-radius:999px;background:#F4F7FA;color:#071018;font-family:inherit;font-size:17px;font-weight:700;line-height:1;text-decoration:none;cursor:pointer;transition:transform .12s,opacity .2s}
.btn.ghost{background:var(--well);color:var(--ink);box-shadow:inset 0 0 0 1px var(--edge)}
.btn.danger{background:rgba(255,138,122,.14);color:var(--red)}
.btn:active{transform:scale(.98)}
.btn:disabled{opacity:.35}
.btn svg{flex:none}
.confirm{margin-top:18px;padding:16px;border-radius:18px;background:rgba(255,138,122,.12)}
.confirm p{margin:0 0 12px;font-weight:600}
.confirm .actions{margin-top:0}
.status{display:none;align-items:flex-start;gap:10px;margin-top:14px;padding:13px 15px;border-radius:16px;font-size:15px;font-weight:550}
.status.show{display:flex}
.status.done{background:rgba(75,227,161,.12);color:var(--mint)}
.status.error{background:rgba(255,138,122,.12);color:var(--red)}
.status.busy,.status.waiting{background:var(--well);color:var(--soft)}
.status svg{flex:none;margin-top:1px}
.state{display:flex;gap:12px;align-items:flex-start;color:var(--soft);font-size:15.5px}
.state svg{flex:none;margin-top:2px}
.state b{color:var(--ink)}
.spin{animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
.foot{margin:22px 4px 0;color:var(--faint);font-size:13px;text-align:center}
"""

    private const val SCRIPT = """
const byId = (id) => document.getElementById(id);
const ICON = {
  done: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M8.5 12.2l2.4 2.4 4.6-4.8"/></svg>',
  error: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M12 7.5v5.5"/><path d="M12 16.5h.01"/></svg>',
  busy: '<svg class="spin" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M21 12a9 9 0 1 1-9-9"/></svg>',
  waiting: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><rect x="3.5" y="5" width="17" height="11" rx="2"/><path d="M8 20h8"/></svg>',
  check: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#4BE3A1" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M8.5 12.2l2.4 2.4 4.6-4.8"/></svg>',
  eye: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="rgba(235,242,248,.6)" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/></svg>',
  arrow: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14"/><path d="M13 6l6 6-6 6"/></svg>',
  down: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4v12"/><path d="M6 11l6 6 6-6"/><path d="M5 20h14"/></svg>',
  dot: '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#071018" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>',
};
const doc = (stroke) => '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="' + stroke + '" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/></svg>';
const OFFLINE = 'Can’t reach the TV. Your phone must be on the same Wi-Fi as the TV, with mobile data and any VPN off.';
const size = (n) => n < 1024 ? n + ' bytes' : n < 1048576 ? (n / 1024).toFixed(1) + ' KB' : (n / 1048576).toFixed(1) + ' MB';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const json = async (r) => { try { return await r.json(); } catch (e) { return null; } };

// ---- Sealing ------------------------------------------------------------------------------
// Passwords and keys go this way, so what the page sends and gets is sealed: the page and the TV
// agree a key with X25519 when it loads, then each request and answer is ChaCha20-Poly1305 with
// keys from HKDF, as the panel's are (PhoneServer.kt does the other half). The code goes inside.
// X25519 (RFC 7748), after TweetNaCl's crypto_scalarmult (public domain): numbers mod 2^255-19 as 16 limbs of 16 bits.
const x25519 = (() => {
  const gf = (init) => { const r = new Float64Array(16); if (init) r.set(init); return r; };
  const A121665 = gf([0xdb41, 1]);
  function car(o) {
    let c = 1;
    for (let i = 0; i < 16; i++) {
      const v = o[i] + c + 65535;
      c = Math.floor(v / 65536);
      o[i] = v - c * 65536;
    }
    o[0] += c - 1 + 37 * (c - 1);
  }
  function swap(p, q, b) {
    const c = ~(b - 1);
    for (let i = 0; i < 16; i++) { const t = c & (p[i] ^ q[i]); p[i] ^= t; q[i] ^= t; }
  }
  function pack(o, n) {
    const m = gf(), t = gf(n);
    car(t); car(t); car(t);
    for (let j = 0; j < 2; j++) {
      m[0] = t[0] - 0xffed;
      for (let i = 1; i < 15; i++) { m[i] = t[i] - 0xffff - ((m[i - 1] >> 16) & 1); m[i - 1] &= 0xffff; }
      m[15] = t[15] - 0x7fff - ((m[14] >> 16) & 1);
      const b = (m[15] >> 16) & 1;
      m[14] &= 0xffff;
      swap(t, m, 1 - b);
    }
    for (let i = 0; i < 16; i++) { o[2 * i] = t[i] & 0xff; o[2 * i + 1] = t[i] >> 8; }
  }
  function unpack(o, n) {
    for (let i = 0; i < 16; i++) o[i] = n[2 * i] + (n[2 * i + 1] << 8);
    o[15] &= 0x7fff;
  }
  const add = (o, a, b) => { for (let i = 0; i < 16; i++) o[i] = a[i] + b[i]; };
  const sub = (o, a, b) => { for (let i = 0; i < 16; i++) o[i] = a[i] - b[i]; };
  function mul(o, a, b) {
    const t = new Float64Array(31);
    for (let i = 0; i < 16; i++) for (let j = 0; j < 16; j++) t[i + j] += a[i] * b[j];
    for (let i = 0; i < 15; i++) t[i] += 38 * t[i + 16];
    for (let i = 0; i < 16; i++) o[i] = t[i];
    car(o); car(o);
  }
  const sq = (o, a) => mul(o, a, a);
  function inv(o, i) {
    const c = gf(i);
    for (let a = 253; a >= 0; a--) { sq(c, c); if (a !== 2 && a !== 4) mul(c, c, i); }
    o.set(c);
  }
  /** The X25519 function: scalar [n] times the point [p], both 32 bytes. */
  function scalarMult(n, p) {
    const z = Uint8Array.from(n);
    z[31] = (z[31] & 127) | 64;
    z[0] &= 248;
    const x = gf(), a = gf(), b = gf(), c = gf(), d = gf(), e = gf(), f = gf();
    unpack(x, p);
    b.set(x);
    a[0] = d[0] = 1;
    for (let i = 254; i >= 0; --i) {
      const r = (z[i >>> 3] >>> (i & 7)) & 1;
      swap(a, b, r); swap(c, d, r);
      add(e, a, c); sub(a, a, c); add(c, b, d); sub(b, b, d);
      sq(d, e); sq(f, a); mul(a, c, a); mul(c, b, e);
      add(e, a, c); sub(a, a, c); sq(b, a); sub(c, d, f);
      mul(a, c, A121665); add(a, a, d); mul(c, c, a); mul(a, d, f); mul(d, b, x); sq(b, e);
      swap(a, b, r); swap(c, d, r);
    }
    inv(c, c);
    mul(a, a, c);
    const q = new Uint8Array(32);
    pack(q, a);
    return q;
  }
  const BASE = new Uint8Array(32); BASE[0] = 9;
  return { scalarMult, publicKey: (k) => scalarMult(k, BASE) };
})();
const enc = new TextEncoder(), dec = new TextDecoder();
function b64(u8) {
  let s = '';
  for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
  return btoa(s);
}
const unb64 = (s) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4)), (c) => c.charCodeAt(0));
const hk = (secret, info) => noble.hkdf(noble.sha256, secret, undefined, enc.encode(info), 32);
function nonce(dir, n) {
  const b = new Uint8Array(12);
  b[0] = dir;
  const v = new DataView(b.buffer);
  v.setUint32(4, Math.floor(n / 4294967296));
  v.setUint32(8, n >>> 0);
  return b;
}
const AAD = enc.encode('POST /api/x');
let chan = null, agreeing = null, counter = 0;

/** Agrees this page's keys with the TV. Resolves to nothing, or to the TV's refusal as an answer. */
function agree() {
  if (!agreeing) {
    agreeing = (async () => {
      const secret = crypto.getRandomValues(new Uint8Array(32));
      const mine = x25519.publicKey(secret);
      const r = await fetch('/api/hello', post(JSON.stringify({ p: b64(mine) }), 'application/json'));
      const j = await json(r);
      if (!r.ok || !j) return { status: r.status, ok: false, j: j };
      const theirs = unb64(j.t);
      const ikm = new Uint8Array(96);
      ikm.set(x25519.scalarMult(secret, theirs));
      ikm.set(mine, 32);
      ikm.set(theirs, 64);
      chan = { h: j.h, up: hk(ikm, 'ovpnc phone up v1'), down: hk(ikm, 'ovpnc phone down v1') };
      counter = 0;
    })().finally(() => { agreeing = null; });
  }
  return agreeing;
}

/**
 * One sealed request to the TV: [op] and its [fields], with the code. Resolves to the TV's answer
 * as { status, ok, j }; throws, as fetch does, when the TV can't be reached.
 */
async function request(op, fields, signal, retried) {
  if (!chan) {
    const refused = await agree();
    if (refused) return refused;
  }
  const c = chan, n = ++counter;
  const sealed = noble.chacha20poly1305(c.up, nonce(0, n), AAD).encrypt(enc.encode(JSON.stringify(Object.assign({ c: code, op: op }, fields || {}))));
  const r = await fetch('/api/x?h=' + c.h + '&n=' + n, { method: 'POST', headers: { 'X-Phone': '1', 'Content-Type': 'application/octet-stream' }, body: sealed, signal: signal, cache: 'no-store' });
  if (r.status === 401 && !retried) {
    const j = await json(r);
    // The TV forgot this page's key (it started again, or many pages came since): agree a new one.
    if (j && j.error === 'counter') counter = Math.max(counter, j.next - 1);
    else if (chan === c) chan = null;
    return request(op, fields, signal, true);
  }
  if (r.status !== 200 || (r.headers.get('content-type') || '').indexOf('octet-stream') < 0) return { status: r.status, ok: false, j: await json(r) };
  try {
    const a = JSON.parse(dec.decode(noble.chacha20poly1305(c.down, nonce(1, n), AAD).decrypt(new Uint8Array(await r.arrayBuffer()))));
    return { status: a.s, ok: a.s >= 200 && a.s < 300, j: a.j };
  } catch (e) {
    return { status: 502, ok: false, j: null };
  }
}

/**
 * Every request to the TV but the page's own waiting goes through here, so each answer the TV can
 * give is handled once: a wrong or ended code, too many tries, or a refusal said in words.
 */
async function call(op, fields, where) {
  let r;
  try {
    r = await request(op, fields);
  } catch (e) {
    showStatus(where, { kind: 'error', text: OFFLINE });
    return null;
  }
  const j = r.j;
  if (r.status === 403 && j && j.error === 'code') { wrongCode(j.text); return null; }
  if (r.status === 410) { pollId++; stateView('ended'); return null; }
  if (r.status === 429) { stateView('wait', j && j.text); return null; }
  if (!r.ok) { showStatus(where, { kind: 'error', text: (j && j.text) || 'The TV refused that.' }); return null; }
  return { r: r, j: j };
}

/** What a POST to the TV carries: the page's own header, which another site can't send. */
const post = (body, type) => ({ method: 'POST', headers: Object.assign({ 'X-Phone': '1' }, type ? { 'Content-Type': type } : {}), body: body });
const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };

const fromLink = (new URLSearchParams(location.hash.slice(1)).get('c') || '').replace(/\D/g, '').slice(0, 6);
let code = fromLink;
let rev = 0, form = null, pollId = 0, sending = null, lastFocus = null, statusNow = null, view = 'none';
// What the person typed, by field, and whether it differs from what the TV last said.
const local = {}, dirty = {}, pending = {}, errors = {}, openFolds = {}, mirrorTimers = {};

// ---- The code ------------------------------------------------------------------
function codeCard(problem) {
  const c = byId('codecard');
  c.innerHTML = '';
  if (code.length === 6 && !problem) {
    c.appendChild(el('div', 'eyebrow', 'Code on the TV'));
    const d = el('div', 'digits');
    code.split('').forEach((n, i) => { if (i === 3) d.appendChild(el('span', 'gap')); d.appendChild(el('span', 'digit', n)); });
    c.appendChild(d);
    const p = el('p', 'check');
    p.innerHTML = ICON.check + '<span>Check that the TV shows this code.</span>';
    c.appendChild(p);
    return;
  }
  const lab = el('label', 'eyebrow', 'Code on the TV');
  lab.htmlFor = 'code';
  c.appendChild(lab);
  const inp = el('input', 'codein');
  inp.id = 'code'; inp.inputMode = 'numeric'; inp.autocomplete = 'one-time-code'; inp.maxLength = 7; inp.placeholder = '000 000';
  c.appendChild(inp);
  if (problem) c.appendChild(el('p', 'err', problem));
  inp.oninput = () => {
    const d = inp.value.replace(/\D/g, '').slice(0, 6);
    inp.value = d.length > 3 ? d.slice(0, 3) + ' ' + d.slice(3) : d;
    if (d.length === 6) { code = d; codeCard(); start(); }
  };
  setTimeout(() => inp.focus(), 50);
}

// ---- Asking the TV --------------------------------------------------------------
function start() {
  rev = 0;
  poll();
}

async function poll() {
  const me = ++pollId;
  // A short gap, such as the TV bringing a tunnel up, is retried quietly before the page says so.
  let failSince = 0;
  const failed = async (timedOut) => {
    if (!failSince) failSince = Date.now();
    // A request that got no answer at all is no blip: the TV is gone.
    const long = timedOut || Date.now() - failSince > 8000;
    if (long) offline();
    await sleep(long ? 3000 : 1000);
  };
  while (me === pollId) {
    let r;
    // The TV answers within 25 s even with nothing new, so no answer in 30 s means it is unreachable.
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), 30000);
    try {
      r = await request('state', { rev: rev }, ctl.signal);
    } catch (e) {
      clearTimeout(timer);
      await failed(ctl.signal.aborted);
      continue;
    }
    clearTimeout(timer);
    if (me !== pollId) return;
    if (r.ok) failSince = 0;
    if (r.status === 403) { pollId++; return wrongCode(r.j && r.j.text); }
    if (r.status === 410) { pollId++; return stateView('ended'); }
    if (r.status === 429) { stateView('wait', r.j && r.j.text); rev = 0; await sleep(60000); continue; }
    if (!r.ok) { await failed(); continue; }
    const s = r.j;
    if (!s) { await sleep(1000); continue; }
    apply(s);
  }
}

function apply(s) {
  rev = s.rev;
  if (s.state === 'moved') return stateView('moved', s.place);
  form = s.form;
  statusNow = s.status;
  render(s.focus);
}

function wrongCode(text) {
  form = null;
  byId('view').innerHTML = '';
  byId('title').textContent = 'Type the code';
  byId('lede').textContent = 'Type the code the TV shows now.';
  code = '';
  codeCard(text || 'That code isn’t on the TV now. Check the code on the TV.');
}

function offline() {
  // Asked again with no revision, the TV answers at once when it is back.
  rev = 0;
  if (view === 'offline') return;
  stateView('offline');
}

/** A whole-page message instead of the form: the TV moved on, the code ended, no connection. */
function stateView(kind, text) {
  view = kind;
  // The app's name is already at the top, so the title says what happened.
  byId('title').textContent = kind === 'moved' ? 'The TV moved on' : kind === 'ended' ? 'This code has ended' : kind === 'offline' ? 'Can’t reach the TV' : 'Too many wrong codes';
  byId('lede').textContent = kind === 'moved' ? 'Waiting for the TV to come back.' : kind === 'ended' ? 'Scan the new code on the TV.' : kind === 'offline' ? 'Trying again every few seconds.' : 'Wait a minute, then try again.';
  const v = byId('view');
  v.innerHTML = '';
  const card = el('section', 'card');
  const row = el('div', 'state');
  const say = (icon, html) => { row.innerHTML = icon + '<span>' + html + '</span>'; };
  if (kind === 'moved') {
    say(ICON.waiting, '');
    row.lastChild.textContent = 'To carry on, open ' + (text && text !== 'this screen' ? text : 'the same screen') + ' on the TV again, or scan the code on the screen it shows now. This page comes back by itself.';
  }
  else if (kind === 'ended') say(ICON.error, '<b>This code has ended.</b> Open the screen on the TV again and scan its new code.');
  else if (kind === 'wait') say(ICON.error, '');
  else say(ICON.error, '');
  if (kind === 'wait' || kind === 'offline') row.lastChild.textContent = kind === 'wait' ? (text || 'Wait a minute, then try again.') : OFFLINE;
  card.appendChild(row);
  if (kind === 'offline') {
    const again = el('button', 'btn ghost', 'Try again');
    again.style.marginTop = '16px';
    again.onclick = () => { view = 'none'; start(); };
    card.appendChild(again);
  }
  v.appendChild(card);
}

// ---- Drawing the form -------------------------------------------------------------
const fields = () => form ? form.sections.flatMap((s) => s.fields) : [];
const fieldById = (id) => fields().find((f) => f.id === id);
const tvValue = (f) => f.type === 'switch' ? (f.value ? 'true' : 'false') : (f.value == null ? '' : String(f.value));
const valueOf = (f) => (f.id in local && (dirty[f.id] || (pending[f.id] && rev <= pending[f.id]))) ? local[f.id] : tvValue(f);
const textish = (f) => f.type === 'text' || f.type === 'lines';

// The TV's last value of each field, to tell when the TV itself changed one the phone was editing.
const seen = {};

function render(focusSection) {
  view = 'form';
  // The TV changed a field the phone was editing (renamed on the TV, say): the TV's value wins, and the page says so.
  const changedOnTv = [];
  form.sections.forEach((sec) => sec.fields.forEach((f) => {
    if (!textish(f) || f.secret) return;
    const tv = tvValue(f);
    if (f.id in seen && seen[f.id] !== tv && dirty[f.id] && local[f.id] !== tv) {
      delete local[f.id];
      dirty[f.id] = false;
      changedOnTv.push(f.label);
    }
    seen[f.id] = tv;
  }));
  if (changedOnTv.length && !statusNow) statusNow = { kind: 'waiting', text: changedOnTv.join(' and ') + ' changed on the TV.' };
  const active = document.activeElement;
  const keep = active && active.id;
  let s0 = null, s1 = null;
  try { if (keep && 'selectionStart' in active) { s0 = active.selectionStart; s1 = active.selectionEnd; } } catch (e) {}
  byId('title').textContent = form.title;
  byId('lede').textContent = form.lede;
  const v = byId('view');
  v.innerHTML = '';
  const top = el('div', 'status');
  top.id = 'st-top';
  v.appendChild(top);
  // Pointing at the part the TV edits only helps when there is more than one, and only for a part with fields to edit.
  const editable = (sec) => sec.fields.some((f) => ['text', 'lines', 'switch', 'choice', 'select'].includes(f.type));
  const point = form.sections.filter(editable).length > 1 && form.sections.some((sec) => sec.id === focusSection && editable(sec)) ? focusSection : null;
  form.sections.forEach((sec) => v.appendChild(sectionEl(sec, sec.id === point)));
  visibility();
  readiness();
  showErrors();
  placeStatus();
  if (keep && byId(keep)) {
    const e = byId(keep);
    e.focus({ preventScroll: true });
    try { if (s0 != null) e.setSelectionRange(s0, s1); } catch (err) {}
  }
  if (focusSection && focusSection !== lastFocus) {
    const e = byId('s-' + focusSection);
    if (e && !e.hidden) e.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
  lastFocus = focusSection;
}

function sectionEl(sec, editing) {
  // Tabs on their own sit on the page, not in a card of their own.
  const bare = !sec.title && !sec.note && !sec.actions.length && sec.fields.length === 1 && sec.fields[0].type === 'choice';
  const card = el('section', bare ? 'bare' : 'card' + (editing ? ' editing' : ''));
  card.id = 's-' + sec.id;
  if (sec.showWhen) card.dataset.when = JSON.stringify(sec.showWhen);
  let body = card;
  if (sec.folded) {
    const d = el('details', 'fold');
    d.appendChild(el('summary', null, sec.title || ''));
    d.open = !!openFolds[sec.id] || editing;
    d.ontoggle = () => { openFolds[sec.id] = d.open; };
    card.appendChild(d);
    body = d;
  } else if (sec.title) {
    card.appendChild(el('h2', null, sec.title));
  }
  if (editing) body.appendChild(el('span', 'tvnow', 'The TV is editing this'));
  if (sec.note) body.appendChild(el('p', 'note', sec.note));
  sec.fields.forEach((f) => body.appendChild(fieldEl(f, sec)));
  if (sec.actions.length) {
    const row = el('div', 'actions');
    row.id = 'a-' + sec.id;
    sec.actions.forEach((a) => row.appendChild(actionEl(a, sec)));
    body.appendChild(row);
  }
  const st = el('div', 'status');
  st.id = 'st-' + sec.id;
  body.appendChild(st);
  return card;
}

function edit(f, value) {
  local[f.id] = value;
  dirty[f.id] = true;
  if (errors[f.id]) { delete errors[f.id]; showErrors(); }
  visibility();
  readiness();
  if (f.mirror) {
    clearTimeout(mirrorTimers[f.id]);
    mirrorTimers[f.id] = setTimeout(() => {
      request('input', { field: f.id, value: value }).catch(() => {});
    }, 200);
  }
}

function labelFor(f, target) {
  const lab = el('label', 'lbl', f.label);
  lab.htmlFor = target;
  return lab;
}

function fieldEl(f, sec) {
  const wrap = el('div', 'fld');
  wrap.id = 'w-' + f.id;
  const err = el('p', 'err');
  err.id = 'e-' + f.id;
  err.hidden = true;
  if (f.type === 'text') {
    wrap.appendChild(labelFor(f, 'f-' + f.id));
    const row = el('div', 'fieldrow');
    const inp = el('input', 'field');
    inp.id = 'f-' + f.id;
    inp.type = f.secret ? 'password' : (f.input === 'email' ? 'email' : 'text');
    if (f.input === 'numeric') { inp.inputMode = 'numeric'; inp.pattern = '[0-9]*'; }
    if (f.input === 'url') inp.inputMode = 'url';
    inp.maxLength = f.max || 256;
    inp.placeholder = f.placeholder || '';
    inp.autocomplete = 'off';
    inp.setAttribute('autocapitalize', 'off');
    inp.setAttribute('autocorrect', 'off');
    inp.spellcheck = false;
    inp.value = valueOf(f);
    inp.oninput = () => edit(f, inp.value);
    inp.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); pressPrimary(sec); } };
    row.appendChild(inp);
    if (f.secret) {
      const eye = el('button', 'eye');
      eye.type = 'button';
      eye.setAttribute('aria-label', 'Show');
      eye.innerHTML = ICON.eye;
      eye.onclick = () => { inp.type = inp.type === 'password' ? 'text' : 'password'; inp.focus(); };
      row.appendChild(eye);
    } else {
      inp.style.paddingRight = '16px';
    }
    wrap.appendChild(row);
  } else if (f.type === 'lines' || f.type === 'paste') {
    wrap.appendChild(labelFor(f, 'f-' + f.id));
    const t = el('textarea', f.type === 'paste' ? 'paste' : null);
    t.id = 'f-' + f.id;
    t.placeholder = f.placeholder || '';
    t.spellcheck = false;
    t.setAttribute('autocapitalize', 'off');
    t.setAttribute('autocorrect', 'off');
    if (f.type === 'lines') {
      t.value = valueOf(f);
      t.oninput = () => edit(f, t.value);
    } else {
      // Pasted text is checked on the TV like a file, a moment after the typing stops.
      t.value = local[f.id] || '';
      let timer = null;
      const sendIt = () => { if (t.value.trim()) upload(f, sec, new Blob([t.value], { type: 'text/plain' }), 'pasted.ovpn'); };
      t.oninput = () => { local[f.id] = t.value; dirty[f.id] = true; clearTimeout(timer); timer = setTimeout(sendIt, 700); readiness(); };
      t.onblur = () => { clearTimeout(timer); if (dirty[f.id]) sendIt(); };
      if (f.file) wrap.appendChild(el('p', 'help', 'The TV has it: ' + size(f.file.size) + (f.file.sub ? ' · ' + f.file.sub : '')));
    }
    wrap.appendChild(t);
  } else if (f.type === 'switch') {
    const row = el('label', 'swrow');
    const txt = el('span', 'txt');
    txt.appendChild(el('b', null, f.label));
    if (f.sub) txt.appendChild(el('small', null, f.sub));
    const sw = el('input', 'sw');
    sw.type = 'checkbox';
    sw.id = 'f-' + f.id;
    sw.checked = valueOf(f) === 'true';
    sw.onchange = () => edit(f, sw.checked ? 'true' : 'false');
    row.appendChild(txt);
    row.appendChild(sw);
    wrap.appendChild(row);
  } else if (f.type === 'choice') {
    if (f.label) wrap.appendChild(el('div', 'lbl', f.label));
    const seg = el('div', 'seg');
    seg.setAttribute('role', 'group');
    f.options.forEach((o) => {
      const b = el('button', null, o[1]);
      b.type = 'button';
      b.id = 'f-' + f.id + '-' + o[0];
      b.setAttribute('aria-pressed', valueOf(f) === o[0]);
      b.onclick = () => {
        edit(f, o[0]);
        seg.querySelectorAll('button').forEach((x) => x.setAttribute('aria-pressed', x === b));
      };
      seg.appendChild(b);
    });
    wrap.appendChild(seg);
  } else if (f.type === 'select') {
    wrap.appendChild(el('div', 'lbl', f.label));
    const list = el('div', 'opts');
    f.options.forEach((o) => {
      const b = el('button', 'opt');
      b.type = 'button';
      b.id = 'f-' + f.id + '-' + o.value;
      b.setAttribute('aria-pressed', valueOf(f) === o.value);
      const dot = el('span', 'dot');
      dot.innerHTML = ICON.dot;
      const t = el('span', 'txt');
      t.appendChild(el('b', null, o.label));
      if (o.sub) t.appendChild(el('small', null, o.sub));
      b.appendChild(dot);
      b.appendChild(t);
      b.onclick = () => {
        edit(f, o.value);
        list.querySelectorAll('.opt').forEach((x) => x.setAttribute('aria-pressed', x === b));
      };
      list.appendChild(b);
    });
    wrap.appendChild(list);
  } else if (f.type === 'upload') {
    const pick = el('label', 'pick' + (f.file ? ' chosen' : ''));
    pick.id = 'f-' + f.id;
    const inp = el('input');
    inp.type = 'file';
    inp.accept = f.accept;
    const icon = el('span', 'icon');
    icon.innerHTML = doc(f.file ? '#4BE3A1' : 'rgba(235,242,248,.8)');
    const t = el('span', 'txt');
    t.appendChild(el('b', null, f.file ? f.file.name : f.label));
    t.appendChild(el('small', null, f.file ? size(f.file.size) + (f.file.sub ? ' · ' + f.file.sub : '') : f.sub));
    pick.appendChild(inp);
    pick.appendChild(icon);
    pick.appendChild(t);
    if (f.file) pick.appendChild(el('span', 'change', 'Change'));
    inp.onchange = () => { const file = inp.files[0]; if (file) upload(f, sec, file, file.name); };
    wrap.appendChild(pick);
  } else if (f.type === 'download') {
    const row = el('div', 'file');
    const icon = el('span', 'icon');
    icon.innerHTML = doc('#4BE3A1');
    const t = el('span', 'txt');
    t.appendChild(el('b', null, f.name));
    if (f.size > 0) t.appendChild(el('small', null, size(f.size)));
    row.appendChild(icon);
    row.appendChild(t);
    wrap.appendChild(row);
    if (f.note) { const n = el('p', 'help', f.note); wrap.appendChild(n); }
    const b = el('button', 'btn');
    b.type = 'button';
    b.innerHTML = '<span></span>' + ICON.down;
    b.firstChild.textContent = f.label;
    b.onclick = () => download(f.id, f.name, sec.id, b);
    wrap.appendChild(b);
  } else if (f.type === 'info') {
    if (f.rows && f.rows.length) {
      const dl = el('dl', 'facts');
      f.rows.forEach((r) => { const d = el('div'); d.appendChild(el('dt', null, r[0])); d.appendChild(el('dd', null, r[1])); dl.appendChild(d); });
      wrap.appendChild(dl);
    }
    if (f.text) wrap.appendChild(el('p', 'info' + (f.tone ? ' ' + f.tone : ''), f.text));
  } else if (f.type === 'copy') {
    wrap.className = 'fld copy';
    if (f.label) wrap.appendChild(el('div', 'lbl', f.label));
    const pre = el('pre', null, f.text);
    wrap.appendChild(pre);
    const b = el('button', 'mini', 'Copy');
    b.type = 'button';
    b.onclick = () => copyText(f.text, b);
    wrap.appendChild(b);
  }
  if (f.help && f.type !== 'paste') wrap.appendChild(el('p', 'help', f.help));
  wrap.appendChild(err);
  return wrap;
}

function actionEl(a, sec) {
  const b = el('button', 'btn' + (a.primary ? '' : a.danger ? ' danger' : ' ghost'));
  b.type = 'button';
  b.id = 'b-' + a.id;
  b.innerHTML = '<span></span>' + (a.primary ? ICON.arrow : '');
  b.firstChild.textContent = a.label;
  b.dataset.needs = JSON.stringify(a.needs || []);
  b.onclick = () => {
    const ask = a.confirm || chosenConfirm(sec);
    if (ask) confirmFirst(sec, a, ask); else act(a, sec);
  };
  return b;
}

/** A choice that replaces something asks first: "Hatlevika has its own certificate. Replace it?" */
function chosenConfirm(sec) {
  for (const f of sec.fields) {
    if (f.type !== 'select') continue;
    const o = f.options.find((x) => x.value === valueOf(f));
    if (o && o.confirm) return o.confirm;
  }
  return null;
}

function confirmFirst(sec, a, text) {
  const row = byId('a-' + sec.id);
  if (!row) return act(a, sec);
  row.hidden = true;
  const box = el('div', 'confirm');
  box.appendChild(el('p', null, text));
  const btns = el('div', 'actions');
  const yes = el('button', 'btn danger', a.confirmLabel || a.label);
  const keep = el('button', 'btn ghost', 'Keep');
  yes.onclick = () => { box.remove(); row.hidden = false; act(a, sec); };
  keep.onclick = () => { box.remove(); row.hidden = false; };
  btns.appendChild(yes);
  btns.appendChild(keep);
  box.appendChild(btns);
  row.parentNode.insertBefore(box, row);
}

function pressPrimary(sec) {
  const a = sec.actions.find((x) => x.primary) || sec.actions[0];
  const b = a && byId('b-' + a.id);
  if (b && !b.disabled) b.click();
}

function visibility() {
  document.querySelectorAll('[data-when]').forEach((card) => {
    const w = JSON.parse(card.dataset.when);
    const f = fieldById(w[0]);
    card.hidden = !f || valueOf(f) !== w[1];
  });
}

function readiness() {
  document.querySelectorAll('.actions .btn[data-needs]').forEach((b) => {
    const needs = JSON.parse(b.dataset.needs);
    const ok = needs.every((id) => { const f = fieldById(id); return f && String(valueOf(f)).trim() !== ''; });
    b.disabled = !ok || !!sending;
  });
}

function showErrors() {
  fields().forEach((f) => {
    const e = byId('e-' + f.id);
    const input = byId('f-' + f.id);
    const text = errors[f.id];
    if (e) { e.hidden = !text; e.textContent = text || ''; }
    if (input) input.classList.toggle('bad', !!text);
  });
}

function showStatus(where, st) {
  document.querySelectorAll('.status').forEach((s) => { s.className = 'status'; s.innerHTML = ''; });
  if (!st) return;
  let box = byId('st-' + where);
  if (!box || box.closest('[hidden]')) box = byId('st-top');
  if (!box) return;
  box.className = 'status show ' + st.kind;
  box.innerHTML = (ICON[st.kind] || ICON.waiting) + '<span></span>';
  box.lastChild.textContent = st.text;
  const r = box.getBoundingClientRect();
  if (r.top < 0 || r.bottom > innerHeight) box.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function placeStatus() {
  showStatus(statusNow && statusNow.section ? statusNow.section : 'top', statusNow);
}

function lock(on) {
  document.querySelectorAll('#view input, #view textarea, #view .seg button, #view .opt').forEach((e) => { e.disabled = on; });
}

function values() {
  const out = {};
  fields().forEach((f) => {
    if (f.type === 'upload' || f.type === 'download' || f.type === 'info' || f.type === 'copy' || f.type === 'paste') return;
    out[f.id] = valueOf(f);
  });
  return out;
}

function settle(j, where) {
  if (!j) return;
  if (j.moved) return stateView('moved');
  Object.keys(errors).forEach((k) => delete errors[k]);
  Object.assign(errors, j.errors || {});
  if (j.ok) {
    fields().forEach((f) => { if (textish(f) && dirty[f.id]) { pending[f.id] = j.rev; dirty[f.id] = false; } });
  }
  (j.clear || []).forEach((id) => {
    delete local[id];
    dirty[id] = false;
    delete pending[id];
    const inp = byId('f-' + id);
    if (inp) inp.value = '';
  });
  showErrors();
  statusNow = j.status;
  if (statusNow) placeStatus(); else showStatus(where, null);
  if (j.download) download(j.download.id, j.download.name, where, null);
}

async function act(a, sec) {
  if (sending) return;
  sending = a.id;
  readiness();
  lock(true);
  showStatus(sec.id, { kind: 'busy', text: 'Sending…' });
  const got = await call('act', { action: a.id, section: sec.id, values: values() }, sec.id);
  if (got) settle(got.j, sec.id);
  sending = null;
  lock(false);
  readiness();
}

/** A photo is far larger than the TV takes: a QR code in it reads just as well at 1600 pixels. */
async function shrink(file) {
  if (!file.type || !file.type.startsWith('image/')) return file;
  try {
    const img = await createImageBitmap(file);
    const k = Math.min(1, 1600 / Math.max(img.width, img.height));
    const c = document.createElement('canvas');
    c.width = Math.round(img.width * k);
    c.height = Math.round(img.height * k);
    c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
    return await new Promise((done) => c.toBlob((b) => done(b || file), 'image/jpeg', 0.85));
  } catch (e) { return file; }
}

async function upload(f, sec, blob, name) {
  showStatus(sec.id, { kind: 'busy', text: 'Sending ' + name + '…' });
  blob = await shrink(blob);
  if (blob.size > 512 * 1024) return showStatus(sec.id, { kind: 'error', text: 'That file is too large. A profile, backup or certificate is much smaller.' });
  const data = b64(new Uint8Array(await blob.arrayBuffer()));
  const got = await call('file', { field: f.id, section: sec.id, name: name, data: data }, sec.id);
  if (!got) return;
  if (got.j && got.j.ok && f.type === 'paste') dirty[f.id] = false;
  settle(got.j, sec.id);
}

// The file is fetched in the background and saved from here, so the page stays and can say what happened.
async function download(id, name, where, b) {
  if (b) b.disabled = true;
  showStatus(where, { kind: 'busy', text: 'Downloading…' });
  const got = await call('download', { id: id, section: where }, where);
  if (got) {
    const url = URL.createObjectURL(new Blob([unb64(got.j.data)], { type: got.j.mime }));
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
    statusNow = { kind: 'done', text: 'Downloaded ' + name + '. Look in Files › Downloads on your phone.', section: where };
    placeStatus();
  }
  if (b) b.disabled = false;
}

function copyText(text, b) {
  const done = () => { b.textContent = 'Copied'; setTimeout(() => { b.textContent = 'Copy'; }, 1600); };
  // The clipboard API needs https; on the home network, or when it is refused, an old-style copy does it.
  const old = () => {
    const t = document.createElement('textarea');
    t.value = text;
    t.style.position = 'fixed';
    t.style.opacity = '0';
    document.body.appendChild(t);
    t.select();
    let ok = false;
    try { ok = document.execCommand('copy'); } catch (e) {}
    t.remove();
    if (ok) done(); else b.textContent = 'Select it and copy';
  };
  if (navigator.clipboard && window.isSecureContext) { navigator.clipboard.writeText(text).then(done, old); return; }
  old();
}

codeCard();
if (code.length === 6) start(); else byId('lede').textContent = 'Type the code the TV shows.';
"""
}

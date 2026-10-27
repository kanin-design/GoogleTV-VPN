/*
 * The paired phone's panel for the TV: status and actions, profiles, every setting, import,
 * backup and the log. Every request and answer is sealed with this phone's device key
 * (ChaCha20-Poly1305, keys from HKDF-SHA256), as remote/Seal.kt does on the TV. Changes are
 * collected as a draft, checked by the TV, shown as a preview, then applied (and tested).
 */
'use strict';
(() => {
  // ---- Small helpers ----------------------------------------------------------------------
  const $ = (id) => document.getElementById(id);
  // The name until the TV says its own (state.tv.app).
  const APP = 'OpenTVPN';
  const enc = new TextEncoder();
  const dec = new TextDecoder();
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  function add(parent, ...kids) {
    kids.flat().forEach((k) => { if (k) parent.append(k); });
    return parent;
  }
  const svg = (html) => { const s = el('span'); s.innerHTML = html; return s.firstChild; };
  const ICON = {
    power: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M12 3v8"/><path d="M6.4 6.6a8 8 0 1 0 11.2 0"/></svg>',
    check: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#06140D" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>',
    bang: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#1A0503" stroke-width="3.4" stroke-linecap="round"><path d="M12 6v8"/><path d="M12 18.5h.01"/></svg>',
    spin: '<svg class="spin" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M21 12a9 9 0 1 1-9-9"/></svg>',
    plus: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>',
    doc: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="rgba(235,242,248,.8)" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/></svg>',
    phone: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="rgba(235,242,248,.8)" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="7" y="3" width="10" height="18" rx="2.5"/><path d="M11 18h2"/></svg>',
  };
  const fmtBytes = (n) => n < 1024 ? n + ' B' : n < 1048576 ? (n / 1024).toFixed(0) + ' KB' : n < 1073741824 ? (n / 1048576).toFixed(1) + ' MB' : (n / 1073741824).toFixed(2) + ' GB';
  // Hours and minutes on a 24-hour clock, as the TV shows them.
  const clock = (ms) => new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
  // A running time as the TV's Connect shows it: 00:02:10.
  const hms = (ms) => { const t = Math.max(0, Math.floor(ms / 1000)); return [Math.floor(t / 3600), Math.floor(t / 60) % 60, t % 60].map((n) => String(n).padStart(2, '0')).join(':'); };
  function ago(ms) {
    const m = Math.floor((Date.now() - ms) / 60000);
    if (m < 1) return 'just now';
    if (m < 60) return m + ' min ago';
    const h = Math.floor(m / 60);
    if (h < 24) return h + ' h ago';
    const d = Math.floor(h / 24);
    return d === 1 ? 'yesterday' : d + ' days ago';
  }
  const same = (a, b) => JSON.stringify(a == null ? null : a) === JSON.stringify(b == null ? null : b);

  let toastTimer = 0;
  function toast(text, bad) {
    const t = $('toast');
    t.textContent = text;
    t.className = 'toast show' + (bad ? ' bad' : '');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.className = 'toast'; }, bad ? 5000 : 2600);
  }

  // ---- Sealing ------------------------------------------------------------------------------
  function b64(u8) {
    let s = '';
    for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
    return btoa(s);
  }
  const unb64 = (s) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4)), (c) => c.charCodeAt(0));
  const hk = (secret, info) => noble.hkdf(noble.sha256, secret, undefined, enc.encode(info), 32);
  function keysOf(secret, purpose) {
    const p = purpose ? purpose + ' ' : '';
    return { up: hk(secret, 'ovpnc ' + p + 'up v1'), down: hk(secret, 'ovpnc ' + p + 'down v1') };
  }
  // This page's lane: two tabs that take the same counter still never share a nonce (Seal.kt).
  const LANE = crypto.getRandomValues(new Uint32Array(1))[0] & 0xffffff;
  function nonce(dir, n) {
    const b = new Uint8Array(12);
    b[0] = dir;
    b[1] = LANE >>> 16;
    b[2] = (LANE >>> 8) & 0xff;
    b[3] = LANE & 0xff;
    const v = new DataView(b.buffer);
    v.setUint32(4, Math.floor(n / 4294967296));
    v.setUint32(8, n >>> 0);
    return b;
  }
  const seal = (key, dir, n, aad, plain) => noble.chacha20poly1305(key, nonce(dir, n), enc.encode(aad)).encrypt(plain);
  function unseal(key, dir, n, aad, sealed) {
    try { return noble.chacha20poly1305(key, nonce(dir, n), enc.encode(aad)).decrypt(sealed); } catch (e) { return null; }
  }

  // The device key lives in this browser, for this TV's address only.
  const STORE = 'ovpnc:' + location.host;
  function loadMe() { try { return JSON.parse(localStorage.getItem(STORE)); } catch (e) { return null; } }
  function saveMe(v) { try { if (v) localStorage.setItem(STORE, JSON.stringify(v)); else localStorage.removeItem(STORE); } catch (e) { /* private mode: paired for this visit */ } }
  let me = loadMe();
  let keys = me ? keysOf(unb64(me.k)) : null;
  function nextCounter() {
    const stored = loadMe();
    // Another tab may have gone on: take the higher.
    if (stored && stored.d === me.d && stored.n > me.n) me.n = stored.n;
    me.n += 1;
    saveMe(me);
    return me.n;
  }

  class Offline extends Error {}
  class Unpaired extends Error {}
  const OFFLINE = 'Can’t reach the TV. The phone must be on the same Wi-Fi, with mobile data and any VPN on it off.';

  async function post(path, envelope, signal) {
    let r;
    try {
      r = await fetch('/api/v2/' + path, { method: 'POST', headers: { 'X-Phone': '1', 'Content-Type': 'application/json' }, body: JSON.stringify(envelope), signal, cache: 'no-store' });
    } catch (e) {
      if (e.name === 'AbortError') throw e;
      throw new Offline(OFFLINE);
    }
    let j = null;
    try { j = await r.json(); } catch (e) { /* no body */ }
    return { r, j };
  }

  /** One sealed request to the TV; the answer opened, or an error. */
  async function api(path, body, retried) {
    if (!me) throw new Unpaired();
    const n = nextCounter();
    const aad = 'POST /api/v2/' + path;
    const c = b64(seal(keys.up, 0, n, aad, enc.encode(JSON.stringify(body || {}))));
    const { r, j } = await post(path, { d: me.d, n, l: LANE, c });
    if (r.status === 401 && j) {
      if (j.error === 'counter' && !retried) {
        me.n = Math.max(me.n, j.next - 1);
        saveMe(me);
        return api(path, body, true);
      }
      // Only the TV's own "not paired" forgets the key; anything else may pass, and is tried again.
      if (j.error === 'pair') {
        // A request still out when this phone unpaired itself is refused too: that page stays.
        if (me) unpaired('removed');
        throw new Unpaired();
      }
      throw new Offline('The TV didn’t accept this phone’s request. Trying again…');
    }
    if (r.status === 429) throw new Offline('Too many wrong requests from this phone. Wait a few minutes.');
    if (!r.ok || !j || j.n !== n) throw new Offline(OFFLINE);
    const plain = unseal(keys.down, 1, n, aad, unb64(j.c));
    if (!plain) throw new Offline('The answer didn’t come from the TV this phone is paired with.');
    return JSON.parse(dec.decode(plain));
  }

  // ---- What the TV said last -----------------------------------------------------------------
  let S = null;
  let SCHEMA = null;
  let V = -1;
  let SEQ = -1;
  const LOG = { epoch: -1, next: 0, lines: [] };
  let online = true;
  let lastTest = null;
  const draft = new Map(); // path -> op
  const fieldErrors = new Map(); // path -> what the TV said is wrong with it
  let sheetWatch = null; // the change the sheet follows

  // What is typed into a form the page draws again on every push from the TV: kept by a name
  // until the form is done with, so a redraw refills it instead of emptying it.
  const typed = new Map();
  function keepTyped(input, key) {
    if (typed.has(key)) input.value = typed.get(key);
    input.addEventListener('input', () => typed.set(key, input.value));
    return input;
  }
  function forgetTyped(prefix) { [...typed.keys()].forEach((k) => { if (k.startsWith(prefix)) typed.delete(k); }); }

  function setOnline(on, why) {
    online = on;
    const l = $('live');
    l.hidden = false;
    l.className = 'live ' + (on ? 'on' : 'off');
    l.lastChild.textContent = on ? 'Live' : 'Offline';
    if (!on && why) showBanner(why); else hideBanner();
  }
  function showBanner(text) {
    let b = $('banner');
    if (!b) {
      b = el('div', 'info bad');
      b.id = 'banner';
      $('main').prepend(b);
    }
    b.textContent = text;
  }
  function hideBanner() { const b = $('banner'); if (b) b.remove(); }

  const profile = (id) => S && S.profiles.find((p) => p.id === id);
  const settingValue = (path) => {
    if (path.startsWith('settings.')) return S.settings[path.slice(9)];
    const [, id, name] = path.split('.');
    const p = profile(id);
    return p ? p.values[name] : undefined;
  };
  const valueOf = (path) => (draft.has(path) ? draft.get(path).value : settingValue(path));
  const schemaItem = (path) => {
    if (!SCHEMA) return null;
    if (path.startsWith('profiles.')) return SCHEMA.profile.find((i) => i.path === path.split('.')[2]);
    for (const g of SCHEMA.groups) for (const i of g.items) if (i.path === path) return i;
    return null;
  };
  function setDraft(path, value, extra) {
    fieldErrors.delete(path);
    const item = schemaItem(path);
    const from = settingValue(path);
    if (item && item.type !== 'secret' && same(value, from) && !extra) draft.delete(path);
    else draft.set(path, Object.assign({ op: 'set', path, value }, item && item.type === 'secret' ? {} : { from }, extra || {}));
    updateTray();
  }
  function updateTray() {
    const n = draft.size;
    $('tray').hidden = n === 0 || !S;
    $('trayText').textContent = n === 1 ? '1 change' : n + ' changes';
  }

  // ---- Pairing -------------------------------------------------------------------------------
  function guessName() {
    const ua = navigator.userAgent;
    if (/iPhone/.test(ua)) return 'iPhone';
    if (/iPad/.test(ua)) return 'iPad';
    const m = ua.match(/Android [\d.]+; ([^;)]+)/);
    if (m && m[1] && m[1] !== 'K') return m[1].trim();
    if (/Android/.test(ua)) return 'Android phone';
    if (/Mac OS X/.test(ua)) return 'Mac';
    if (/Windows/.test(ua)) return 'Windows PC';
    return 'Phone';
  }

  function pairView(offerId, secret) {
    const main = $('main');
    main.replaceChildren();
    $('tabs').hidden = true;
    $('tray').hidden = true;
    add(main, el('h1', null, 'Pair this phone with the TV'), el('p', 'lede', 'Then it can see and change everything on the TV from here: profiles, settings, the log. Only on your home network.'));
    const card = add(el('section', 'card'));
    const f = add(el('div', 'field'), el('label', null, 'This phone’s name'), el('span', 'help', 'The TV shows it when it asks, and in its list of phones.'));
    f.style.padding = '0';
    const input = el('input', 'input');
    input.id = 'pair-name';
    f.querySelector('label').htmlFor = 'pair-name';
    input.value = guessName();
    input.maxLength = 40;
    input.autocomplete = 'off';
    f.append(input);
    const status = el('div', 'info');
    status.hidden = true;
    const go = el('button', 'btn mint', 'Pair');
    go.type = 'button';
    add(card, f, add(el('div', 'actions'), go), status);
    main.append(card);
    add(main, el('p', 'foot', 'The code in the link works once, for five minutes. It never leaves this phone.'));
    const k = keysOf(secret, 'pair');
    const claim = b64(crypto.getRandomValues(new Uint8Array(16)));
    let n = 0;
    go.onclick = async () => {
      go.disabled = true;
      input.disabled = true;
      status.hidden = false;
      status.className = 'info';
      status.replaceChildren(svg(ICON.spin), document.createTextNode(' Asking the TV…'));
      const name = input.value.trim() || guessName();
      for (let tries = 0; tries < 12; tries++) {
        n += 1;
        const aad = 'POST /api/v2/pair';
        const c = b64(seal(k.up, 0, n, aad, enc.encode(JSON.stringify({ name, claim }))));
        let res;
        try {
          res = await post('pair', { p: offerId, n, l: LANE, c });
        } catch (e) {
          status.className = 'info bad';
          status.textContent = OFFLINE;
          go.disabled = false;
          input.disabled = false;
          return;
        }
        if (res.r.status === 401) {
          status.className = 'info bad';
          status.textContent = 'This code was used already, or it ran out. On the TV, open Settings › Phone access › Pair a phone and scan the new code.';
          return;
        }
        const plain = res.j && res.j.c ? unseal(k.down, 1, n, aad, unb64(res.j.c)) : null;
        const a = plain ? JSON.parse(dec.decode(plain)) : null;
        if (!a) {
          status.className = 'info bad';
          status.textContent = 'The TV’s answer couldn’t be read. Scan the code again.';
          return;
        }
        if (a.state === 'asking') {
          status.replaceChildren(svg(ICON.spin), document.createTextNode(' Press Pair on the TV.'));
          continue;
        }
        if (a.state === 'paired') {
          me = { d: a.device, k: a.key, n: 0, name: a.name, tv: a.tv };
          saveMe(me);
          keys = keysOf(unb64(me.k));
          // The secret goes from the address bar and the history.
          history.replaceState(null, '', location.pathname + '#/status');
          toast('Paired with the TV');
          start();
          return;
        }
        status.className = 'info bad';
        status.textContent = a.text || 'Not paired.';
        return;
      }
      status.className = 'info bad';
      status.textContent = 'The TV didn’t answer. Scan the code again.';
    };
  }

  function unpaired(why) {
    saveMe(null);
    me = null;
    keys = null;
    running = false;
    S = null;
    draft.clear();
    const main = $('main');
    main.replaceChildren();
    $('tabs').hidden = true;
    $('tray').hidden = true;
    $('live').hidden = true;
    add(main, el('h1', null, why === 'new' ? 'Pair this phone first' : why === 'self' ? 'This phone is unpaired' : 'This phone isn’t paired any more'),
      el('p', 'lede', why === 'removed' ? 'It was removed on the TV, or it wasn’t used for 90 days.' : why === 'self' ? 'You unpaired it. Pair it again to use the panel.' : 'The panel works on phones paired with the TV.'));
    add(main, add(el('section', 'card'), el('h2', null, 'To pair it'),
      add(el('ol', 'lede'), el('li', null, 'On the TV, open ' + APP + ' › Settings › Phone access.'), el('li', null, 'Choose Pair a phone.'), el('li', null, 'Scan the code with this phone’s camera.'))));
  }

  // ---- Talking to the TV ---------------------------------------------------------------------
  let running = false;
  async function loop() {
    let wait = 1000;
    while (running) {
      try {
        const r = await api('events', { after: SEQ, v: V, log: [LOG.epoch, LOG.next] });
        const cameBack = !online;
        if (!online) setOnline(true);
        wait = 1000;
        V = r.v;
        // The TV's app started again: its events count from the start, and what it was doing is gone.
        const restarted = SEQ >= 0 && r.seq < SEQ;
        const prevSeq = restarted ? -1 : SEQ;
        SEQ = r.seq;
        if (sheetWatch && (cameBack || restarted || Date.now() - sheetWatch.seen > 90000)) checkWatch();
        if (r.state) {
          S = r.state;
          draw();
        }
        if (prevSeq >= 0) r.events.forEach(onEvent);
        onLog(r.log);
        if (r.bytes) onBytes(r.bytes);
      } catch (e) {
        if (e instanceof Unpaired) return;
        setOnline(false, e.message);
        await sleep(wait);
        // At most 5 s apart: on the home network a try costs nothing, and Live comes back sooner.
        wait = Math.min(wait * 2, 5000);
      }
    }
  }

  async function start() {
    running = true;
    $('tabs').hidden = false;
    setOnline(true);
    try {
      const r = await api('schema', {});
      SCHEMA = r.schema;
    } catch (e) {
      if (e instanceof Unpaired) return;
    }
    loop();
  }

  async function act(action, extra) {
    try {
      const r = await api('act', Object.assign({ action }, extra || {}));
      if (!r.ok && r.text) toast(r.text, true);
      return r;
    } catch (e) {
      if (!(e instanceof Unpaired)) toast(e.message, true);
      return { ok: false };
    }
  }

  // ---- Drawing -------------------------------------------------------------------------------
  let pendingDraw = false;
  const route = () => (location.hash.replace(/^#\/?/, '') || 'status').split('/');

  /** Draws the page again, unless something is being typed: then once typing stops. */
  function draw(force) {
    if (!S) return;
    const a = document.activeElement;
    if (!force && a && $('main').contains(a) && /INPUT|TEXTAREA|SELECT/.test(a.tagName)) {
      pendingDraw = true;
      return;
    }
    pendingDraw = false;
    $('app').textContent = S.tv.app;
    $('tv').textContent = 'On ' + S.tv.name + (me && me.name ? ' · ' + me.name : '');
    document.title = S.tv.app;
    const [tab, arg] = route();
    document.querySelectorAll('.tabs button').forEach((b) => b.setAttribute('aria-current', b.dataset.tab === (tab === 'add' && arg === 'restore' ? 'settings' : tab === 'p' || tab === 'add' ? 'profiles' : tab) ? 'page' : 'false'));
    const main = $('main');
    const y = window.scrollY;
    main.replaceChildren();
    if (!online) showBanner(OFFLINE);
    if (tab === 'profiles') profilesView(main);
    else if (tab === 'p') profileView(main, arg);
    else if (tab === 'add') addView(main, arg);
    else if (tab === 'settings') settingsView(main);
    else if (tab === 'log') logView(main);
    else statusView(main);
    updateTray();
    window.scrollTo(0, y);
  }
  document.addEventListener('focusout', () => setTimeout(() => { if (pendingDraw) draw(); }, 150));
  // A field typed in near the bottom stays clear of the tray of changes.
  document.addEventListener('focusin', (e) => {
    if (!/INPUT|TEXTAREA|SELECT/.test(e.target.tagName) || $('tray').hidden) return;
    setTimeout(() => e.target.scrollIntoView({ block: 'center' }), 250);
  });
  window.addEventListener('hashchange', () => { window.scrollTo(0, 0); draw(true); });
  document.querySelectorAll('.tabs button').forEach((b) => { b.onclick = () => { location.hash = '#/' + b.dataset.tab; }; });

  function tag(kind, label) { return el('span', 'tag ' + (kind === 'wireguard' ? 'wg' : 'ovpn'), label); }
  function stateChip(tone, word) {
    const s = el('span', 'state ' + ({ on: 'on', busy: 'busy', bad: 'bad' }[tone] || ''));
    s.append(el('i'), document.createTextNode(word));
    return s;
  }
  function button(text, cls, onclick, icon) {
    const b = el('button', 'btn ' + (cls || ''));
    b.type = 'button';
    if (icon) b.append(svg(icon));
    b.append(document.createTextNode(text));
    b.onclick = async () => {
      b.disabled = true;
      // Buttons of a change that is under way stay off until it ends (data-busy on their row).
      try { await onclick(); } finally { if (!b.closest('[data-busy]')) b.disabled = false; }
    };
    return b;
  }
  function fact(label, value, wide, id, plain) {
    const d = el('div', wide ? 'wide' : null);
    const dd = el('dd', plain ? 'plain' : null, value);
    if (id) dd.id = id;
    add(d, el('dt', null, label), dd);
    return d;
  }

  // ---- Status --------------------------------------------------------------------------------
  function heroWords(t, p) {
    switch (t.status) {
      case 'connected': return ['on', 'Connected', 'Connected · ' + hms(Date.now() - t.since)];
      case 'connecting': return ['busy', 'Connecting…', p && p.server ? 'Reaching ' + p.server.split(' · ')[0] : 'Reaching the server'];
      case 'paused': return ['busy', 'Paused', 'Paused until ' + clock(t.pausedUntil) + '. Traffic goes directly to the internet until then.'];
      case 'blocked': return ['bad', 'Blocking traffic', 'The tunnel dropped. The kill switch holds traffic back until it is back.'];
      case 'failed': return ['bad', 'Couldn’t connect', [t.error, t.advice && !(t.error || '').includes(t.advice) ? t.advice + '.' : ''].filter(Boolean).join(' ')];
      default: return ['', 'Not connected', p ? p.status.line : ''];
    }
  }

  function statusView(main) {
    const t = S.tunnel;
    const p = profile(t.profile) || profile(S.active);
    if (!p) {
      add(main, el('h1', null, 'No profiles yet'), el('p', 'lede', 'Add one from this phone: a .ovpn or WireGuard .conf file, a QR code picture, or pasted text.'));
      main.append(add(el('div', 'actions'), button('Add a profile', 'mint', () => { location.hash = '#/add'; }, ICON.plus)));
      return;
    }
    const [tone, word, why] = heroWords(t, p);
    const live = t.status === 'connected' || t.status === 'connecting' || t.status === 'blocked';
    const hero = add(el('section', 'card hero'),
      add(el('div', 'row1'), tag(p.kind, p.kindLabel), stateChip(tone, word)),
      el('h1', null, p.name));
    const w = el('p', 'why' + (tone === 'bad' ? ' bad' : ''), why);
    w.id = 'why';
    hero.append(w);
    const facts = el('dl', 'facts');
    if (t.status === 'connected') {
      add(facts, fact('VPN IP', t.ip || 'Looking it up…'), fact('Tunnel IP', t.address || '–'),
        fact('Server', p.server || '–', true), fact('Traffic', '–', true, 'bytes'));
    } else {
      add(facts, fact('Server', p.server || '–', true), fact('Sign-in', p.signIn.summary, true, null, true));
    }
    hero.append(facts);
    const actions = el('div', 'actions');
    // The TV's buttons for each state, in its words.
    if (t.status === 'paused') {
      add(actions, button('Resume now', 'mint', () => act('resume'), ICON.power),
        add(el('div', 'pair'), button('15 more minutes', 'ghost', () => act('pauseMore')), button('Disconnect', 'ghost', () => act('disconnect'))));
    } else if (t.status === 'connecting') {
      add(actions, button('Cancel', '', () => act('disconnect'), ICON.power));
    } else if (live) {
      add(actions, button('Disconnect', '', () => act('disconnect'), ICON.power),
        add(el('div', 'pair'), button('Pause 15 minutes', 'ghost', () => act('pause')), button('Test', 'ghost', runTest)));
    } else {
      add(actions, button(t.status === 'failed' ? 'Try again' : 'Connect', 'mint', () => connect(p.id), ICON.power));
    }
    hero.append(actions);
    main.append(hero);
    if (!S.vpnAllowed && !live) main.append(el('p', 'info warn', 'Android asks once before the TV can connect. Open ' + S.tv.app + ' on the TV and connect there the first time.'));
    if (lastTest) main.append(testCard(lastTest));
    const others = S.profiles.filter((o) => o.id !== p.id);
    if (others.length) {
      main.append(el('div', 'section', 'Other profiles'));
      const list = el('section', 'card tight');
      const rows = el('div', 'rows');
      others.forEach((o) => {
        const r = el('div', 'row');
        const txt = add(el('div', 'txt'), add(el('div', 'meta'), tag(o.kind, o.kindLabel)), el('b', null, o.name), el('small', null, o.status.line));
        r.append(txt);
        r.append(button('Connect', 'ghost small', () => connect(o.id)));
        txt.onclick = () => { location.hash = '#/p/' + o.id; };
        rows.append(r);
      });
      main.append(add(list, rows));
    }
    main.append(el('p', 'foot', 'Changes from here show on the TV, and the TV asks before anything risky.'));
    if (lastBytes) onBytes(lastBytes);
  }

  let lastBytes = null;
  function onBytes(b) {
    lastBytes = b;
    const e = $('bytes');
    if (e) e.textContent = '↓ ' + fmtBytes(b[0]) + '   ↑ ' + fmtBytes(b[1]);
    tick();
  }
  function tick() {
    const w = $('why');
    if (w && S && S.tunnel.status === 'connected' && route()[0] === 'status') w.textContent = 'Connected · ' + hms(Date.now() - S.tunnel.since);
  }
  setInterval(tick, 1000);

  async function runTest() {
    lastTest = { running: true };
    draw(true);
    const r = await act('test');
    lastTest = r.ok ? { checks: r.checks, ipv6: r.ipv6, at: Date.now() } : null;
    draw(true);
  }

  function checkRow(c) {
    const d = el('span', 'dot ' + (c.result === 'ok' ? 'ok' : c.result === 'warn' ? 'warn' : 'fail'));
    d.append(svg(c.result === 'ok' ? ICON.check : ICON.bang));
    return add(el('div', 'check'), d, add(el('div'), el('b', null, c.title), el('small', null, c.detail)));
  }

  function testCard(t) {
    const card = add(el('section', 'card'), el('h2', null, 'Connection test'));
    if (t.running) {
      card.append(add(el('div', 'progress'), svg(ICON.spin), document.createTextNode('Checking the tunnel, DNS and the internet…')));
      return card;
    }
    t.checks.forEach((c) => card.append(checkRow(c)));
    card.append(el('p', 'lede', t.ipv6 ? 'Block IPv6 is on: IPv6 can’t leave outside the tunnel.' : 'Block IPv6 is off: IPv6 may leave outside the tunnel.'));
    return card;
  }

  async function connect(id, login) {
    const r = await act('connect', Object.assign({ profile: id }, login ? { login } : {}));
    if (r.needs === 'login') loginSheet(id, r.text);
    else if (r.ok && login) closeSheet();
    return r;
  }

  function loginSheet(id, text) {
    const p = profile(id);
    const s = openSheet();
    add(s, el('h2', null, 'Sign in to ' + (p ? p.name : 'the profile')), el('p', 'lede', text || 'Type the username and password.'));
    const user = el('input', 'input');
    user.placeholder = 'Username';
    user.autocomplete = 'username';
    user.value = (p && p.values.username) || '';
    const pw = el('input', 'input');
    pw.type = 'password';
    pw.placeholder = 'Password';
    pw.autocomplete = 'off';
    const keep = el('input', 'sw');
    keep.type = 'checkbox';
    // Saving a password is a change: with the PIN lock on, it is used this once only.
    keep.checked = !S.lock.locks;
    const keepRow = add(el('label', 'pick'), add(el('span', 'txt'), el('b', null, 'Remember on the TV'), el('small', null, 'Then it connects without asking next time')), keep);
    keepRow.querySelector('input').style.display = 'block';
    if (S.lock.locks) keepRow.hidden = true;
    add(s, user, pw, keepRow, add(el('div', 'actions'),
      button('Connect', 'mint', () => connect(id, { username: user.value, password: pw.value, remember: keep.checked }), ICON.power),
      button('Cancel', 'ghost', closeSheet)));
    user.focus();
  }

  // ---- Profiles ------------------------------------------------------------------------------
  function profilesView(main) {
    add(main, el('h1', null, 'Profiles'), el('p', 'lede', S.profiles.length ? 'Tap one to change it. Changes wait until you review them.' : 'Nothing here yet.'));
    main.append(add(el('div', 'actions'), button('Add a profile', 'ghost', () => { location.hash = '#/add'; }, ICON.plus)));
    if (!S.profiles.length) return;
    const rows = el('div', 'rows');
    S.profiles.forEach((p) => {
      const r = el('button', 'row' + ([...draft.keys()].some((k) => k.startsWith('profiles.' + p.id)) ? ' changed' : ''));
      r.type = 'button';
      add(r, add(el('div', 'txt'), add(el('div', 'meta'), tag(p.kind, p.kindLabel), stateChip(p.status.tone, p.status.word)), el('b', null, p.name),
        el('small', null, [p.server, p.signIn.summary].filter(Boolean).join(' · '))), el('span', 'chev'));
      r.onclick = () => { location.hash = '#/p/' + p.id; };
      rows.append(r);
    });
    main.append(add(el('section', 'card tight'), rows));
  }

  function profileView(main, id) {
    const p = profile(id);
    const back = el('button', 'link', '‹ Profiles');
    back.onclick = () => { location.hash = '#/profiles'; };
    main.append(back);
    if (!p) {
      add(main, el('h1', null, 'That profile is gone'), el('p', 'lede', 'It was deleted on the TV.'));
      return;
    }
    const t = S.tunnel;
    const mine = t.profile === p.id && t.status !== 'disconnected';
    const head = add(el('section', 'card hero'), add(el('div', 'row1'), tag(p.kind, p.kindLabel), stateChip(p.status.tone, p.status.word)), el('h1', null, p.name),
      el('p', 'why' + (p.status.tone === 'bad' ? ' bad' : ''), p.status.detail || p.server));
    const live = mine && (t.status === 'connected' || t.status === 'connecting' || t.status === 'blocked');
    head.append(add(el('div', 'actions'), live ? button('Disconnect', '', () => act('disconnect'), ICON.power) : button('Connect', 'mint', () => connect(p.id), ICON.power)));
    main.append(head);
    main.append(el('div', 'section', 'Options'));
    const card = el('section', 'card tight');
    p.items.forEach((name) => {
      const item = SCHEMA && SCHEMA.profile.find((i) => i.path === name);
      if (item) card.append(field('profiles.' + p.id + '.' + name, item));
    });
    main.append(card);
    if (p.publicKey) {
      main.append(el('div', 'section', 'This device’s public key'));
      main.append(add(el('section', 'card'), keyBlock(p.publicKey)));
    }
    main.append(el('div', 'section', 'More'));
    const more = el('section', 'card tight');
    const rows = el('div', 'rows');
    const dl = el('button', 'row');
    add(dl, el('span', 'ico'), add(el('div', 'txt'), el('b', null, 'Download its file'), el('small', null, p.kind === 'wireguard' ? 'The .conf with its key. The TV asks first' : 'The .ovpn, without the saved password. The TV asks first')));
    dl.querySelector('.ico').append(svg(ICON.doc));
    dl.onclick = () => download({ what: 'profile', profile: p.id });
    const del = el('button', 'row');
    add(del, add(el('div', 'txt'), el('b', null, 'Delete this profile'), el('small', null, p.signIn.summary.startsWith('Password saved') ? 'It goes with its saved password. The TV asks first' : 'The TV asks first')));
    del.querySelector('b').style.color = 'var(--red)';
    del.onclick = () => review([{ op: 'delete', path: 'profiles.' + p.id }], { title: 'Delete ' + p.name + '?', done: () => { location.hash = '#/profiles'; toast('Deleted ' + p.name); } });
    add(rows, dl, del);
    main.append(add(more, rows));
  }

  /** This device's WireGuard public key, whole, with Copy: what the server's peer list needs. */
  function keyBlock(publicKey) {
    const copy = el('button', 'link', 'Copy');
    copy.onclick = () => copyText(publicKey);
    return add(el('div', 'keyblock'), el('p', 'lede', 'The WireGuard server needs this device’s public key in its peer list.'), el('div', 'key', publicKey), copy);
  }

  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
      toast('Copied');
    } catch (e) {
      // Clipboard needs HTTPS; select it instead.
      const ta = el('textarea', 'input');
      ta.value = text;
      document.body.append(ta);
      ta.select();
      let ok = false;
      try { ok = document.execCommand('copy'); } catch (x) { /* not allowed */ }
      if (ok) toast('Copied'); else toast('Select it and copy it by hand', true);
      ta.remove();
    }
  }

  // ---- One setting or option, drawn from the schema ------------------------------------------
  function field(path, item) {
    const f = el('div', 'field' + (draft.has(path) ? ' changed' : ''));
    const value = valueOf(path);
    const id = 'f-' + path.replace(/\W/g, '-');
    const label = el('label', null, item.label);
    label.htmlFor = id;
    const help = el('span', 'help', item.help);
    const head = add(el('div', 'head'), add(el('div', 'txt'), label, help));
    f.append(head);
    const options = item.path === 'settings.startup'
      ? [{ value: '', label: 'Off' }].concat(S.profiles.map((p) => ({ value: p.id, label: p.name })))
      : (item.options || []);
    switch (item.type) {
      case 'switch': {
        const sw = el('input', 'sw');
        sw.type = 'checkbox';
        sw.id = id;
        sw.checked = value === true;
        sw.onchange = () => { setDraft(path, sw.checked); draw(true); };
        head.append(sw);
        break;
      }
      case 'choice': {
        const sel = el('select', 'input');
        sel.id = id;
        options.forEach((o) => { const op = el('option', null, o.label); op.value = o.value; sel.append(op); });
        sel.value = value == null ? '' : String(value);
        const sub = options.find((o) => o.value === sel.value);
        if (sub && sub.help) help.textContent = sub.help;
        sel.onchange = () => { setDraft(path, sel.value); draw(true); };
        f.append(sel);
        if (path === 'settings.pinMode' && sel.value !== 'Off' && !S.lock.pin) {
          const pin = el('input', 'input');
          pin.type = 'password';
          pin.inputMode = 'numeric';
          pin.placeholder = 'Choose a PIN: 4 to 8 digits';
          pin.maxLength = 8;
          const d = draft.get('lock.pin');
          if (d) pin.value = d.value;
          pin.oninput = () => { if (pin.value) setDraft('lock.pin', pin.value); else { draft.delete('lock.pin'); updateTray(); } };
          f.append(pin);
        }
        break;
      }
      case 'lines': {
        const ta = el('textarea', 'input');
        ta.id = id;
        ta.value = (value || []).join('\n');
        ta.placeholder = 'One on each line';
        ta.spellcheck = false;
        ta.autocapitalize = 'off';
        ta.oninput = () => setDraft(path, ta.value.split(/[\s,]+/).filter(Boolean));
        f.append(ta);
        break;
      }
      case 'text':
      case 'number': {
        const i = el('input', 'input');
        i.id = id;
        i.value = value == null ? '' : String(value);
        if (item.type === 'number') i.inputMode = 'numeric';
        i.autocapitalize = 'off';
        i.spellcheck = false;
        i.oninput = () => setDraft(path, item.type === 'number' ? (/^\d+$/.test(i.value) ? Number(i.value) : i.value) : i.value);
        f.append(i);
        break;
      }
      case 'secret': {
        const i = el('input', 'input');
        i.id = id;
        i.type = 'password';
        i.autocomplete = 'new-password';
        i.placeholder = value ? 'Saved on the TV. Type a new one to change it' : 'Not saved';
        const d = draft.get(path);
        if (d && d.value) i.value = d.value;
        i.oninput = () => { if (i.value) setDraft(path, i.value); else { draft.delete(path); updateTray(); } };
        f.append(i);
        if (value) {
          const forget = el('button', 'link', d && d.value === null ? 'Keep the saved one after all' : 'Forget the saved one');
          forget.onclick = () => { if (d && d.value === null) draft.delete(path); else setDraft(path, null); draw(true); };
          f.append(forget);
        }
        break;
      }
      case 'set': {
        const chosen = new Set(value || []);
        const box = el('div', 'checks');
        if (!options.length) box.append(el('span', 'help', 'No apps were found on the TV.'));
        options.forEach((o) => {
          const c = el('input');
          c.type = 'checkbox';
          c.checked = chosen.has(o.value);
          c.onchange = () => {
            if (c.checked) chosen.add(o.value); else chosen.delete(o.value);
            setDraft(path, [...chosen].sort());
          };
          box.append(add(el('label'), c, document.createTextNode(o.label)));
        });
        f.append(box);
        break;
      }
      default: break;
    }
    const bad = fieldErrors.get(path);
    if (bad) {
      f.classList.add('bad');
      f.append(el('p', 'err', bad));
    }
    return f;
  }

  // ---- Settings ------------------------------------------------------------------------------
  function settingsView(main) {
    add(main, el('h1', null, 'Settings'), el('p', 'lede', 'The same settings as on the TV. Change what you like, then review it.'));
    if (!SCHEMA) {
      main.append(el('p', 'info', 'Loading the settings…'));
      return;
    }
    const advanced = valueOf('settings.advanced') === true;
    SCHEMA.groups.forEach((g) => {
      if (g.advanced && !advanced) return;
      const items = g.items.filter((i) => advanced || !i.advanced);
      if (!items.length) return;
      main.append(el('div', 'section', g.label === 'General' ? 'General' : g.label));
      const card = el('section', 'card tight');
      items.forEach((i) => card.append(field(i.path, i)));
      if (g.id === 'phones') card.append(phonesBlock());
      main.append(card);
    });
    main.append(el('div', 'section', 'Backup'));
    main.append(backupCard());
    main.append(el('div', 'section', 'About'));
    main.append(add(el('section', 'card'), el('p', 'lede', S.tv.app + ' ' + S.tv.version + ' · build ' + S.tv.build + ' on ' + S.tv.name + '.'),
      el('p', 'lede', 'Updates, Always-on VPN and installing certificates from Android’s keystore are done on the TV.')));
  }

  function phonesBlock() {
    const box = el('div', 'rows');
    S.devices.forEach((d) => {
      const r = el('div', 'row');
      r.append(el('span', 'ico'));
      r.querySelector('.ico').append(svg(ICON.phone));
      add(r, add(el('div', 'txt'), el('b', null, d.name + (d.you ? ' (this phone)' : '')), el('small', null, 'Paired ' + ago(d.paired) + ' · last seen ' + ago(d.seen))));
      r.append(button(d.you ? 'Unpair' : 'Remove', 'ghost small', () => removeDevice(d)));
      box.append(r);
    });
    return box;
  }

  async function removeDevice(d) {
    let pin = '';
    if (!d.you && S.lock.locks) {
      pin = await askPin('Type it to remove ' + d.name + '.');
      if (pin == null) return;
    }
    if (d.you && !(await confirmSheet('Unpair this phone?', 'It can’t reach the TV until it is paired again.', 'Unpair'))) return;
    if (!d.you) toast('Press Remove on the TV');
    try {
      const r = await api('devices/remove', { id: d.id, pin });
      if (!r.ok) return toast(r.text, true);
      if (d.you) unpaired('self');
      else toast(d.name + ' was removed');
    } catch (e) { if (!(e instanceof Unpaired)) toast(e.message, true); }
  }

  function backupCard() {
    const card = el('section', 'card');
    card.append(el('p', 'lede', S.backup.last + '. Backups are encrypted with your passphrase and hold every profile, saved password and setting.'));
    const pass = keepTyped(el('input', 'input'), 'backup:new');
    pass.type = 'password';
    pass.autocomplete = 'new-password';
    pass.placeholder = S.backup.passphrase ? 'A new passphrase' : 'A passphrase: 6 characters or more';
    const again = keepTyped(el('input', 'input'), 'backup:again');
    again.type = 'password';
    again.autocomplete = 'new-password';
    again.placeholder = 'The same again';
    const current = keepTyped(el('input', 'input'), 'backup:current');
    current.type = 'password';
    current.autocomplete = 'off';
    current.placeholder = 'The current passphrase';
    const err = el('p', 'err');
    err.hidden = true;
    // The TV's words for the same thing.
    const set = button(S.backup.passphrase ? 'Change the backup passphrase' : 'Choose a backup passphrase', 'ghost', async () => {
      err.hidden = true;
      if (pass.value.length < 6) { err.hidden = false; err.textContent = 'At least 6 characters'; return; }
      if (pass.value !== again.value) { err.hidden = false; err.textContent = 'Not the same as the first. Type it again'; return; }
      await review([Object.assign({ op: 'set', path: 'backup.passphrase', value: pass.value }, S.backup.passphrase ? { current: current.value } : {})], { done: () => forgetTyped('backup:') });
    });
    add(card, S.backup.passphrase ? current : null, pass, again, err, add(el('div', 'actions'),
      S.backup.passphrase ? button('Download a backup', '', () => download({ what: 'backup' })) : null,
      set,
      S.backup.passphrase ? button('Remove the passphrase', 'danger', () => review([{ op: 'set', path: 'backup.passphrase', value: null }])) : null,
      button('Restore a backup', 'ghost', () => { location.hash = '#/add/restore'; })));
    return card;
  }

  // ---- Add a profile, restore, certificates ---------------------------------------------------
  let arrived = null; // what the TV made of the last file sent

  function addView(main, from) {
    // Opened from Settings › Backup it is the restore page, and goes back there.
    const restore = from === 'restore';
    const back = el('button', 'link', restore ? '‹ Settings' : '‹ Profiles');
    back.onclick = () => { arrived = null; forgetTyped('add:'); location.hash = restore ? '#/settings' : '#/profiles'; };
    add(main, back, el('h1', null, restore ? 'Restore a backup' : 'Add a profile'), el('p', 'lede', restore
      ? 'Choose the backup file. Type its passphrase next; you see what it adds and changes before anything happens.'
      : 'A .ovpn or WireGuard .conf, a picture of a WireGuard QR code, a .zip, a backup, or a .p12 certificate. The TV works out what it is.'));
    if (arrived) {
      main.append(arrivedCard(arrived));
      return;
    }
    const card = el('section', 'card');
    const input = el('input');
    input.type = 'file';
    const pick = add(el('label', 'pick'), el('span', 'ico'), add(el('span', 'txt'), el('b', null, 'Choose a file'), el('small', null, 'From Files, Downloads or Photos')), input);
    pick.querySelector('.ico').append(svg(ICON.doc));
    input.onchange = () => { if (input.files[0]) sendFile(input.files[0]); };
    const ta = el('textarea', 'input paste');
    ta.placeholder = 'Or paste the profile’s text here';
    ta.spellcheck = false;
    ta.autocapitalize = 'off';
    add(card, pick, ta, add(el('div', 'actions'), button('Send the text', 'ghost', () => {
      if (!ta.value.trim()) return toast('Paste the profile first', true);
      return upload('pasted.ovpn', enc.encode(ta.value));
    })));
    main.append(card);
  }

  /** A photo is made smaller first: a QR code needs far fewer pixels than a camera takes. */
  async function shrink(file) {
    if (!/^image\//.test(file.type) || file.size < 400 * 1024) return new Uint8Array(await file.arrayBuffer());
    const url = URL.createObjectURL(file);
    try {
      const img = await new Promise((ok, no) => { const i = new Image(); i.onload = () => ok(i); i.onerror = no; i.src = url; });
      const scale = Math.min(1, 1600 / Math.max(img.width, img.height));
      const c = document.createElement('canvas');
      c.width = Math.round(img.width * scale);
      c.height = Math.round(img.height * scale);
      c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
      const blob = await new Promise((ok) => c.toBlob(ok, 'image/jpeg', 0.9));
      return new Uint8Array(await blob.arrayBuffer());
    } finally { URL.revokeObjectURL(url); }
  }

  async function sendFile(file) {
    if (file.size > 8 * 1024 * 1024) return toast('That file is too large. A profile, backup or certificate is much smaller.', true);
    const bytes = await shrink(file);
    if (bytes.length > 512 * 1024) return toast('That file is too large. A profile, backup or certificate is much smaller.', true);
    return upload(file.name, bytes);
  }

  async function upload(name, bytes) {
    toast('Sending ' + (name === 'pasted.ovpn' ? 'the text' : name) + '…');
    try {
      const r = await api('files', { name, data: b64(bytes) });
      if (!r.ok) return toast(r.text || 'The TV couldn’t read that.', true);
      arrived = r;
      if (!location.hash.startsWith('#/add')) location.hash = '#/add'; else draw(true);
    } catch (e) { if (!(e instanceof Unpaired)) toast(e.message, true); }
  }

  function arrivedCard(a) {
    const card = el('section', 'card');
    const again = el('button', 'link', 'Send something else');
    again.onclick = () => { arrived = null; forgetTyped('add:'); draw(true); };
    // Typed text survives the page being drawn again, until this file is done with.
    const k = 'add:' + a.file + ':';
    const finished = () => { arrived = null; forgetTyped('add:'); };
    if (a.what === 'profile') {
      const rv = a.review;
      add(card, add(el('div', 'meta'), tag(rv.kind, rv.kindLabel)), el('h2', null, rv.name));
      // Named after its server, the name says the address already: the line under it adds the rest.
      const rest = rv.server.startsWith(rv.name + ':') ? 'Port ' + rv.server.slice(rv.name.length + 1) : rv.server;
      add(card, el('p', 'lede', rest), rv.note ? el('p', 'lede', rv.note) : null);
      if (rv.publicKey) card.append(keyBlock(rv.publicKey));
      const name = el('input', 'input');
      name.value = rv.name;
      name.maxLength = 60;
      keepTyped(name, k + 'name');
      add(card, add(el('div', 'field'), el('label', null, 'Name'), el('span', 'help', 'Shown on the TV’s cards and on Connect.'), name));
      card.lastChild.style.padding = '12px 0 0';
      let user = null;
      let pw = null;
      if (rv.needsLogin) {
        user = el('input', 'input');
        user.placeholder = 'Username';
        user.value = rv.username || '';
        user.autocomplete = 'off';
        keepTyped(user, k + 'user');
        pw = el('input', 'input');
        pw.type = 'password';
        pw.placeholder = rv.hasPassword ? 'Password (in the file)' : 'Password';
        // The VPN's password, not this page's: the browser shouldn't offer to save it.
        pw.autocomplete = 'off';
        keepTyped(pw, k + 'pw');
        const f = add(el('div', 'field'), el('label', null, 'Sign-in'), el('span', 'help', 'Saved on the TV, encrypted. Leave them empty to type them on the TV when it connects.'), user, pw);
        f.style.padding = '12px 0 0';
        card.append(f);
      }
      if (!rv.canSignIn) card.append(el('p', 'info warn', 'This profile has no way to sign in: no password, certificate or key. It may not connect.'));
      rv.warnings.forEach((w) => card.append(el('p', 'info warn', w)));
      const ops = (test) => [{ op: 'import', file: a.file, name: name.value.trim(), username: user ? user.value.trim() : '', password: pw ? pw.value : '' }];
      const acts = el('div', 'actions');
      if (a.same) {
        card.append(el('p', 'info', a.same.name + ' is for the same server. Update it with this file, or add this as a new profile.'));
        acts.append(button('Update ' + a.same.name, 'mint', () => review([{ op: 'update', file: a.file, path: 'profiles.' + a.same.id }], { done: finished })));
      }
      add(acts, button(a.same ? 'Add as a new profile' : 'Add it', a.same ? 'ghost' : 'mint', () => review(ops(), { title: 'Add ' + (name.value.trim() || rv.name) + '?', publicKey: rv.publicKey, done: () => { finished(); location.hash = '#/profiles'; } })));
      add(card, acts, again);
      return card;
    }
    if (a.what === 'backup') {
      add(card, el('h2', null, 'A backup: ' + a.name), el('p', 'lede', 'Type its passphrase. You see what it adds and changes before anything happens.'));
      const pass = keepTyped(el('input', 'input'), k + 'pass');
      pass.type = 'password';
      pass.placeholder = 'The backup’s passphrase';
      // The narrower choice first: settings come along only when asked for.
      const what = segmented([['profiles', 'Profiles only'], ['all', 'Profiles and settings']], 'profiles', k + 'what');
      const sameName = segmented([['both', 'Keep both'], ['replace', 'Replace it']], 'both', k + 'same');
      add(card, pass, el('span', 'help', 'What to restore'), what.el, el('span', 'help', 'When a profile with the same name is on the TV'), sameName.el,
        add(el('div', 'actions'), button('Check the backup', 'mint', () => review([{ op: 'restore', file: a.file, passphrase: pass.value, settings: what.value() === 'all', replace: sameName.value() === 'replace' }], { title: 'Restore this backup?', done: () => { finished(); location.hash = '#/profiles'; } }))), again);
      card.querySelectorAll('.help').forEach((h) => { h.style.margin = '14px 0 8px'; h.style.display = 'block'; });
      return card;
    }
    if (a.what === 'p12') {
      add(card, el('h2', null, 'A certificate: ' + a.name), el('p', 'lede', 'Type the password it was exported with. The TV finds the profile it belongs to.'));
      const pass = keepTyped(el('input', 'input'), k + 'pass');
      pass.type = 'password';
      pass.placeholder = 'The certificate’s password';
      const sel = el('select', 'input');
      [{ id: '', name: 'The profile it belongs to' }].concat(a.targets).forEach((t) => { const o = el('option', null, t.name); o.value = t.id; sel.append(o); });
      keepTyped(sel, k + 'profile');
      const keep = segmented([['file', 'In the profile'], ['keystore', 'In Android’s keystore']], 'file', k + 'keep');
      add(card, pass, sel, el('span', 'help', 'Where the key is kept'), keep.el, add(el('div', 'actions'), button('Check it', 'mint', () => review([{ op: 'certificate', file: a.file, password: pass.value, path: sel.value ? 'profiles.' + sel.value : '', keep: keep.value() }], { done: () => { finished(); location.hash = '#/profiles'; } }))), again);
      return card;
    }
    add(card, el('p', 'info bad', a.text || 'The TV couldn’t read that.'), again);
    return card;
  }

  /** Buttons for one of [options]; with [key], the choice survives the page being drawn again. */
  function segmented(options, initial, key) {
    let v = key && typed.has(key) ? typed.get(key) : initial;
    const box = el('div', 'seg');
    options.forEach(([value, label]) => {
      const b = el('button', null, label);
      b.type = 'button';
      b.setAttribute('aria-pressed', value === v);
      b.onclick = () => {
        v = value;
        if (key) typed.set(key, v);
        box.querySelectorAll('button').forEach((x, i) => x.setAttribute('aria-pressed', options[i][0] === v));
      };
      box.append(b);
    });
    return { el: box, value: () => v };
  }

  /** A yes-or-no question as a sheet; true when [yes] is pressed. */
  function confirmSheet(title, text, yes) {
    return new Promise((done) => {
      const s = openSheet();
      add(s, el('h2', null, title), el('p', 'lede', text), add(el('div', 'actions'),
        button(yes, 'danger', () => { closeSheet(); done(true); }), button('Keep', 'ghost', () => { closeSheet(); done(false); })));
    });
  }

  async function clearLog() {
    if (!(await confirmSheet('Clear the log?', 'Its lines go on the TV too, including what phones changed. The log then starts with who cleared it.', 'Clear'))) return;
    act('clearLog');
  }

  // ---- Downloads -----------------------------------------------------------------------------
  /** The TV's PIN, asked for here when the TV is locked and the request needs it. */
  function askPin(text) {
    return new Promise((done) => {
      const s = openSheet();
      add(s, el('h2', null, 'The TV is locked with a PIN'), el('p', 'lede', text));
      const pin = el('input', 'input');
      pin.type = 'password';
      pin.inputMode = 'numeric';
      pin.maxLength = 8;
      pin.placeholder = 'The TV’s PIN';
      add(s, pin, add(el('div', 'actions'), button('Go on', 'mint', () => { const v = pin.value; closeSheet(); done(v); }), button('Cancel', 'ghost', () => { closeSheet(); done(null); })));
      pin.focus();
    });
  }

  async function download(req) {
    if (S.lock.locks && !req.pin) {
      const pin = await askPin('Type it to download this file.');
      if (pin == null) return;
      req = Object.assign({}, req, { pin });
    }
    // The TV asks first; this sheet waits with it, up to the 90 s the TV gives.
    const s = openSheet();
    const started = Date.now();
    const line = add(el('div', 'progress'), svg(ICON.spin), document.createTextNode(' Press Allow on the TV'));
    add(s, el('h2', null, 'Waiting for the TV'), line);
    const tick = setInterval(() => { line.lastChild.textContent = ' Press Allow on the TV · ' + hms(Date.now() - started).slice(3); }, 1000);
    try {
      const r = await api('download', req);
      if (!r.ok) return toast(r.text, true);
      save(r.name, unb64(r.data), 'application/octet-stream');
      toast('Downloaded ' + r.name);
    } catch (e) { if (!(e instanceof Unpaired)) toast(e.message, true); } finally {
      clearInterval(tick);
      if (sheetEl && sheetEl.contains(s)) closeSheet();
    }
  }

  function save(name, bytes, type) {
    const url = URL.createObjectURL(new Blob([bytes], { type }));
    const a = el('a');
    a.href = url;
    a.download = name;
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
  }

  // ---- The log -------------------------------------------------------------------------------
  function onLog(l) {
    if (!l) return;
    if (l.reset || l.epoch !== LOG.epoch) LOG.lines = [];
    LOG.epoch = l.epoch;
    LOG.lines = LOG.lines.concat(l.lines).slice(-300);
    LOG.next = l.next;
    const box = $('logbox');
    if (!box) return;
    if (l.reset) box.replaceChildren();
    const stick = box.scrollTop + box.clientHeight >= box.scrollHeight - 30;
    l.lines.forEach((x) => box.append(logLine(x)));
    while (box.childNodes.length > 300) box.firstChild.remove();
    if (stick) box.scrollTop = box.scrollHeight;
  }
  function logLine(x) {
    const d = el('div', x[2] === 'ok' ? 'ok' : x[2] === 'err' ? 'err' : null);
    add(d, el('span', 't', x[0] + '  '), document.createTextNode(x[1]));
    return d;
  }
  function logView(main) {
    add(main, el('h1', null, 'Connection log'), el('p', 'lede', 'What the TV’s VPN did this session, as it happens. Server addresses and times, no passwords.'));
    const box = el('div', 'log');
    box.id = 'logbox';
    LOG.lines.forEach((x) => box.append(logLine(x)));
    if (!LOG.lines.length) box.append(el('div', 't', 'Nothing yet.'));
    // Above the box, so they are on the first screen.
    main.append(add(el('div', 'toolbar'),
      button('Download', 'ghost small', () => {
        const head = S ? [S.tv.app + ' ' + S.tv.version + ' · build ' + S.tv.build + ' · on ' + S.tv.name, 'Downloaded ' + new Date().toString(), ''] : [];
        save((S ? S.tv.app : APP).toLowerCase().replace(/[^a-z0-9]/g, '') + '-log.txt', enc.encode(head.concat(LOG.lines.map((x) => x[0] + '  ' + x[1])).join('\n') + '\n'), 'text/plain');
      }),
      button('Clear', 'ghost small', clearLog)));
    main.append(box);
    setTimeout(() => { box.scrollTop = box.scrollHeight; }, 0);
  }

  // ---- Review and apply ----------------------------------------------------------------------
  let sheetEl = null;
  function openSheet() {
    closeSheet();
    const scrim = el('div', 'scrim');
    const sheet = el('div', 'sheet');
    sheet.append(el('div', 'grab'));
    scrim.append(sheet);
    scrim.onclick = (e) => { if (e.target === scrim && !sheetWatch) closeSheet(); };
    document.body.append(scrim);
    document.body.classList.add('sheet-open');
    sheetEl = scrim;
    return sheet;
  }
  function closeSheet() {
    if (sheetEl) sheetEl.remove();
    sheetEl = null;
    sheetWatch = null;
    document.body.classList.remove('sheet-open');
  }

  $('discard').onclick = () => { draft.clear(); fieldErrors.clear(); draw(true); };
  $('review').onclick = () => review([...draft.values()], { fromDraft: true });

  /** Sends [ops] to the TV to check; shows what would change, then applies it. */
  async function review(ops, opts) {
    opts = opts || {};
    let p;
    try {
      p = await api('changes', { ops });
    } catch (e) {
      if (!(e instanceof Unpaired)) toast(e.message, true);
      return;
    }
    // A problem with one of the draft's settings shows under that field, where it is fixed.
    const problems = Object.entries(p.problems);
    if (opts.fromDraft && !p.conflicts.length && problems.length) {
      const fieldOf = (k) => { const i = Number((k.match(/ops\.(\d+)$/) || [])[1]); const op = Number.isNaN(i) ? null : ops[i]; return op && op.op === 'set' && draft.has(op.path) ? op.path : null; };
      if (problems.every(([k]) => fieldOf(k))) {
        problems.forEach(([k, v]) => fieldErrors.set(fieldOf(k), v));
        draw(true);
        const first = document.querySelector('.field.bad');
        if (first) first.scrollIntoView({ block: 'center' });
        toast(problems.length === 1 ? 'One setting needs fixing first' : problems.length + ' settings need fixing first', true);
        return;
      }
    }
    const s = openSheet();
    const blocked = p.conflicts.length || problems.length;
    add(s, el('h2', null, blocked ? 'This can’t be applied yet' : opts.title || (p.diff.length === 1 ? 'Review the change' : 'Review ' + p.diff.length + ' changes')));
    const labelOf = (i) => {
      const op = ops[i];
      const item = op && op.path ? schemaItem(op.path) : null;
      if (op && op.path === 'lock.pin') return 'PIN';
      if (op && op.path === 'backup.passphrase') return 'Backup passphrase';
      return item ? item.label : op ? ({ import: 'The new profile', update: 'The file', restore: 'The backup', certificate: 'The certificate', delete: 'Delete' }[op.op] || 'This change') : 'This change';
    };
    if (p.conflicts.length) {
      s.append(el('p', 'info warn', 'The TV or another phone changed these since this page showed them:'));
      const d = el('div', 'diff');
      p.conflicts.forEach((c) => d.append(add(el('div'), el('b', null, c.label), add(el('span'), el('s', null, c.phone), document.createTextNode('→ on the TV now:'), el('em', null, c.tv)))));
      s.append(d);
      s.append(add(el('div', 'actions'), button('Use the TV’s, and check the rest', 'mint', () => {
        p.conflicts.forEach((c) => draft.delete(c.path));
        closeSheet();
        draw(true);
        if (draft.size) review([...draft.values()], opts);
      }), opts.fromDraft ? button('Use mine anyway', 'ghost', () => {
        // Checked again against what the TV has now, so this phone's values replace it.
        p.conflicts.forEach((c) => { const d = draft.get(c.path); if (d && 'from' in d) d.from = settingValue(c.path); });
        closeSheet();
        review([...draft.values()], opts);
      }) : null, button('Back', 'ghost', closeSheet)));
      return;
    }
    if (problems.length) {
      problems.forEach(([k, v]) => {
        const i = Number((k.match(/ops\.(\d+)/) || [])[1]);
        s.append(el('p', 'info bad', (Number.isNaN(i) ? '' : labelOf(i) + ': ') + v));
      });
      s.append(add(el('div', 'actions'), button('Back', 'ghost', closeSheet)));
      return;
    }
    const d = el('div', 'diff');
    p.diff.forEach((x) => d.append(add(el('div'), el('b', null, x.label), add(el('span'), x.from ? el('s', null, x.from) : null, x.from ? document.createTextNode('→') : null, el('em', null, x.to)))));
    if (p.diff.length) s.append(d);
    p.notes.forEach((n) => s.append(el('p', 'info', n)));
    if (!p.change) {
      s.append(add(el('div', 'actions'), button('Back', 'ghost', closeSheet)));
      return;
    }
    let pin = null;
    // With the PIN lock on, Apply waits for a whole PIN: an empty one would only be refused.
    const pinReady = () => !pin || pin.value.length >= 4;
    const gate = () => acts.querySelectorAll('button[data-go]').forEach((b) => { b.disabled = !pinReady(); });
    if (p.pin) {
      pin = el('input', 'input');
      pin.type = 'password';
      pin.inputMode = 'numeric';
      pin.placeholder = 'The TV’s PIN';
      pin.maxLength = 8;
      pin.oninput = gate;
      s.append(pin);
    }
    const progress = el('div', 'progress');
    progress.hidden = true;
    const acts = el('div', 'actions');
    const go = async (test) => {
      acts.dataset.busy = '1';
      acts.querySelectorAll('button').forEach((b) => { b.disabled = true; });
      progress.hidden = false;
      progress.className = 'progress';
      progress.replaceChildren(svg(ICON.spin), document.createTextNode(p.askTv.length ? ' Press Allow on the TV' : test ? ' Applying, then testing…' : ' Applying…'));
      // A try after a wrong PIN has the same id: only what comes from now on counts.
      for (let i = recent.length - 1; i >= 0; i--) if (recent[i].change === p.change) recent.splice(i, 1);
      try {
        const r = await api('changes/apply', { change: p.change, test, pin: pin ? pin.value : '' });
        if (!r.ok) throw new Error(r.text);
        sheetWatch = { id: p.change, progress, acts, ops, opts, pin, sheet: s, seen: Date.now() };
        // Progress that came in before this answer did.
        recent.filter((x) => x.change === p.change).forEach(onEvent);
      } catch (e) {
        progress.className = 'info bad';
        progress.textContent = e.message;
        delete acts.dataset.busy;
        acts.querySelectorAll('button').forEach((b) => { b.disabled = false; });
        gate();
      }
    };
    const testLabel = ops.some((o) => o.op === 'import') ? 'Test before saving' : 'Apply and test';
    const importing = ops.some((o) => o.op === 'import');
    // A setting for the tunnel that is up: Apply and test leads, as it puts the old one back if it fails.
    const testFirst = p.testable && !importing;
    const apply = button(ops.some((o) => o.op === 'delete') ? 'Delete' : importing ? 'Save' : 'Apply', ops.some((o) => o.op === 'delete') ? 'danger' : testFirst ? 'ghost' : 'mint', () => go(false));
    const test = p.testable ? button(testLabel, testFirst ? 'mint' : 'ghost', () => go(true)) : null;
    [apply, test].forEach((b) => { if (b) b.dataset.go = '1'; });
    add(acts, testFirst ? test : apply, testFirst ? apply : test, button('Back', 'ghost', closeSheet));
    add(s, progress, acts);
    gate();
  }

  /**
   * The page lost track of the change its sheet follows: the TV went away, or nothing came for a
   * while. It asks the TV where the change stands, and ends the sheet if it is over.
   */
  async function checkWatch() {
    const w = sheetWatch;
    if (!w) return;
    w.seen = Date.now();
    let r;
    try { r = await api('changes/state', { change: w.id }); } catch (e) { return; }
    if (sheetWatch !== w) return;
    const ev = r.event;
    if (!ev) onEvent({ t: 'change', change: w.id, state: 'lost', text: 'The TV’s app restarted before this finished. A test that was cut off puts the settings from before back. Check it again.' });
    else if (!['started', 'asking', 'applying', 'testing'].includes(ev.state)) onEvent(ev);
  }

  const recent = [];
  function onEvent(e) {
    if (e.t === 'change') {
      recent.push(e);
      if (recent.length > 20) recent.shift();
    }
    if (e.t !== 'change' || !sheetWatch || e.change !== sheetWatch.id) return;
    const w = sheetWatch;
    w.seen = Date.now();
    const busy = (text) => { w.progress.className = 'progress'; w.progress.replaceChildren(svg(ICON.spin), document.createTextNode(' ' + text)); };
    const end = (cls, text) => {
      w.progress.className = 'info ' + cls;
      // The TV shortens the key ("QcW+Dn81…bwo="); the phone shows it whole, and offers Copy.
      const key = w.opts.publicKey;
      const short = key && text && text.includes('…') && /public key/.test(text);
      w.progress.textContent = short ? text.replace(/[A-Za-z0-9+/]+…[A-Za-z0-9+/]+=*/, key) : text;
      if (short) {
        const copy = el('button', 'link', 'Copy the key');
        copy.onclick = () => copyText(key);
        w.progress.after(copy);
      }
      if (e.checks && e.checks.length) {
        const box = el('div');
        e.checks.forEach((c) => box.append(checkRow(c)));
        w.progress.after(box);
      }
    };
    switch (e.state) {
      case 'asking': busy(e.step || 'Press Allow on the TV'); break;
      case 'applying': busy('Applying…'); break;
      case 'testing': busy(e.step || 'Testing…'); break;
      case 'applied': {
        delete w.acts.dataset.busy;
        w.ops.forEach((o) => { if (o.path) draft.delete(o.path); });
        if (w.opts.done) w.opts.done();
        sheetWatch = null;
        end('ok', e.checks && e.checks.length ? 'Applied, and the test passed.' : 'Applied on the TV.');
        w.acts.replaceChildren(button('Done', 'mint', () => { closeSheet(); draw(true); }));
        if (!e.checks || !e.checks.length) setTimeout(() => { if (!sheetWatch) { closeSheet(); draw(true); } }, 1400);
        break;
      }
      case 'rolledBack':
        delete w.acts.dataset.busy;
        sheetWatch = null;
        end('bad', e.text);
        w.acts.replaceChildren(button('Done', 'ghost', () => { closeSheet(); draw(true); }));
        break;
      case 'pin':
        end('bad', e.text);
        if (w.pin) { w.pin.value = ''; w.pin.focus(); }
        delete w.acts.dataset.busy;
        // Back at once; Apply again once a whole PIN is typed.
        w.acts.querySelectorAll('button').forEach((b) => { b.disabled = !!b.dataset.go; });
        sheetWatch = null;
        break;
      case 'conflict':
        sheetWatch = null;
        closeSheet();
        toast('The TV changed some of this meanwhile. Check it again.', true);
        review(w.ops, w.opts);
        break;
      default:
        sheetWatch = null;
        end('bad', e.text || 'It didn’t apply.');
        delete w.acts.dataset.busy;
        if (e.retry) w.acts.querySelectorAll('button').forEach((b) => { b.disabled = false; });
        else w.acts.replaceChildren(button('Back', 'ghost', () => { closeSheet(); draw(true); }));
    }
  }

  // ---- Start ---------------------------------------------------------------------------------
  /** The encryption library, loaded again when a flaky Wi-Fi dropped it the first time. */
  async function haveNoble() {
    for (let i = 0; i < 3 && typeof noble === 'undefined'; i++) {
      await new Promise((done) => {
        const sc = document.createElement('script');
        sc.src = 'noble.js?try=' + (i + 1);
        sc.onload = done;
        sc.onerror = () => setTimeout(done, 800);
        document.head.append(sc);
      });
    }
    return typeof noble !== 'undefined';
  }

  haveNoble().then((ok) => {
    const m = location.hash.match(/pair=([\w-]+)\.([\w-]+)/);
    if (!ok) $('main').replaceChildren(el('p', 'info bad', 'This page didn’t load completely. Check the Wi-Fi, then reload it.'));
    else if (m) pairView(m[1], unb64(m[2]));
    else if (me) start();
    else unpaired('new');
  });
})();

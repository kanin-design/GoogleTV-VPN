/*
 * Draws the phone panel (app/src/main/assets/panel) as PNGs: the page as it is in the app, in a
 * headless Chromium, against a stand-in for the TV that answers its sealed requests with demo
 * data shaped like RemoteApi.state() and Schema.toJson(). tools/screenshots/shoot.sh runs it.
 *
 *   node shoot.js <folder> <fonts folder> [picture ...]
 *
 * Without pictures named it draws the README's; "all" draws every one. Needs Playwright with its
 * Chromium (npm install, then npx playwright install chromium).
 */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

let chromium;
try {
  ({ chromium } = require('playwright'));
} catch (e) {
  console.error('tools/screenshots: Playwright is missing. In tools/screenshots/phone: npm install, then npx playwright install chromium');
  process.exit(1);
}

const REPO = path.resolve(__dirname, '../../..');
const ASSETS = path.join(REPO, 'app/src/main/assets/panel');
const OUT = path.resolve(process.argv[2] || 'screenshots');
const FONTS = path.resolve(process.argv[3] || path.join(__dirname, '../build/fonts'));
const NAMES = process.argv.slice(4);
fs.mkdirSync(OUT, { recursive: true });

/** The name people see, as gradle.properties sets it. */
function appName() {
  const m = fs.readFileSync(path.join(REPO, 'gradle.properties'), 'utf8').match(/^appName=(.+)$/m);
  return m ? m[1].trim() : 'OpenTVPN';
}

// noble, the build the page itself uses, seals the stand-in's answers.
const sandbox = { console, Uint8Array, DataView, ArrayBuffer, TextEncoder, TextDecoder, crypto: require('crypto').webcrypto };
sandbox.self = sandbox;
sandbox.window = sandbox;
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync(path.join(ASSETS, 'noble.js'), 'utf8'), sandbox);
const noble = sandbox.noble;
const enc = new TextEncoder();
const hk = (secret, info) => noble.hkdf(noble.sha256, secret, undefined, enc.encode(info), 32);
// The pairing the page finds in its storage, and the key the stand-in answers with.
const KEY = new Uint8Array(32).map((_, i) => (i * 7 + 3) & 0xff);
const DOWN = hk(KEY, 'ovpnc down v1');
function nonce(dir, lane, n) {
  const b = new Uint8Array(12);
  b[0] = dir;
  b[1] = lane >>> 16;
  b[2] = (lane >>> 8) & 0xff;
  b[3] = lane & 0xff;
  const v = new DataView(b.buffer);
  v.setUint32(4, Math.floor(n / 4294967296));
  v.setUint32(8, n >>> 0);
  return b;
}
const b64 = (u8) => Buffer.from(u8).toString('base64');

// ---- Demo data --------------------------------------------------------------------------------
const NOW = Date.now();
const H = 3600e3, D = 24 * H;
const o = (value, label, help) => ({ value, label: label ?? value, help: help ?? null });
const it = (p, type, label, help, extra) => Object.assign({ path: p, type, label, help, effect: 'now', advanced: false }, extra || {});
const T = { effect: 'reconnect' };
const APP = appName();
const profileOpts = [o('From file', 'From file', 'Use what the profile says'), o('UDP', 'UDP', 'Faster. The usual choice'), o('TCP', 'TCP', 'Gets through strict networks')];
const apps = [o('com.example.streaming', 'Streaming service'), o('com.example.sports', 'Sports'), o('com.example.music', 'Music'), o('com.example.store', 'App store')];
const schema = {
  id: 's1',
  groups: [
    { id: 'protection', label: 'Protection', help: 'What happens when the tunnel drops, and what may leave the device outside it.', advanced: false, items: [
      it('settings.killSwitch', 'switch', 'Kill switch', 'Block all traffic while the tunnel is down'),
      it('settings.allowLan', 'switch', 'Allow the local network', 'Casting and devices at home keep working while connected', T),
      it('settings.pinMode', 'choice', 'Lock with a PIN', 'Ask for a PIN before profiles or settings change', { options: [o('Off', 'Off', 'Anyone can change profiles and settings'), o('For settings', 'For settings', 'Ask before profiles or settings change'), o('For everything', 'For everything', 'Ask every time ' + APP + ' opens')] }),
      it('settings.blockIpv6', 'switch', 'Block IPv6 outside the tunnel', 'Stops leaks when the server only tunnels IPv4', Object.assign({ advanced: true }, T)),
      it('settings.blockDns', 'switch', 'Block DNS outside the tunnel', 'With the local network allowed, lookups still use the tunnel', Object.assign({ advanced: true }, T)),
    ] },
    { id: 'startup', label: 'Startup', help: 'What ' + APP + ' does when the device turns on or the network changes.', advanced: false, items: [
      it('settings.startup', 'choice', 'Connect when the device starts', 'Connects before you open the app', { options: [o('', 'Off', 'Nothing connects by itself'), o('p1', 'Home'), o('p2', 'Office'), o('p3', 'Streaming'), o('p4', 'Travel')] }),
      it('settings.roam', 'switch', 'Reconnect automatically', 'After Wi-Fi changes and router restarts', T),
      it('settings.stayAwake', 'switch', 'Stay connected while the screen is off', 'Keeps the device awake while the tunnel is up', Object.assign({ advanced: true }, T)),
    ] },
    { id: 'apps', label: 'Apps', help: 'Some TV services block VPNs. Let those apps skip it, or send only the apps you choose through the VPN.', advanced: false, items: [
      it('settings.appMode', 'choice', 'Which apps use the VPN', APP + ' itself always does', Object.assign({ options: [o('skip', 'All apps, except the ones you choose', 'The apps you turn on skip the VPN'), o('only', 'Only the apps you choose', 'Every other app uses your normal connection')] }, T)),
      it('settings.apps', 'set', 'Chosen apps', 'Turn on the apps the choice above is about', Object.assign({ options: apps }, T)),
    ] },
    { id: 'diagnostics', label: 'Diagnostics', help: 'For when something does not connect.', advanced: false, items: [
      it('settings.verb', 'choice', 'Log detail', 'How much the log shows', Object.assign({ kind: 'openvpn', advanced: true, options: [o('1 · quiet', '1 · quiet', 'Errors and connection changes only'), o('3 · normal', '3 · normal', 'Recommended'), o('4 · detailed', '4 · detailed', 'Everything, for troubleshooting with your VPN admin')] }, T)),
    ] },
    { id: 'phones', label: 'Phone access', help: 'Paired phones can see and change ' + APP + ' from their browser, on your home network.', advanced: false, items: [
      it('settings.phoneReach', 'choice', 'When phones can reach the TV', 'When the panel answers a paired phone', { options: [o('open', 'While the app is open', 'Nothing listens once you leave ' + APP), o('vpn', 'Also while the VPN is on', 'Change things from the sofa while you watch')] }),
    ] },
    { id: 'openvpn', label: 'OpenVPN', help: 'Applied on top of every profile. A setting inside the .ovpn file wins unless noted.', advanced: true, items: [
      it('settings.proto', 'choice', 'Preferred protocol', 'Tried first when a profile lists UDP and TCP', Object.assign({ kind: 'openvpn', options: profileOpts }, T)),
      it('settings.timeout', 'choice', 'Connection timeout', 'Move on to the next server after this long', Object.assign({ options: [o('30', '30 s', 'Quick to give up on a dead server'), o('60', '60 s', 'Recommended'), o('120', '120 s', 'For slow or distant servers')] }, T)),
      it('settings.mtu', 'choice', 'Tunnel MTU', 'Lower it if video stalls on some networks', Object.assign({ options: [o('Auto', 'Auto', 'Recommended'), o('1400', '1400', 'Try this if video stalls'), o('1300', '1300', 'For networks that drop large packets')] }, T)),
      it('settings.tlsMin', 'choice', 'Minimum TLS version', 'Refuse servers that only offer older TLS', Object.assign({ kind: 'openvpn', options: [o('TLS 1.2', 'TLS 1.2', 'Works with almost every server'), o('TLS 1.3', 'TLS 1.3', 'Stricter. Newer servers only')] }, T)),
      it('settings.legacyCiphers', 'switch', 'Allow legacy ciphers', 'BF-CBC and others removed in OpenVPN 2.6', Object.assign({ kind: 'openvpn' }, T)),
      it('settings.compress', 'switch', 'Compress what this device sends', 'Can leak data (VORACLE). Compression the server sends is always accepted', Object.assign({ kind: 'openvpn' }, T)),
    ] },
    { id: 'dns', label: 'DNS', help: 'Where name lookups go while the tunnel is up.', advanced: true, items: [
      it('settings.pushedDns', 'switch', 'Use DNS from the server', 'The servers your VPN pushes', T),
      it('settings.customDns', 'lines', 'Custom DNS servers', 'Used instead of the pushed ones', Object.assign({ check: 'dns' }, T)),
      it('settings.searchDomains', 'lines', 'Search domains', 'Short names like nas resolve as nas.home.lan', T),
    ] },
    { id: 'automation', label: 'Automation', help: 'For home automation apps and ADB. Off until you turn it on.', advanced: true, items: [
      it('settings.automation', 'switch', 'Let other apps control ' + APP, 'They can connect and disconnect profiles'),
    ] },
    { id: 'general', label: 'General', help: '', advanced: false, items: [
      it('settings.advanced', 'switch', 'Advanced', 'OpenVPN, DNS, automation'),
      it('settings.autoUpdate', 'switch', 'Check for updates by itself', 'Each time ' + APP + ' opens'),
    ] },
  ],
  profile: [
    it('name', 'text', 'Name', 'Shown on cards and on Connect', { check: 'name' }),
    it('host', 'text', 'Server address', 'Use another address than the one in the file', Object.assign({ kind: 'openvpn', check: 'host' }, T)),
    it('port', 'number', 'Port', 'From 1 to 65535', Object.assign({ kind: 'openvpn', check: 'port' }, T)),
    it('proto', 'choice', 'Protocol', 'Override the protocol in the file', Object.assign({ kind: 'openvpn', options: profileOpts }, T)),
    it('killMode', 'choice', 'Kill switch', 'For this profile only. App setting follows Settings › Protection', Object.assign({ options: [o('App setting', 'App setting', 'Follow Settings › Protection'), o('Always on', 'Always on', 'Block traffic whenever this tunnel is down'), o('Off', 'Off', 'Never block for this profile')] }, T)),
    it('dnsMode', 'choice', 'DNS', 'For this profile only', Object.assign({ options: [o('App setting', 'App setting', 'The server’s DNS, or 1.1.1.1 if it sends none'), o('Server only', 'Server only', 'Only the DNS servers this server pushes'), o('Custom', 'Custom', 'Your own DNS servers')] }, T)),
    it('username', 'text', 'Username', 'For signing in to the server', Object.assign({ kind: 'openvpn', check: 'username' }, T)),
    it('password', 'secret', 'Password', 'Saved encrypted on the TV. It never comes back to the phone', Object.assign({ kind: 'openvpn' }, T)),
    it('pinned', 'switch', 'Pin to Quick connect', 'Show it first on the Connect screen'),
    it('startup', 'switch', 'Connect when the device starts', 'Connects before you open the app'),
  ],
};

function profile(id, name, kind, host, port, proto, lastUsed, status, extra) {
  const ovpn = kind === 'openvpn';
  const items = ['name'].concat(ovpn ? ['host', 'port', 'proto'] : []).concat(['killMode', 'dnsMode']).concat(extra && extra.login ? ['username', 'password'] : []).concat(['pinned', 'startup']);
  const values = { name, killMode: 'App setting', dnsMode: 'App setting', pinned: !!(extra && extra.pinned), startup: !!(extra && extra.startup) };
  if (ovpn) Object.assign(values, { host, port, proto: 'From file' });
  if (extra && extra.login) Object.assign(values, { username: 'alex', password: 'saved' });
  return {
    id, name, kind, kindLabel: ovpn ? 'OpenVPN' : 'WireGuard',
    server: [host + ':' + port, ovpn ? proto : null].filter(Boolean).join(' · '),
    signIn: ovpn ? (extra && extra.login ? { summary: 'Password saved for alex', method: 'Password', needs: true } : { summary: 'Certificate in the file', method: 'Certificate', needs: false }) : { summary: 'Key in the profile', method: 'Key', needs: false },
    publicKey: ovpn ? null : 'mWv2sJ6uPq0h3H5x8yQe1t4n7cBfLkZr9aDgUiOpXsE=',
    status, lastUsed, startup: !!(extra && extra.startup), items, values,
  };
}
const off = (lastUsed, ago) => ({ word: 'Not connected', detail: 'Last connected ' + ago, tone: 'off', line: 'Last connected ' + ago });
const state = {
  v: 1, schema: 's1',
  tv: { name: 'Living room TV', app: APP, version: '0.2.2', build: 157 },
  tunnel: { status: 'connected', profile: 'p1', since: NOW - (3 * H + 17 * 60e3 + 5e3), pausedUntil: null, error: null, advice: null, ip: '198.51.100.20', address: '10.8.0.6/24', dns: ['10.8.0.1'], remote: 'home.example.net' },
  active: 'p1',
  profiles: [
    profile('p1', 'Home', 'openvpn', 'home.example.net', 1194, 'UDP', NOW - 3 * H, { word: 'Connected', detail: null, tone: 'on', line: 'Connected' }, { pinned: true, startup: true }),
    profile('p2', 'Office', 'openvpn', 'vpn.office.example.com', 443, 'TCP', NOW - D, off(NOW - D, 'yesterday'), { login: true }),
    profile('p3', 'Streaming', 'wireguard', 'wg.example.org', 51820, null, NOW - 2 * D, off(NOW - 2 * D, '2 days ago')),
    profile('p4', 'Travel', 'openvpn', 'travel.example.net', 1194, 'UDP', NOW - 5 * D, off(NOW - 5 * D, '5 days ago')),
  ],
  settings: { killSwitch: true, allowLan: true, pinMode: 'Off', blockIpv6: true, blockDns: true, startup: 'p1', roam: true, stayAwake: false, appMode: 'skip', apps: ['com.example.streaming'], verb: '3 · normal', phoneReach: 'vpn', proto: 'From file', timeout: '60', mtu: 'Auto', tlsMin: 'TLS 1.2', legacyCiphers: false, compress: false, pushedDns: true, customDns: [], searchDomains: [], automation: false, advanced: false, autoUpdate: true },
  lock: { pin: false, locks: false, mode: 'Off' },
  backup: { passphrase: true, last: 'Last backup 3 days ago, with 4 profiles' },
  you: { device: 'd1', name: 'Phone' },
  devices: [{ id: 'd1', name: 'Phone', paired: NOW - 9 * D, seen: NOW, you: true }, { id: 'd2', name: 'iPad', paired: NOW - 30 * D, seen: NOW - 4 * D, you: false }],
  vpnAllowed: true, wireguard: true,
};
const t0 = new Date(NOW - 3 * H - 17 * 60e3);
const hm = (d) => d.toTimeString().slice(0, 8);
const log = { epoch: 1, next: 9, reset: true, lines: [
  [hm(t0), 'Connecting to Home (home.example.net:1194 UDP)', null],
  [hm(new Date(+t0 + 180)), 'TLS handshake with home.example.net', null],
  [hm(new Date(+t0 + 420)), 'Peer certificate verified: CN=home-server', null],
  [hm(new Date(+t0 + 650)), 'Data channel: AES-256-GCM, tls-crypt on the control channel', null],
  [hm(new Date(+t0 + 700)), 'Tunnel address 10.8.0.6/24, DNS 10.8.0.1', null],
  [hm(new Date(+t0 + 760)), 'Connected', 'ok'],
  [hm(new Date(+t0 + 1900)), 'Address sites see: 198.51.100.20', null],
] };

// ---- The stand-in for the TV ---------------------------------------------------------------
const TYPES = { '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript', '.ttf': 'font/ttf' };
let firstEvents = true;
async function answer(route) {
  const req = route.request();
  const p = new URL(req.url()).pathname;
  if (req.method() === 'GET') {
    const file = p.startsWith('/__fonts/') ? path.join(FONTS, path.basename(p)) : path.join(ASSETS, p === '/' ? 'index.html' : p.slice(1));
    if (!fs.existsSync(file)) return route.fulfill({ status: 404, body: '' });
    return route.fulfill({ status: 200, contentType: TYPES[path.extname(file)] || 'application/octet-stream', body: fs.readFileSync(file) });
  }
  const body = JSON.parse(req.postData() || '{}');
  const name = p.replace('/api/v2/', '');
  let plain;
  if (name === 'schema') plain = { ok: true, schema };
  else if (name === 'events') {
    // The first answer brings the state; later ones wait, as the TV does when nothing changes.
    if (!firstEvents) await new Promise((r) => setTimeout(r, 20000));
    plain = { v: 1, seq: 1, events: [], log: firstEvents ? log : { epoch: 1, next: 9, lines: [] }, state, bytes: [2022612736, 96e6] };
    firstEvents = false;
  } else plain = { ok: true };
  const aad = 'POST /api/v2/' + name;
  const c = noble.chacha20poly1305(DOWN, nonce(1, body.l, body.n), enc.encode(aad)).encrypt(enc.encode(JSON.stringify(plain)));
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ n: body.n, c: b64(c) }) });
}

// Android's fonts for the page, whatever the computer has: Roboto and Droid Sans Mono.
const FONT_CSS = [300, 400, 500, 600, 700, 800, 900].map((w) => `@font-face{font-family:Roboto;font-weight:${w};src:url(/__fonts/Roboto-${w}.ttf)}`).join('') +
  '@font-face{font-family:"Droid Sans Mono";src:url(/__fonts/DroidSansMono.ttf)}' +
  ':root{--sans:Roboto,sans-serif!important;--mono:"Droid Sans Mono",monospace!important}';

/** Pictures: a name, the page, and what differs from the demo (the tunnel, and Home's state). */
const SHOTS = [
  ['phone-status', '#/status'],
  ['phone-profiles', '#/profiles'],
  ['phone-profile', '#/p/p1'],
  ['phone-settings', '#/settings'],
  ['phone-add', '#/add'],
  ['phone-log', '#/log'],
  ['phone-connecting', '#/status', { status: 'connecting', since: null, ip: null, address: null }, { word: 'Connecting…', detail: null, tone: 'busy', line: 'Connecting…' }],
  ['phone-off', '#/status', { status: 'disconnected', since: null, ip: null, address: null, profile: null }, { word: 'Not connected', detail: 'Last connected 3 h ago', tone: 'off', line: 'Last connected 3 h ago' }],
];
const README = ['phone-status', 'phone-profiles'];

(async () => {
  const chosen = NAMES.includes('all') ? SHOTS : SHOTS.filter(([name]) => (NAMES.length ? NAMES : README).includes(name));
  if (!chosen.length) return;
  for (const f of ['Roboto-400.ttf', 'DroidSansMono.ttf']) {
    if (!fs.existsSync(path.join(FONTS, f))) {
      console.error('tools/screenshots: no fonts in ' + FONTS + '. shoot.sh downloads them.');
      process.exit(1);
    }
  }
  const browser = await chromium.launch();
  const base = JSON.stringify(state);
  for (const [name, hash, tunnel, homeStatus] of chosen) {
    firstEvents = true;
    Object.assign(state, JSON.parse(base));
    if (tunnel) Object.assign(state.tunnel, tunnel);
    if (homeStatus) state.profiles[0].status = homeStatus;
    const ctx = await browser.newContext({
      viewport: { width: 393, height: 852 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, colorScheme: 'dark',
      userAgent: 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36',
    });
    await ctx.addInitScript(({ key, css }) => {
      localStorage.setItem('ovpnc:' + location.host, JSON.stringify({ d: 'd1', k: key, n: 0, name: 'Phone', tv: 'Living room TV' }));
      document.addEventListener('DOMContentLoaded', () => {
        const s = document.createElement('style');
        s.textContent = css;
        document.head.append(s);
      });
    }, { key: b64(KEY), css: FONT_CSS });
    await ctx.route('http://tv.lan:8080/**', answer);
    const page = await ctx.newPage();
    page.on('pageerror', (e) => console.error(name + ': ' + e.message));
    await page.goto('http://tv.lan:8080/' + hash);
    await page.waitForTimeout(1200);
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: path.join(OUT, name + '.png') });
    console.log('phone: ' + name + '.png');
    await ctx.close();
  }
  await browser.close();
})();

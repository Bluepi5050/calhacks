import * as QRMod from 'qrcode@1.5.4';

// SmartBag store console. One SpacetimeDB connection; global subscription = store + my_stores only,
// plus the live rows of ONE selected store (storeQueries), swapped when the store changes.
const QRLib = (QRMod && typeof QRMod.toDataURL === 'function') ? QRMod : ((QRMod && QRMod.default) || null);
const ACCENT = '#E8654A';
const MAPS_KEY = 'AIzaSyC6dseIbredlFfc0oM6ayDR6JLH3WSbqd4';
const MONO = 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace';
const NUS = '6e400001-b5a3-f393-e0a9-e50e24dcca9e';
const NUS_RX = '6e400002-b5a3-f393-e0a9-e50e24dcca9e';
const NUS_TX = '6e400003-b5a3-f393-e0a9-e50e24dcca9e';
const STORE_TABLES = ['store', 'myStores', 'myAccount', 'myTeam', 'myStoreActivity', 'myInvites'];
const ROW_TABLES = ['product', 'shopper', 'cartItem', 'shopOrder', 'orderLine', 'bagEvent', 'myStorePeople'];
// Global: the store list plus the caller's own account, teams and activity (views scoped to the caller).
// my_store_people = names of signed-in shoppers in stores the caller runs (staff only; guests see 'Guest ·id').
const GLOBAL_QUERIES = ['SELECT * FROM store', 'SELECT * FROM my_stores', 'SELECT * FROM my_account', 'SELECT * FROM my_team', 'SELECT * FROM my_store_activity', 'SELECT * FROM my_store_people', 'SELECT * FROM my_invites'];
const AUTH_DEFAULT = 'https://auth.spacetimedb.com/oidc';
const RECEIPT_MS = 60000;           // a walk-out receipt stays on Live for a minute, then lives in Orders
const q = (v) => "'" + String(v).replace(/'/g, "''") + "'";
const utcDay = (offsetDays) => new Date(Date.now() + offsetDays * 86400000).toISOString().slice(0, 10);
// Everything for ONE store. Orders are indexed by (store_id, UTC day): tomorrow absorbs clock skew and
// two days back covers every timezone's "yesterday".
function storeQueries(id) {
  const days = [utcDay(1), utcDay(0), utcDay(-1), utcDay(-2)];
  return [
    'SELECT * FROM product WHERE store_id = ' + q(id),
    'SELECT * FROM shopper WHERE store_id = ' + q(id),
    'SELECT * FROM cart_item WHERE store_id = ' + q(id),
    'SELECT * FROM bag_event WHERE store_id = ' + q(id),
    ...days.map((d) => 'SELECT * FROM shop_order WHERE store_id = ' + q(id) + ' AND day = ' + q(d)),
    ...days.map((d) => 'SELECT * FROM order_line WHERE store_id = ' + q(id) + ' AND day = ' + q(d)),
  ];
}

const fmt = (c) => '$' + ((Number(c) || 0) / 100).toFixed(2);
const ms = (ts) => (ts && ts.microsSinceUnixEpoch != null ? Number(ts.microsSinceUnixEpoch / 1000n) : 0);
const hex = (id) => { try { return id.toHexString(); } catch (e) { return String(id); } };
// Every SpacetimeDB identity starts with the same bytes (c200…), so the short id uses the tail.
const shortId = (h) => String(h).slice(-6);
const errMsg = (e) => String((e && (e.message || e)) || 'Unknown error').replace(/^SenderError:\s*/, '');
const plural = (n, w) => n + ' ' + w + (n === 1 ? '' : 's');
function haversine(a, b) {
  const R = 6371000, r = (x) => x * Math.PI / 180;
  const dLat = r(b.lat - a.lat), dLng = r(b.lng - a.lng);
  const s = Math.sin(dLat / 2) ** 2 + Math.cos(r(a.lat)) * Math.cos(r(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(s));
}
function fmtDist(m) { if (m == null) return ''; return m < 1000 ? Math.round(m) + ' m' : (m / 1000).toFixed(m < 10000 ? 1 : 0) + ' km'; }
function ago(t) { const s = (Date.now() - t) / 1000; if (s < 5) return 'just now'; if (s < 60) return Math.floor(s) + 's ago'; if (s < 3600) return Math.floor(s / 60) + ' min ago'; if (s < 86400) return Math.floor(s / 3600) + ' h ago'; return new Date(t).toLocaleDateString(); }
const clock = (t) => new Date(t).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
const isToday = (t) => new Date(t).toDateString() === new Date().toDateString();
function dayLabel(t) {
  const d = new Date(t).toDateString();
  if (d === new Date().toDateString()) return 'Today';
  if (d === new Date(Date.now() - 86400000).toDateString()) return 'Yesterday';
  return new Date(t).toLocaleDateString([], { weekday: 'long', month: 'short', day: 'numeric' });
}
function readUrl() { const p = new URLSearchParams(window.location.search); return { view: p.get('view') || 'live', store: p.get('store') }; }
function parsePrice(raw) {
  const s = String(raw == null ? '' : raw).replace(/[$\s,]/g, '');
  if (!/^\d{1,6}(\.\d{0,2})?$/.test(s)) return null;
  const c = Math.round(parseFloat(s) * 100);
  return c >= 0 && c <= 9999999 ? c : null;
}
const SKU_RE = /^[A-Za-z0-9_-]{1,40}$/;
function skuOfLine(line) {
  const t = String(line || '').trim();
  if (t.startsWith('{')) { try { const j = JSON.parse(t); return String(j.sku || j.id || j.code || ''); } catch (e) { return ''; } }
  const m = t.match(/^(?:ADD|DEL|REMOVE|RM)\s+(.+)$/i);
  const rest = (m ? m[1] : t).replace(/^[+-]/, '');
  return rest.split('@')[0].trim();
}
// A bag_event in words. The module writes: '+ Name', '− Name', 'not sold here', 'already in bag', 'not in bag',
// 'unreadable', 'added, no store yet', and for walk-outs 'charged $x' / 'payment due $x' / 'left, nothing to charge'.
function describe(e, priceBySku) {
  const r = String(e.result || ''), line = String(e.line || '');
  if (line.startsWith('WALKOUT')) {
    const code = line.slice(8).trim();
    const amt = (r.match(/\$\d+(\.\d+)?/) || [''])[0];
    return { kind: 'walkout', code, amount: amt, due: r.startsWith('payment due'), nothing: !amt };
  }
  const sku = skuOfLine(line);
  if (r.startsWith('+ ')) { const p = priceBySku.get(sku); return { kind: 'add', text: 'added ' + r.slice(2), amount: p ? fmt(p.priceCents) : '' }; }
  if (r.startsWith('− ') || r.startsWith('- ')) return { kind: 'remove', text: 'removed ' + r.slice(2), amount: '' };
  if (r === 'already in bag') return { kind: 'info', text: 'repeat read of ' + (sku || 'a tag') + ', ignored', amount: '' };
  if (r === 'not sold here') return { kind: 'warn', text: 'scanned ' + (sku || 'an item') + ', not sold here', amount: '' };
  if (r === 'unreadable') return { kind: 'warn', text: 'sent an unreadable line', amount: '' };
  return { kind: 'info', text: (sku ? sku + ': ' : '') + r, amount: '' };
}
function geofenceMap(s, dark) {
  const r = Math.max(10, s.radiusM || 150);
  const z = r <= 60 ? 18 : r <= 120 ? 17 : r <= 250 ? 16 : 15;
  const pts = [];
  for (let i = 0; i <= 40; i++) {
    const a = (i / 40) * 2 * Math.PI;
    const dLat = (r / 111320) * Math.cos(a), dLng = (r / (111320 * Math.cos(s.lat * Math.PI / 180))) * Math.sin(a);
    pts.push((s.lat + dLat).toFixed(6) + ',' + (s.lng + dLng).toFixed(6));
  }
  const style = dark
    ? ['element:geometry|color:0x1f1f23', 'element:labels.text.fill|color:0x8e8e97', 'element:labels.text.stroke|color:0x18181b', 'feature:road|element:geometry|color:0x2e2e34', 'feature:water|element:geometry|color:0x101014', 'feature:poi|visibility:off', 'feature:transit|visibility:off', 'feature:road|element:labels.icon|visibility:off', 'feature:road.local|element:labels|visibility:off']
    : ['saturation:-100', 'feature:poi|visibility:off', 'feature:transit|visibility:off', 'feature:road|element:geometry|lightness:30', 'feature:road|element:labels.icon|visibility:off', 'feature:road.local|element:labels|visibility:off', 'feature:road.arterial|element:labels|visibility:simplified'];
  return 'https://maps.googleapis.com/maps/api/staticmap?size=640x200&scale=2&center=' + s.lat + ',' + s.lng + '&zoom=' + z
    + style.map((x) => '&style=' + encodeURIComponent(x)).join('')
    + '&path=' + encodeURIComponent('color:0xE8654Aff|weight:2|fillcolor:0xE8654A1F|' + pts.join('|'))
    + '&markers=' + encodeURIComponent('size:tiny|color:0xE8654A|' + s.lat + ',' + s.lng) + '&key=' + MAPS_KEY;
}
// ---- sign-in (SpacetimeAuth, OIDC authorization code + PKCE; tokens stay in this browser's localStorage)
const b64url = (buf) => btoa(String.fromCharCode.apply(null, Array.from(new Uint8Array(buf)))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const randomStr = (n) => b64url(crypto.getRandomValues(new Uint8Array(n)));
async function pkceChallenge(v) { return b64url(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(v))); }
function jwtPayload(t) {
  try { const p = String(t).split('.')[1].replace(/-/g, '+').replace(/_/g, '/'); const s = atob(p + '==='.slice((p.length + 3) % 4)); return JSON.parse(decodeURIComponent(Array.from(s).map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0')).join(''))); } catch (e) { return null; }
}
function loadJSON(k) { try { return JSON.parse(localStorage.getItem(k) || 'null'); } catch (e) { return null; } }
function saveJSON(k, v) { try { if (v == null) localStorage.removeItem(k); else localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} }
function sessionFrom(tok, prev) {
  const idToken = tok.id_token || (prev && prev.idToken);
  const p = jwtPayload(idToken) || {};
  return { idToken, refreshToken: tok.refresh_token || (prev && prev.refreshToken) || null, exp: p.exp ? p.exp * 1000 : Date.now() + (tok.expires_in || 3600) * 1000,
    sub: p.sub || '', name: p.name || p.preferred_username || p.nickname || (p.email ? String(p.email).split('@')[0] : ''), email: p.email || '' };
}
async function tokenRequest(authority, body) {
  const ac = typeof AbortController !== 'undefined' ? new AbortController() : null;
  const t = setTimeout(() => { try { ac && ac.abort(); } catch (e) {} }, 15000);
  let r;
  try { r = await fetch(authority + '/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(body).toString(), signal: ac ? ac.signal : undefined }); }
  catch (e) { clearTimeout(t); const er = new Error('Could not reach the sign-in service.'); er.transient = true; throw er; }
  clearTimeout(t);
  const j = await r.json().catch(() => ({}));
  if (!r.ok || !j.id_token) { const e = new Error(j.error_description || j.error || ('Sign-in failed (' + r.status + ')')); e.status = r.status; e.transient = r.status >= 500 || r.status === 429; throw e; }
  return j;
}
const withTimeout = (p, ms, msg) => Promise.race([Promise.resolve(p), new Promise((_, rej) => setTimeout(() => rej(new Error(msg || 'No reply from the database. Check the connection and try again.')), ms || 12000))]);
const initials = (n) => (String(n || '?').trim().split(/\s+/).map((w) => w[0]).join('').slice(0, 2) || '?').toUpperCase();

function paidTotalFor(d) { return d.orders.filter((o) => o.status === 'paid' && isToday(o.at)).reduce((a, o) => a + o.totalCents, 0); }
// Uncontrolled inputs keep a stable key; this ref brings in a remote value only while the field isn't being edited.
const syncRef = (v) => (el) => { if (el && document.activeElement !== el && el.dataset.v !== String(v)) { el.value = v; el.dataset.v = String(v); } };
function useCountUp(v, dur) {
  const [shown, setShown] = React.useState(v);
  const prev = React.useRef(v);
  React.useEffect(() => {
    const from = prev.current; prev.current = v;
    const reduce = typeof matchMedia !== 'undefined' && matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (from === v || reduce) { setShown(v); return; }
    let raf; const t0 = performance.now();
    const step = (t) => { const k = Math.min(1, (t - t0) / (dur || 700)); setShown(Math.round(from + (v - from) * (1 - Math.pow(1 - k, 3)))); if (k < 1) raf = requestAnimationFrame(step); };
    raf = requestAnimationFrame(step);
    const guard = setTimeout(() => setShown(v), (dur || 700) + 300);       // background tabs pause rAF
    return () => { cancelAnimationFrame(raf); clearTimeout(guard); };
  }, [v]);
  return shown;
}
function useWidth(ref) {
  const [w, setW] = React.useState(0);
  React.useEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    setW(Math.round(el.getBoundingClientRect().width));
    const ro = new ResizeObserver((es) => { const r = es[0] && es[0].contentRect; if (r) setW(Math.round(r.width)); });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return w;
}

const PALETTE = {
  light: { ink: '#18181b', muted: '#52525b', faint: '#71717a', line: '#e4e4e7', strong: '#d4d4d8', surface: '#ffffff', sunken: '#fafafa', hover: '#f4f4f5', field: '#ffffff', acc: '#C2462C', ok: '#157a4a', err: '#b42318' },
  dark: { ink: '#f4f4f5', muted: '#a1a1aa', faint: '#8e8e97', line: '#27272a', strong: '#3f3f46', surface: '#18181b', sunken: '#111113', hover: '#27272a', field: '#0f0f11', acc: '#F0806A', ok: '#34c47c', err: '#f07171' },
};
const CSS = `
.sbc { color: var(--ink); }
.sbc .ink { color: var(--ink); } .sbc .muted { color: var(--muted); } .sbc .faint { color: var(--faint); }
.sbc .acc { color: var(--acc); } .sbc .ok { color: var(--ok); } .sbc .err { color: var(--err); }
.sbc .hl { border-color: var(--line); } .sbc .surface { background: var(--surface); } .sbc .sunken { background: var(--sunken); }
.sbc .mono { font-family: ${MONO}; } .sbc .tnum { font-variant-numeric: tabular-nums; }
.sbc .cap { font-size: 11px; letter-spacing: .14em; text-transform: uppercase; color: var(--faint); font-weight: 500; }
.sbc .hov:hover { background: var(--hover); }
.sbc .btn { display: inline-flex; align-items: center; justify-content: center; gap: 6px; height: 34px; padding: 0 14px; border-radius: 9999px; border: 1px solid var(--strong); color: var(--ink); font-size: 13px; font-weight: 500; white-space: nowrap; background: transparent; transition: background .15s; }
.sbc .btn:hover:not(:disabled) { background: var(--hover); }
.sbc .btn-primary { background: ${ACCENT}; border-color: ${ACCENT}; color: #18181b; font-weight: 600; }
.sbc .btn-primary:hover:not(:disabled) { background: #ee7a62; }
.sbc .btn-danger { color: var(--err); }
.sbc .btn:disabled, .sbc .btn[aria-disabled="true"] { opacity: .4; cursor: default; }
.sbc .btn-sm { height: 28px; padding: 0 11px; font-size: 12px; }
.sbc .field { width: 100%; height: 36px; border-radius: 10px; border: 1px solid var(--strong); background: var(--field); color: var(--ink); padding: 0 12px; font-size: 14px; outline: none; transition: border-color .15s; }
.sbc .field::placeholder { color: var(--faint); }
.sbc .field:hover:not(:disabled) { border-color: var(--faint); }
.sbc .field:focus { border-color: ${ACCENT}; }
.sbc .field:disabled { opacity: .55; }
.sbc .field[aria-invalid="true"] { border-color: var(--err); }
.sbc .cell { width: 100%; height: 30px; border: 0; border-bottom: 1px dotted var(--strong); border-radius: 0; background: transparent; color: var(--ink); padding: 0 2px; font-size: 14px; outline: none; }
.sbc .cell:hover:not(:disabled) { border-bottom: 1px solid var(--faint); }
.sbc .cell:disabled { border-bottom-color: transparent; }
.sbc .cell:focus { border-bottom: 1px solid ${ACCENT}; }
.sbc :where(button, a, select, summary):focus-visible { outline: 2px solid var(--ink); outline-offset: 2px; }
.sbc .noscroll { scrollbar-width: none; } .sbc .noscroll::-webkit-scrollbar { display: none; }
.sbc-new { animation: sbcNew .7s cubic-bezier(.2,.8,.2,1); }
@keyframes sbcNew { from { opacity: 0; transform: translateY(-4px); background: rgba(232,101,74,.14); } to { opacity: 1; transform: none; background: transparent; } }
.sbc-receipt { animation: sbcReceipt .55s cubic-bezier(.2,.8,.2,1); }
@keyframes sbcReceipt { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: none; } }
.sbc-stamp { animation: sbcStamp .45s .45s cubic-bezier(.2,.8,.2,1) both; }
@keyframes sbcStamp { from { opacity: 0; transform: translateY(3px); } to { opacity: 1; transform: none; } }
@keyframes sbcAge { from { transform: scaleX(1); } to { transform: scaleX(0); } }
@keyframes sbcComplete { from { transform: scaleX(.5); } to { transform: scaleX(1); } }
.sbc-beat { animation: sbcBeat .8s ease-out; }
@keyframes sbcBeat { from { box-shadow: 0 0 0 0 rgba(31,157,99,.55); } to { box-shadow: 0 0 0 7px rgba(31,157,99,0); } }
.sbc .menu { position: absolute; right: 0; top: calc(100% + 6px); z-index: 30; min-width: 240px; border-radius: 14px; border: 1px solid var(--line); background: var(--surface); box-shadow: 0 8px 28px rgba(0,0,0,.12); }
.sbc-plus { animation: sbcPlus 4s ease-out both; }
@keyframes sbcPlus { 0% { opacity: 0; transform: translateY(4px); } 10%, 70% { opacity: 1; transform: none; } 100% { opacity: 0; } }
@media (prefers-reduced-motion: reduce) { .sbc [aria-hidden="true"][style*="sbcAge"] { animation: none !important; } .sbc [data-leave-bar] { transition: none !important; } .sbc-new, .sbc-receipt, .sbc-stamp, .sbc-beat { animation: none !important; } .sbc-plus { animation: sbcPlusStill 2.5s step-end both !important; } }
@keyframes sbcPlusStill { 0% { opacity: 1; } 100% { opacity: 0; } }
`;

function UserDiv() {
  const { theme } = useTheme();
  const dark = theme === 'dark';
  const pal = dark ? PALETTE.dark : PALETTE.light;
  const rootRef = React.useRef(null);
  const width = useWidth(rootRef);
  const stdbAddr = relPathAddress('spacetime');
  const fwAddr = relPathAddress('firmware');
  const androidAddr = relPathAddress('android');
  const iosAddr = relPathAddress('ios');
  // Who is connecting: a signed-in person (SpacetimeAuth id_token), else the page owner's console
  // identity (stored console token), else this browser's own guest identity (kept in localStorage).
  // The stored console token and admin code are only ever used when the page owner opens the page.
  const isOwner = !!userId && userId === ownerId;
  const AUTH_KEY = 'smartbag.auth.v1:' + ownerId;
  const GUEST_KEY = 'smartbag.guest.v1:' + ownerId;
  const PKCE_KEY = 'smartbag.pkce.v1:' + ownerId;
  const isAuthPopup = typeof window !== 'undefined' && !!window.opener && window.name === 'sbauth';
  // The owner's console credentials live OUTSIDE this app (owner-only node), so sharing the page never shares them.
  const secretAddr = ownerId + '/file-system/smartbag-private/spacetime';

  const [cfg, setCfg] = React.useState(null);
  const [authCfg, setAuthCfg] = React.useState(null);
  const [authLoaded, setAuthLoaded] = React.useState(false);
  const [session, setSession] = React.useState(() => loadJSON(AUTH_KEY));
  const [authBusy, setAuthBusy] = React.useState(null);
  const [authErr, setAuthErr] = React.useState(null);
  const [menuOpen, setMenuOpen] = React.useState(false);
  const [nameDraft, setNameDraft] = React.useState(null);
  const [conn, setConn] = React.useState(null);
  const [status, setStatus] = React.useState('connecting');
  const [operator, setOperator] = React.useState(null);
  const [tick, setTick] = React.useState(0);
  const [storeTick, setStoreTick] = React.useState(0);
  const [firmware, setFirmware] = React.useState('');
  const [android, setAndroid] = React.useState(null);
  const [ios, setIos] = React.useState(null);
  const [apkUrl, setApkUrl] = React.useState(null);
  const [srcUrl, setSrcUrl] = React.useState(null);
  const [iosUrl, setIosUrl] = React.useState(null);
  const [qr, setQr] = React.useState(null);
  const [route, setRoute] = React.useState(readUrl);
  const [geo, setGeo] = React.useState({ status: 'idle', pos: null, err: null });
  const [open, setOpen] = React.useState(null);
  const [confirm, setConfirm] = React.useState(null);
  const [copied, setCopied] = React.useState(null);
  const [notice, setNotice] = React.useState(null);
  const [fieldMsg, setFieldMsg] = React.useState({});
  const [, setClock] = React.useState(0);
  const [scoped, setScoped] = React.useState(null);
  const [storeErr, setStoreErr] = React.useState(null);
  const [retryKey, setRetryKey] = React.useState(0);
  const [dayKey, setDayKey] = React.useState(() => utcDay(0));
  const [storeSearch, setStoreSearch] = React.useState('');
  const [catSearch, setCatSearch] = React.useState('');
  const [draft, setDraft] = React.useState({ sku: '', name: '', price: '', unit: '' });
  const [draftTried, setDraftTried] = React.useState(false);
  const [showInvite, setShowInvite] = React.useState(false);
  const [joinCode, setJoinCode] = React.useState('');
  const [pinName, setPinName] = React.useState('');
  const [inviteEmail, setInviteEmail] = React.useState('');
  const [mapFailed, setMapFailed] = React.useState(false);
  const [feedAll, setFeedAll] = React.useState(false);
  const [demoBusy, setDemoBusy] = React.useState(null);

  const liveRef = React.useRef(false);
  const liveSinceRef = React.useRef(Date.now());
  const lagRef = React.useRef([]);
  const orderLagRef = React.useRef(new Map());
  const subsRef = React.useRef(new Map());          // connection → Set of store subscription handles
  const lastScopeRef = React.useRef({ conn: null, sel: null });
  const noticeTimer = React.useRef(null);
  const noticeHold = React.useRef(false);
  const seenReceipts = React.useRef(new Map());
  const sessionRef = React.useRef(session);
  const refreshRef = React.useRef(null);
  const selRef = React.useRef(null);
  const preloadRef = React.useRef(null);
  const modRef = React.useRef(null);
  const beatRef = React.useRef({ t: 0, tick: -1, k: 0 });
  const offsetRef = React.useRef(0);
  const demoT0Ref = React.useRef(0);
  const demoIdRef = React.useRef('');
  const leftRef = React.useRef(new Map());
  const userFails = React.useRef(0);
  const hostRef = React.useRef(null);
  sessionRef.current = session;

  const sessionKey = session && session.idToken ? 'user:' + (session.sub || 'x') : 'anon';
  const authority = (authCfg && authCfg.authority) || AUTH_DEFAULT;
  const clientId = authCfg && authCfg.clientId;
  const redirectUri = (authCfg && authCfg.redirectUri && authCfg.redirectUri.startsWith(window.location.origin + '/')) ? authCfg.redirectUri : (window.location.origin + window.location.pathname);
  const authReady = !!clientId;
  const clientIdRef = React.useRef(null); clientIdRef.current = clientId;
  const authCfgRef = React.useRef(null); authCfgRef.current = authCfg;

  const [fast, setFast] = React.useState(false);
  React.useEffect(() => {
    const t = setInterval(() => { setClock((x) => x + 1); setDayKey((p) => (p === utcDay(0) ? p : utcDay(0))); }, fast ? 1000 : 15000);
    return () => clearInterval(t);
  }, [fast]);

  React.useEffect(() => {
    if (!stdbAddr) return;
    const offs = [
      FS.onValue(FS.ref(FS.db, fwAddr + '/code'), (s) => setFirmware(s.val() || '')),
      FS.onValue(FS.ref(FS.db, androidAddr), (s) => setAndroid(s.val())),
      FS.onValue(FS.ref(FS.db, iosAddr), (s) => setIos(s.val())),
      FS.onValue(FS.ref(FS.db, stdbAddr + '/auth'), (s) => { setAuthCfg(s.val()); setAuthLoaded(true); }, () => setAuthLoaded(true)),
    ];
    return () => offs.forEach((o) => { try { o && o(); } catch (e) {} });
  }, [stdbAddr]);

  // ---- session lifecycle. authGen increases on every sign-in/sign-out, so a refresh that finishes after
  // the person signed out (or signed in as someone else) is discarded. One refresh at a time.
  const authGen = React.useRef(0);
  const refreshing = React.useRef(null);
  const pollRef = React.useRef(null);
  const [refreshNonce, setRefreshNonce] = React.useState(0);
  const signOut = React.useCallback((why) => {
    authGen.current++; refreshing.current = null;
    const old = sessionRef.current;
    if (old && old.refreshToken && clientIdRef.current) {      // revoke the refresh token too (best effort)
      fetch(((authCfgRef.current && authCfgRef.current.authority) || AUTH_DEFAULT) + '/token/revocation', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ token: old.refreshToken, token_type_hint: 'refresh_token', client_id: clientIdRef.current }).toString() }).catch(() => {});
    }
    saveJSON(AUTH_KEY, null);
    try { localStorage.setItem(AUTH_KEY + ':signedOut', '1'); } catch (e) {}
    setSession(null); setMenuOpen(false);
    if (why) setAuthErr(why);
  }, []);
  const refresh = React.useCallback(() => {
    if (refreshing.current) return refreshing.current;
    const stored = loadJSON(AUTH_KEY);                          // another tab may already have refreshed (rotating tokens)
    if (stored && stored.idToken && sessionRef.current && stored.exp > sessionRef.current.exp + 1000) { sessionRef.current = stored; setSession(stored); return Promise.resolve(stored); }
    const s = sessionRef.current, gen = authGen.current;
    if (!s || !s.refreshToken || !clientId) return Promise.reject(Object.assign(new Error('expired'), { status: 400 }));
    const p = tokenRequest(authority, { grant_type: 'refresh_token', refresh_token: s.refreshToken, client_id: clientId }).then((tok) => {
      if (gen !== authGen.current) throw Object.assign(new Error('superseded'), { superseded: true });
      const next = sessionFrom(tok, s);
      saveJSON(AUTH_KEY, next); sessionRef.current = next; setSession(next);
      return next;
    });
    refreshing.current = p;
    p.then(() => { refreshing.current = null; }, () => { refreshing.current = null; });
    return p;
  }, [authority, clientId]);
  refreshRef.current = refresh;
  React.useEffect(() => {
    if (!authLoaded || !session || !session.idToken) return;
    const wait = session.exp - Date.now() - 120000;
    const t = setTimeout(() => {
      refresh().catch((e) => {
        if (e.superseded) return;
        if (e.transient) { setTimeout(() => setRefreshNonce((n) => n + 1), 30000); return; }   // network hiccup: try again soon
        const stored = loadJSON(AUTH_KEY);
        if (stored && stored.idToken && stored.exp > Date.now() + 60000) { sessionRef.current = stored; setSession(stored); return; }   // another tab won the rotation
        signOut('Your sign-in expired. Sign in again to keep editing.');
      });
    }, Math.max(1000, wait - Math.random() * 30000));
    return () => clearTimeout(t);
  }, [authLoaded, session && session.exp, refresh, refreshNonce]);
  // Another tab signed in or out: follow it.
  React.useEffect(() => {
    const on = (e) => {
      if (e.key !== AUTH_KEY) return;
      const next = loadJSON(AUTH_KEY);
      const cur = sessionRef.current;
      if ((next && next.idToken) === (cur && cur.idToken)) return;
      authGen.current++; refreshing.current = null;
      sessionRef.current = next; setSession(next);
      if (next && next.idToken) { clearInterval(pollRef.current); setAuthBusy(null); }
    };
    window.addEventListener('storage', on);
    return () => window.removeEventListener('storage', on);
  }, []);
  React.useEffect(() => () => clearInterval(pollRef.current), []);

  const finishSignIn = React.useCallback(async (code, state) => {
    if (!code || !state) { setAuthBusy(null); setAuthErr('Sign-in didn’t finish. Try again.'); return; }
    const key = PKCE_KEY + ':' + state;
    const p = loadJSON(key);
    if (!p || p.state !== state) return;                          // not this tab's sign-in (or already finished)
    saveJSON(key, null);
    if (Date.now() - p.at > 15 * 60000) { setAuthErr('That sign-in took too long. Try again.'); setAuthBusy(null); return; }
    setAuthBusy('exchanging');
    const gen = ++authGen.current;
    try {
      const tok = await tokenRequest(p.authority, { grant_type: 'authorization_code', code, redirect_uri: p.redirectUri, client_id: p.clientId, code_verifier: p.verifier });
      if (gen !== authGen.current) return;
      const s = sessionFrom(tok);
      saveJSON(AUTH_KEY, s);
      try { localStorage.removeItem(AUTH_KEY + ':signedOut'); if (s.refreshToken) localStorage.setItem(AUTH_KEY + ':consented', '1'); } catch (e) {}
      setAuthErr(null); sessionRef.current = s; setSession(s);
    } catch (e) { setAuthErr('Sign-in failed: ' + errMsg(e)); }
    setAuthBusy(null);
  }, []);
  const signIn = React.useCallback(async (intent) => {
    if (!clientId) return;
    setAuthErr(null); setMenuOpen(false);
    if (intent && typeof intent === 'object' && !intent.nativeEvent) { try { sessionStorage.setItem(AUTH_KEY + ':intent', JSON.stringify(intent)); } catch (e) {} }
    const w = window.open('about:blank', 'sbauth', 'popup,width=480,height=720');   // open inside the click, before any await
    try { Object.keys(localStorage).forEach((k) => { if (k.startsWith(PKCE_KEY + ':')) { const v = loadJSON(k); if (!v || Date.now() - v.at > 15 * 60000) localStorage.removeItem(k); } }); } catch (e) {}
    const verifier = randomStr(32), state = randomStr(16);
    const challenge = await pkceChallenge(verifier);
    saveJSON(PKCE_KEY + ':' + state, { verifier, state, at: Date.now(), authority, clientId, redirectUri });
    let forceLogin = false, consented = false;
    try { forceLogin = localStorage.getItem(AUTH_KEY + ':signedOut') === '1'; consented = localStorage.getItem(AUTH_KEY + ':consented') === '1'; } catch (e) {}
    const prompt = [forceLogin ? 'login' : '', consented ? '' : 'consent'].filter(Boolean).join(' ');
    const params = { client_id: clientId, redirect_uri: redirectUri, response_type: 'code', scope: 'openid profile email offline_access', code_challenge: challenge, code_challenge_method: 'S256', state };
    if (prompt) params.prompt = prompt;
    const url = authority + '/auth?' + new URLSearchParams(params).toString();
    if (!w) { window.location.assign(url); return; }          // popups blocked: full-page sign-in, finished on return
    try { w.location.href = url; } catch (e) { window.location.assign(url); return; }
    setAuthBusy('popup');
    clearInterval(pollRef.current);
    pollRef.current = setInterval(() => {
      if (w.closed) { clearInterval(pollRef.current); setAuthBusy((b) => (b === 'popup' ? null : b)); return; }
      let href = ''; try { href = w.location.href; } catch (e) { return; }   // still on the sign-in site
      if (!href.startsWith(redirectUri)) return;
      clearInterval(pollRef.current);
      const u = new URL(href); try { w.close(); } catch (e) {}
      if (u.searchParams.get('error')) { setAuthBusy(null); setAuthErr(u.searchParams.get('error_description') || 'Sign-in was cancelled.'); return; }
      finishSignIn(u.searchParams.get('code'), u.searchParams.get('state'));
    }, 200);
  }, [clientId, authority, redirectUri]);
  // Full-page return from sign-in (popup blocked, a popup that lost its opener, or the iPhone app).
  React.useEffect(() => {
    if (isAuthPopup) return;
    const p = new URLSearchParams(window.location.search);
    const code = p.get('code'), state = p.get('state');
    if (!state || (!code && !p.get('error'))) return;
    if (!loadJSON(PKCE_KEY + ':' + state)) return;
    ['code', 'state', 'iss', 'session_state', 'error', 'error_description'].forEach((k) => p.delete(k));
    const qs = p.toString();
    window.history.replaceState({}, '', window.location.pathname + (qs ? '?' + qs : ''));
    if (code) finishSignIn(code, state); else { saveJSON(PKCE_KEY + ':' + state, null); setAuthErr('Sign-in was cancelled.'); }
  }, []);

  // ---- SpacetimeDB connection. If the socket drops, the connection is rebuilt with backoff
  // (1 s, 2 s, 4 s … 15 s); coming back to the tab, regaining focus or going online retries at once.
  React.useEffect(() => {
    if (!stdbAddr || isAuthPopup || !authLoaded) return;
    let cancelled = false, c = null, blobUrl = null, timer = null, stimer = null, retry = null, attempt = 0, mod = null, conf = null, connecting = false;
    const bump = () => { if (!timer) timer = setTimeout(() => { timer = null; if (!cancelled) setTick((t) => t + 1); }, 40); };
    const bumpStores = () => { if (!stimer) stimer = setTimeout(() => { stimer = null; if (!cancelled) setStoreTick((t) => t + 1); }, 60); };
    const lag = (ctx, at, orderId) => {
      if (!liveRef.current || Date.now() - liveSinceRef.current < 3000) return;          // warm-up: skip the initial load
      if (ctx && ctx.event && /Subscribe/i.test(String(ctx.event.tag || ''))) return;      // rows from (re)subscribing are not live
      const d = Math.max(1, Math.round(Date.now() + (offsetRef.current || 0) - ms(at)));   // corrected for the laptop's clock
      if (Date.now() + (offsetRef.current || 0) - ms(at) < -1500 || d > 10000) return;
      lagRef.current = [...lagRef.current.slice(-24), d];
      if (orderId != null && !orderLagRef.current.has(orderId)) orderLagRef.current.set(orderId, { ms: d });
    };
    const schedule = (why) => {
      if (cancelled || retry) return;
      setStatus(why);
      const wait = Math.min(15000, 1000 * Math.pow(2, attempt++));
      retry = setTimeout(() => { retry = null; open(); }, wait);
    };
    const pickToken = async () => {
      let s = sessionRef.current;
      if (s && s.idToken) {
        if (s.exp - Date.now() < 60000) {
          try { s = await refreshRef.current(); }
          catch (e) {
            if (e.transient && s.exp > Date.now() + 5000) return { mode: 'user', token: s.idToken };      // still valid for a moment
            if (!e.superseded) signOut('Your sign-in expired. Sign in again to keep editing.');
            return { mode: isOwner ? 'owner' : 'guest', token: isOwner ? conf.consoleToken : (loadJSON(GUEST_KEY) || {}).token };
          }
        }
        return { mode: 'user', token: s.idToken };
      }
      if (isOwner && conf.consoleToken) return { mode: 'owner', token: conf.consoleToken };
      return { mode: 'guest', token: (loadJSON(GUEST_KEY) || {}).token };
    };
    const open = async () => {
      if (cancelled) return;
      try { c && c.disconnect(); } catch (e) {}
      c = null;
      connecting = true;
      const { mode, token } = await pickToken();
      if (cancelled) return;
      let mine = null;
      let b = mod.DbConnection.builder().withUri(conf.host).withDatabaseName(conf.db);
      if (token) b = b.withToken(token);
      b = b.onConnect((cn, identity, tok) => {
        if (cancelled || cn !== mine) { try { cn.disconnect(); } catch (e) {} return; }
        connecting = false;
        if (mode === 'user') userFails.current = 0;
        if (mode === 'owner' && tok && tok !== conf.consoleToken) { conf.consoleToken = tok; FS.update(FS.ref(FS.db, secretAddr), { consoleToken: tok }).catch(() => {}); }
        // Clock offset to the server (best of three round trips), so "N ms" is honest on any laptop.
        (async () => { let best = null; for (let i = 0; i < 3; i++) { try { const t0 = Date.now(); const v = await cn.procedures.serverTime({}); const t1 = Date.now(); if (!best || t1 - t0 < best.rtt) best = { rtt: t1 - t0, off: Number(v / 1000n) - (t0 + t1) / 2 }; } catch (e) { break; } } if (best && cn === mine) offsetRef.current = best.off; })();
        if (mode === 'guest' && tok) saveJSON(GUEST_KEY, { token: tok });
        if (mode === 'user') Promise.resolve(cn.reducers.signIn({})).catch((e) => setAuthErr('The database did not accept this sign-in: ' + errMsg(e)));
        if (isOwner && conf.operatorCode) Promise.resolve(cn.reducers.claimOperator({ code: conf.operatorCode })).then(() => setOperator(true), () => setOperator(false));
        else setOperator(false);
        STORE_TABLES.forEach((tn) => { const t = cn.db[tn]; if (!t) return; t.onInsert(bumpStores); t.onDelete(bumpStores); if (t.onUpdate) t.onUpdate(bumpStores); });
        ROW_TABLES.forEach((tn) => { const t = cn.db[tn]; if (!t) return; t.onInsert(bump); t.onDelete(bump); if (t.onUpdate) t.onUpdate(bump); });
        cn.db.bagEvent.onInsert((x, row) => lag(x, row.at));
        cn.db.shopOrder.onInsert((x, row) => {
          if (demoT0Ref.current && hex(row.shopper) === demoIdRef.current) { orderLagRef.current.set(String(row.id), { ms: Math.round(performance.now() - demoT0Ref.current), demo: true }); demoT0Ref.current = 0; return; }
          lag(x, row.paidAt, String(row.id));
        });
        const ready = () => { if (cancelled || cn !== mine) return; attempt = 0; setConn(cn); setStatus('connected'); bump(); bumpStores(); };
        const sb = cn.subscriptionBuilder().onApplied(() => {
          if (cancelled || cn !== mine) return;
          // Re-load the open store on this connection BEFORE switching to it, so a reconnect never blanks the screen.
          const sel = selRef.current;
          if (!sel) return ready();
          const set = new Set(); subsRef.current.set(cn, set);
          let h = null;
          h = cn.subscriptionBuilder().onApplied(() => { lastScopeRef.current = { conn: cn, sel }; preloadRef.current = { conn: cn, sel, day: utcDay(0) }; liveRef.current = true; setScoped(sel); ready(); })
            .onError(() => ready()).subscribe(storeQueries(sel));
          set.add(h);
        });
        if (sb.onError) sb.onError(() => { if (cn === mine) schedule('reconnecting'); });
        sb.subscribe(GLOBAL_QUERIES);
      }).onConnectError((_x, err) => {
        connecting = false;
        if (cancelled) return;
        const authErr = /401|403|unauthori|forbidden|token|different app|invalid/i.test(String((err && (err.message || err)) || ''));
        if (mode === 'user') {
          const s = sessionRef.current;
          if (s && s.exp < Date.now() + 60000) { signOut('Your sign-in expired. Sign in again to keep editing.'); return; }
          if (authErr) { userFails.current = 0; signOut('This sign-in wasn’t accepted by the store database. You’re browsing as a guest.'); return; }
          if (navigator.onLine !== false && ++userFails.current >= 3) {
            // Only blame the token if a token-less connection works (so the network and server are fine).
            userFails.current = 0;
            let probe = null;
            new Promise((res) => { probe = mod.DbConnection.builder().withUri(conf.host).withDatabaseName(conf.db).onConnect(() => res(true)).onConnectError(() => res(false)).build(); setTimeout(() => res(false), 8000); })
              .then((ok) => { try { probe && probe.disconnect(); } catch (e) {} if (cancelled) return; if (ok) signOut('This sign-in wasn’t accepted by the store database. You’re browsing as a guest.'); else schedule('reconnecting'); });
            return;
          }
        }
        if (mode === 'guest' && token && authErr) saveJSON(GUEST_KEY, null);   // only a rejected guest token is thrown away
        schedule('reconnecting');
      });
      if (b.onDisconnect) b = b.onDisconnect(() => { connecting = false; if (!cancelled && c === mine) schedule('reconnecting'); });
      mine = b.build();
      c = mine;
    };
    (async () => {
      const snap = await FS.get(FS.ref(FS.db, stdbAddr));
      conf = { ...(snap.val() || {}) };
      if (!conf.client || !conf.client.code) throw new Error('SpacetimeDB client missing');
      delete conf.consoleToken; delete conf.operatorCode;
      if (isOwner) { try { const sec = (await FS.get(FS.ref(FS.db, secretAddr))).val() || {}; conf.consoleToken = sec.consoleToken; conf.operatorCode = sec.operatorCode; } catch (e) {} }
      if (cancelled) return;
      setCfg({ db: conf.db });
      blobUrl = URL.createObjectURL(new Blob([conf.client.code], { type: 'text/javascript' }));
      mod = await import(/* @vite-ignore */ blobUrl);
      modRef.current = mod; hostRef.current = { host: conf.host, db: conf.db };
      open();
    })().catch((e) => { if (!cancelled) setStatus('error: ' + errMsg(e)); });
    const wake = () => {
      if (cancelled || !mod || document.visibilityState !== 'visible') return;
      if (retry) { clearTimeout(retry); retry = null; attempt = 0; open(); }
      else if (c && !connecting && c.isActive === false) { attempt = 0; setStatus('reconnecting'); open(); }
    };
    document.addEventListener('visibilitychange', wake);
    window.addEventListener('focus', wake);
    window.addEventListener('online', wake);
    return () => {
      cancelled = true; clearTimeout(timer); clearTimeout(stimer); clearTimeout(retry);
      document.removeEventListener('visibilitychange', wake);
      window.removeEventListener('focus', wake);
      window.removeEventListener('online', wake);
      try { c && c.disconnect(); } catch (e) {}
      if (blobUrl) URL.revokeObjectURL(blobUrl);
      setConn(null); setOperator(null); setStatus('connecting');
    };
  }, [stdbAddr, isOwner, sessionKey, authLoaded]);

  // ---- downloads for Setup
  React.useEffect(() => {
    let alive = true;
    const url = (p, set) => { if (p) FS.getDownloadURL(FS.storageRef(FS.storage, ownerId + '/' + p)).then((u) => { if (alive) set(u); }).catch(() => {}); };
    url(android && android.apkPath, setApkUrl);
    url(android && android.sourcePath, setSrcUrl);
    url(ios && ios.sourcePath, setIosUrl);
    return () => { alive = false; };
  }, [android && android.apkPath, android && android.sourcePath, ios && ios.sourcePath]);
  React.useEffect(() => {
    if (!apkUrl || !QRLib) return;
    QRLib.toDataURL(apkUrl, { margin: 1, width: 320, color: { dark: '#18181b', light: '#ffffff' } }).then(setQr).catch(() => {});
  }, [apkUrl]);

  // ---- URL state: ?view= and ?store=; unrelated params (chatId…) are preserved
  const nav = React.useCallback((next, push) => {
    const p = new URLSearchParams(window.location.search);
    ['view', 'store'].forEach((k) => p.delete(k));
    Object.entries(next).forEach(([k, v]) => { if (v) p.set(k, v); });
    if (p.get('view') === 'live') p.delete('view');
    const qs = p.toString();
    const url = window.location.pathname + (qs ? '?' + qs : '');
    if (push) window.history.pushState({}, '', url); else window.history.replaceState({}, '', url);
    setRoute(readUrl());
  }, []);
  React.useEffect(() => {
    const on = () => setRoute(readUrl());
    window.addEventListener('popstate', on);
    return () => window.removeEventListener('popstate', on);
  }, []);
  // Old links (?view=store / app / hardware) keep working and are rewritten to today's names.
  React.useEffect(() => {
    const v = route.view, map = { store: 'settings', app: 'setup', hardware: 'setup' };
    if (map[v]) nav({ view: map[v], store: route.store }, false);
  }, []);
  const deepLinkRef = React.useRef(!!readUrl().store);      // the page was opened on a specific store

  // ---- who am I, which stores do I run
  const lsKey = 'smartbag.console.store:' + ownerId;
  const allStores = React.useMemo(() => (conn ? [...conn.db.store.iter()].sort((a, b) => a.name.localeCompare(b.name)) : []), [storeTick, conn]);
  const roles = React.useMemo(() => new Map(conn && conn.db.myStores ? [...conn.db.myStores.iter()].map((r) => [r.storeId, r]) : []), [storeTick, conn]);
  const account = React.useMemo(() => (conn && conn.db.myAccount ? [...conn.db.myAccount.iter()][0] || null : null), [storeTick, conn]);
  const team = React.useMemo(() => (conn && conn.db.myTeam ? [...conn.db.myTeam.iter()] : []), [storeTick, conn]);
  const invites = React.useMemo(() => (conn && conn.db.myInvites ? [...conn.db.myInvites.iter()] : []), [storeTick, conn]);
  const activity = React.useMemo(() => (conn && conn.db.myStoreActivity ? [...conn.db.myStoreActivity.iter()].sort((a, b) => ms(b.at) - ms(a.at)) : []), [storeTick, conn]);
  const admin = operator === true || !!(account && account.admin);
  const myStoreIds = React.useMemo(() => allStores.filter((s) => roles.has(s.id) && roles.get(s.id).role !== 'admin').map((s) => s.id), [allStores, roles]);
  const switcherOptions = React.useMemo(() => {
    const mine = new Set(myStoreIds);
    const others = allStores.filter((s) => !mine.has(s.id));
    const opt = (st) => <option key={st.id} value={st.id}>{st.name}</option>;
    return mine.size ? [
      <optgroup key="mine" label="Your stores">{allStores.filter((s) => mine.has(s.id)).map(opt)}</optgroup>,
      <optgroup key="all" label="Other stores">{others.slice(0, 200).map(opt)}</optgroup>,
      <option key="__browse" value="__browse">Browse all stores…</option>,
    ] : [...allStores.slice(0, 200).map(opt), ...(allStores.length > 200 ? [<option key="__browse" value="__browse">Browse all stores…</option>] : [])];
  }, [allStores, myStoreIds]);
  const signedIn = !!(session && session.idToken);
  const meName = (account && account.signedIn && account.name) || (session && session.name) || '';
  const selId = React.useMemo(() => {
    if (!allStores.length) return null;
    const has = (id) => id && allStores.some((s) => s.id === id);
    const isMine = (id) => roles.has(id) && roles.get(id).role !== 'admin';
    if (has(route.store)) return route.store;
    let last = null; try { last = localStorage.getItem(lsKey); } catch (e) {}
    if (has(last) && (isMine(last) || admin)) return last;           // admins manage every store, so their last one stays
    const mine = allStores.find((s) => isMine(s.id));
    if (mine) return mine.id;
    if (has(last)) return last;
    return allStores[0].id;
  }, [route.store, allStores, roles, admin]);
  selRef.current = selId;
  React.useEffect(() => {
    if (!selId) return;
    try { localStorage.setItem(lsKey, selId); } catch (e) {}
    if (route.store !== selId && route.view !== 'stores') nav({ view: route.view, store: selId }, false);
  }, [selId]);
  // Gaining a store (join code, email invite on sign-in, opening one) takes you there; signing in lands on
  // your own store unless the page was opened on a specific store.
  const prevMine = React.useRef(null);
  React.useEffect(() => {
    if (!conn) { prevMine.current = null; return; }
    const prev = prevMine.current;
    prevMine.current = new Set(myStoreIds);
    if (!prev) { if (signedIn && !deepLinkRef.current && myStoreIds.length && selId && !myStoreIds.includes(selId)) nav({ view: 'live', store: myStoreIds[0] }, false); return; }
    const added = myStoreIds.find((id) => !prev.has(id));
    if (added) { deepLinkRef.current = false; nav({ view: 'live', store: added }, true); }
  }, [myStoreIds.join(','), conn]);
  React.useEffect(() => { setConfirm(null); setOpen(null); setDraftTried(false); setShowInvite(false); setMapFailed(false); setFeedAll(false); }, [route.view, selId]);

  // ---- live rows for the selected store only. A new subscription is applied before the old one is
  // dropped, so a UTC-day rollover or a retry never blanks the screen; switching stores shows Loading.
  React.useEffect(() => { const cn = conn; return () => { const set = subsRef.current.get(cn); if (set) { set.forEach((h) => { try { h.unsubscribe(); } catch (e) {} }); subsRef.current.delete(cn); } }; }, [conn]);
  React.useEffect(() => {
    if (!conn || !selId) return;
    const pre = preloadRef.current;
    preloadRef.current = null;
    if (pre && pre.conn === conn && pre.sel === selId && pre.day === dayKey && !retryKey) return;   // already loaded while connecting
    let alive = true;
    const switching = lastScopeRef.current.conn !== conn || lastScopeRef.current.sel !== selId;
    lastScopeRef.current = { conn, sel: selId };
    if (switching) { setScoped(null); liveRef.current = false; lagRef.current = []; }
    setStoreErr(null);
    if (!subsRef.current.has(conn)) subsRef.current.set(conn, new Set());
    const set = subsRef.current.get(conn);
    let h = null;
    try {
      h = conn.subscriptionBuilder()
        .onApplied(() => {
          if (!alive) return;                                  // a newer subscription will clean up
          set.forEach((x) => { if (x !== h) { try { x.unsubscribe(); } catch (e) {} set.delete(x); } });
          if (!liveRef.current) liveSinceRef.current = Date.now();
          liveRef.current = true;
          setScoped(selId); setTick((t) => t + 1);
        })
        .onError((_x, err) => { if (alive) setStoreErr(errMsg(err)); })
        .subscribe(storeQueries(selId));
      set.add(h);
    } catch (e) { setStoreErr(errMsg(e)); }
    return () => { alive = false; };
  }, [conn, selId, dayKey, retryKey]);


  const storeRows = React.useMemo(() => {
    const f = storeSearch.trim().toLowerCase();
    return allStores
      .filter((s) => !f || s.name.toLowerCase().includes(f) || (s.address || '').toLowerCase().includes(f) || s.id.includes(f))
      .map((s) => ({ ...s, dist: geo.pos ? haversine(geo.pos, s) : null }))
      .sort((a, b) => (a.dist != null && b.dist != null ? a.dist - b.dist : 0) || ((roles.has(b.id) && roles.get(b.id).role !== 'admin' ? 1 : 0) - (roles.has(a.id) && roles.get(a.id).role !== 'admin' ? 1 : 0)));
  }, [allStores, storeSearch, geo.pos, roles]);

  // ---- derived data from the live client cache
  const d = React.useMemo(() => {
    const all = (tn) => (conn && conn.db[tn] ? [...conn.db[tn].iter()] : []);
    const names = new Map(all('myStorePeople').filter((p) => p.storeId === selId).map((p) => [hex(p.who), p.name]));
    const who = (h) => names.get(h) || 'Guest ' + shortId(h);
    const products = all('product').filter((p) => p.storeId === selId);
    const priceBySku = new Map(products.map((p) => [p.sku, p]));
    const cartBy = new Map();
    all('cartItem').forEach((c) => { if (c.storeId !== selId) return; const k = hex(c.shopper); if (!cartBy.has(k)) cartBy.set(k, []); cartBy.get(k).push(c); });
    const shoppers = all('shopper').filter((s) => s.storeId === selId).map((s) => {
      const k = hex(s.identity);
      const items = (cartBy.get(k) || []).slice().sort((a, b) => ms(a.addedAt) - ms(b.addedAt)).map((c) => {
        const p = priceBySku.get(c.sku);
        return { key: String(c.id), sku: c.sku, qty: c.qty, name: p ? p.name : 'Unknown item ' + c.sku, price: p ? p.priceCents : null, added: ms(c.addedAt) };
      });
      const priced = items.filter((i) => i.price != null);
      return { ...s, key: k, who: who(k), named: names.has(k), items, total: priced.reduce((a, i) => a + i.price * i.qty, 0), count: items.reduce((a, i) => a + i.qty, 0), seen: ms(s.updatedAt), leaving: s.outsideFixes > 0 };
    }).filter((s) => s.items.length || Date.now() - s.seen < 2 * 3600e3)
      .sort((a, b) => (b.items.length ? 1 : 0) - (a.items.length ? 1 : 0) || b.seen - a.seen);
    const linesBy = new Map();
    all('orderLine').forEach((l) => { if (l.storeId !== selId) return; const k = String(l.orderId); if (!linesBy.has(k)) linesBy.set(k, []); linesBy.get(k).push(l); });
    const orders = all('shopOrder').filter((o) => o.storeId === selId).map((o) => {
      const k = String(o.id), h = hex(o.shopper);
      return { ...o, key: k, who: who(h), at: ms(o.paidAt), lines: (linesBy.get(k) || []).slice().sort((a, b) => Number(a.id - b.id)) };
    }).sort((a, b) => b.at - a.at);
    const events = all('bagEvent').filter((e) => e.storeId === selId)
      .sort((a, b) => ms(b.at) - ms(a.at) || Number(b.id - a.id)).slice(0, 50)
      .map((e) => ({ key: String(e.id), at: ms(e.at), who: who(hex(e.shopper)), line: e.line, ...describe(e, priceBySku) }));
    return { products, priceBySku, shoppers, orders, events };
  }, [tick, conn, selId]);

  const sel = allStores.find((s) => s.id === selId) || null;
  const connected = status === 'connected' && !!conn;
  const R = connected ? conn.reducers : null;
  const role = selId ? roles.get(selId) : null;
  const canEdit = connected && !!selId && (admin || !!role);
  const canManage = canEdit && (admin || (role && role.role === 'owner'));       // owner/admin: team, reset, delete
  const roleLabel = admin ? 'Admin' : role && role.role === 'owner' ? 'Owner' : role ? 'Staff' : 'Viewer';
  const [lookAround, setLookAround] = React.useState(false);
  const needsWelcome = connected && signedIn && operator !== null && !admin && myStoreIds.length === 0 && !lookAround;
  const paidShown = useCountUp(paidTotalFor(d, selId));
  const todays = d.orders.filter((o) => isToday(o.at));
  const paidToday = todays.filter((o) => o.status === 'paid');
  const paidTotal = paidToday.reduce((a, o) => a + o.totalCents, 0);
  const dueToday = todays.length - paidToday.length;
  const srvNow = Date.now() + (offsetRef.current || 0);     // server-clock "now"
  const fresh = d.orders.filter((o) => o.trigger === 'walkout' && srvNow - o.at < RECEIPT_MS && srvNow - o.at > -5000);
  React.useEffect(() => { setFast(fresh.length > 0); }, [fresh.length > 0]);
  const lags = lagRef.current;
  const lagMs = lags.length >= 3 ? lags.slice().sort((a, b) => a - b)[Math.floor(lags.length / 2)] : null;
  const isNew = (t) => liveRef.current && t > liveSinceRef.current + (offsetRef.current || 0) - 2000;
  const storeTeam = team.filter((t) => t.storeId === selId);
  const storeActivity = activity.filter((a) => a.storeId === selId).slice(0, 12);

  // ---- actions (every database call times out instead of hanging if the socket drops mid-call)
  const say = (text, kind, extra) => {
    clearTimeout(noticeTimer.current);
    setNotice({ text, kind: kind || 'info', ...(extra || {}) });
    const arm = (msLeft) => { noticeTimer.current = setTimeout(() => { if (noticeHold.current) arm(1500); else setNotice(null); }, msLeft); };
    if (kind !== 'error') arm(extra && extra.undo ? 9000 : 4000);
  };
  React.useEffect(() => () => clearTimeout(noticeTimer.current), []);
  const call = (p) => withTimeout(p, 12000);
  const run = (label, p, okText) => call(p).then((v) => { if (okText) say(okText, 'ok'); return v; }, (e) => { say(label + ': ' + errMsg(e), 'error'); throw e; });
  // Edits to one store or product run one after another, each built from the row as confirmed by the
  // database after the previous edit, so two quick edits never undo each other.
  const queues = React.useRef(new Map());
  const enqueue = (entity, key, make) => {
    setFieldMsg((m) => ({ ...m, [key]: 'Saving…' }));
    const prev = queues.current.get(entity) || Promise.resolve();
    const next = prev.catch(() => {}).then(() => call(make()));
    queues.current.set(entity, next);
    next.then(() => {
      setFieldMsg((m) => ({ ...m, [key]: 'Saved' }));
      setTimeout(() => setFieldMsg((m) => (m[key] === 'Saved' ? { ...m, [key]: null } : m)), 1600);
    }, (e) => setFieldMsg((m) => ({ ...m, [key]: 'Not saved: ' + errMsg(e) })));
    next.finally(() => { if (queues.current.get(entity) === next) queues.current.delete(entity); });
  };
  const saveStore = (key, s, patch) => {
    if (!R) return;
    enqueue('store:' + s.id, key, () => {
      const cur = (conn.db.store.id && conn.db.store.id.find(s.id)) || s;
      return R.upsertStore({ id: cur.id, name: cur.name, address: cur.address, lat: cur.lat, lng: cur.lng, radiusM: cur.radiusM, ...patch });
    });
  };
  const saveProduct = (key, p, patch) => {
    if (!R) return;
    enqueue('product:' + p.storeId + ':' + p.sku, key, () => {
      const cur = [...conn.db.product.iter()].find((x) => x.storeId === p.storeId && x.sku === p.sku) || p;
      return R.upsertProduct({ storeId: cur.storeId, sku: cur.sku, name: cur.name, priceCents: cur.priceCents, unit: cur.unit || '', ...patch });
    });
  };
  const removeProduct = (p) => {
    if (!R) return;
    run('Remove ' + p.name, R.deleteProduct({ storeId: p.storeId, sku: p.sku })).then(() => say('Removed ' + p.name + '.', 'ok', { undo: { storeId: p.storeId, sku: p.sku, name: p.name, priceCents: p.priceCents, unit: p.unit || '' } }), () => {});
  };
  const undo = () => { const u = notice && notice.undo; if (!u || !R) return; setNotice(null); run('Restore ' + u.name, R.upsertProduct(u), 'Restored ' + u.name + '.').catch(() => {}); };
  const locate = () => new Promise((res, rej) => {
    if (!navigator.geolocation) { const m = 'Location is not available in this browser.'; setGeo({ status: 'err', pos: null, err: m }); return rej(new Error(m)); }
    setGeo((g) => ({ ...g, status: 'asking', err: null }));
    navigator.geolocation.getCurrentPosition(
      (p) => { const pos = { lat: p.coords.latitude, lng: p.coords.longitude, acc: p.coords.accuracy }; setGeo({ status: 'ok', pos, err: null }); res(pos); },
      (e) => { const m = e.code === 1 ? 'Location is blocked for this site. Allow it in the browser’s site settings.' : 'Could not get your location. Try again.'; setGeo((g) => ({ ...g, status: 'err', err: m })); rej(new Error(m)); },
      { enableHighAccuracy: true, timeout: 20000, maximumAge: 30000 });
  });
  const needsSignIn = authReady && !signedIn;
  const openStoreNamed = (raw) => {
    if (!R || !conn) return;
    const name = (raw || '').trim() || (meName ? meName.split(' ')[0] + '’s Market' : 'Demo Market');
    locate().then((pos) => {
      const cn = conn;
      if (admin) {     // admins don't get the "new store of mine" jump below, so follow the insert
        let done = false;
        const onIns = (_x, s) => { if (!done && s.name === name.slice(0, 80) && haversine(pos, s) < 50) { done = true; try { cn.db.store.removeOnInsert(onIns); } catch (er) {} nav({ view: 'live', store: s.id }, true); } };
        cn.db.store.onInsert(onIns);
        setTimeout(() => { if (!done) { done = true; try { cn.db.store.removeOnInsert(onIns); } catch (er) {} } }, 6000);
      }
      return run('Open store', R.pinStore({ name, lat: pos.lat, lng: pos.lng }), name + ' is open. Phones here now land in it.').then(() => setPinName(''));
    }).catch(() => {});
  };
  const pinHere = (e) => {
    e.preventDefault();
    if (needsSignIn) { signIn({ pin: pinName }); return; }
    openStoreNamed(pinName);
  };
  const moveHere = (s) => { if (!R) return; locate().then((pos) => saveStore('loc', s, { lat: pos.lat, lng: pos.lng })).catch(() => {}); };
  const join = (e) => {
    e.preventDefault();
    const code = joinCode.trim().toUpperCase().replace(/\s+/g, '');
    if (needsSignIn && code) { signIn({ join: code }); return; }
    if (!R || !code) return;
    run('Join', R.joinStore({ code }), 'You’re on the team. The store is in your list now.').then(() => setJoinCode(''), () => {});
  };
  const invite = (e) => {
    e.preventDefault();
    const em = inviteEmail.trim().toLowerCase();
    if (!R || !sel || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(em)) { setFieldMsg((m) => ({ ...m, invite: 'Not sent: enter an email like sam@store.com' })); return; }
    setFieldMsg((m) => ({ ...m, invite: null }));
    run('Invite', R.inviteStaff({ storeId: sel.id, email: em }), em + ' is invited. They’ll see it after signing in with that email.').then(() => setInviteEmail(''), () => {});
  };
  const saveName = (e) => {
    e.preventDefault();
    const n = (nameDraft || '').trim();
    if (!R || !n) return;
    run('Rename', R.setDisplayName({ name: n }), 'Your name is now ' + n + '.').then(() => setNameDraft(null), () => {});
  };
  const addProduct = (e) => {
    e.preventDefault();
    setDraftTried(true);
    const sku = draft.sku.trim(), name = draft.name.trim(), cents = parsePrice(draft.price);
    if (!R || !sel || !SKU_RE.test(sku) || !name || cents == null) return;
    const exists = d.priceBySku.has(sku);
    run((exists ? 'Update ' : 'Add ') + name, R.upsertProduct({ storeId: sel.id, sku, name, priceCents: cents, unit: draft.unit.trim() }), (exists ? 'Updated ' : 'Added ') + name + '.')
      .then(() => { setDraft({ sku: '', name: '', price: '', unit: '' }); setDraftTried(false); }, () => {});
  };
  // Finish what a guest started before signing in (open a store / join a team).
  React.useEffect(() => {
    if (!connected || !signedIn || !(account && account.signedIn)) return;      // only on the signed-in connection
    let intent = null; try { intent = JSON.parse(sessionStorage.getItem(AUTH_KEY + ':intent') || 'null'); sessionStorage.removeItem(AUTH_KEY + ':intent'); } catch (e) {}
    if (!intent) return;
    if (intent.join) run('Join', R.joinStore({ code: intent.join }), 'You’re on the team.').catch(() => {});
    else if (intent.pin != null) openStoreNamed(intent.pin);
  }, [conn, connected, signedIn, account && account.signedIn]);
  const copy = async (what, text) => { const r = await copyText(text); setCopied(r === 'failed' ? 'failed:' + what : what); setTimeout(() => setCopied(null), 1600); };
  React.useEffect(() => {
    if (!menuOpen) return;
    const close = (e) => { if (!(e.target.closest && e.target.closest('[data-sbc-menu]'))) setMenuOpen(false); };
    const esc = (e) => { if (e.key === 'Escape') setMenuOpen(false); };
    document.addEventListener('pointerdown', close); document.addEventListener('keydown', esc);
    return () => { document.removeEventListener('pointerdown', close); document.removeEventListener('keydown', esc); };
  }, [menuOpen]);

  const wide = width >= 940;
  const narrow = width > 0 && width < 480;
  const vars = { '--ink': pal.ink, '--muted': pal.muted, '--faint': pal.faint, '--line': pal.line, '--strong': pal.strong, '--surface': pal.surface, '--sunken': pal.sunken, '--hover': pal.hover, '--field': pal.field, '--acc': pal.acc, '--ok': pal.ok, '--err': pal.err, colorScheme: dark ? 'dark' : 'light' };
  const box = 'rounded-2xl border hl surface';

  if (!stdbAddr) return <div ref={rootRef} className="w-full p-6 text-sm">Open this app from its page in Mana.</div>;
  if (isAuthPopup) return <div ref={rootRef} className="w-full p-10 text-center text-sm" style={{ color: pal.muted }}>Finishing sign-in…</div>;

  const Section = (title, right, body, first) => (
    <section key={title} className={first ? '' : 'pt-7'}>
      <div className="flex items-baseline justify-between gap-3 pb-2">
        <h2 className="cap">{title}</h2>
        {right ? <div className="text-xs muted text-right">{right}</div> : null}
      </div>
      {body}
    </section>
  );
  const Note = (text) => <div className="py-14 text-center text-sm muted">{text}</div>;
  const Msg = (key) => fieldMsg[key] ? <span role="status" className={'text-xs ' + (String(fieldMsg[key]).startsWith('Not ') ? 'err' : 'faint')}>{fieldMsg[key]}</span> : null;
  const Avatar = (name, size) => (
    <span aria-hidden="true" className="inline-flex items-center justify-center rounded-full shrink-0 font-semibold" style={{ width: size || 24, height: size || 24, fontSize: (size || 24) * 0.42, background: pal.hover, color: pal.ink, border: '1px solid ' + pal.line }}>{initials(name)}</span>
  );

  // ---- Live: the floor (shoppers + walk-out receipts) and the bag feed
  const Receipt = (o, still) => {
    if (!seenReceipts.current.has(o.key)) seenReceipts.current.set(o.key, Date.now());
    const firstShown = Date.now() - seenReceipts.current.get(o.key) < 1500;
    const anim = !still && isNew(o.at) && firstShown;                        // animate once, never on remount
    const left = leftRef.current.get(hex(o.shopper));
    const handoff = anim && left && Date.now() - left < 15000;                // this card was a leaving shopper a moment ago
    const age = Math.max(0, (Date.now() + (offsetRef.current || 0) - o.at) / 1000);
    const lag = orderLagRef.current.get(o.key);
    const due = o.status !== 'paid';
    const stamp = lag ? (lag.demo ? 'phone to screen in ' + lag.ms + ' ms' : 'on screen ' + lag.ms + ' ms after the charge') : null;
    return (
      <div role="group" className={'relative overflow-hidden rounded-2xl border hl surface ' + (anim && !handoff ? 'sbc-receipt' : '')} aria-label={(due ? 'Payment due ' : 'Paid ') + fmt(o.totalCents) + ' by ' + o.who + ', order ' + o.code}>
        <div className="px-4 pt-4 pb-4">
          <div className={narrow ? '' : 'flex items-start gap-3'}>
            <div className="min-w-0 flex-1">
              <div className="text-sm font-semibold"><span className={due ? 'err' : 'ok'}>{due ? 'Payment due' : 'Paid'}</span><span className="faint font-normal"> · walked out, no checkout</span></div>
              <div className="mt-0.5 text-xs muted truncate">{o.who} · {plural(o.itemCount, 'item')} · <span className="mono">{o.code}</span></div>
            </div>
            <div className={(narrow ? 'mt-2 ' : '') + 'text-right ' + (anim ? 'sbc-stamp' : '')}>
              <div className="tnum font-semibold tracking-tight ink" style={{ fontSize: 28, lineHeight: 1 }}>{fmt(o.totalCents)}</div>
              {stamp ? <div className="mt-1 mono text-xs faint whitespace-nowrap" title="Measured on this screen; corrected for the difference between this device's clock and the database's">{stamp}</div> : null}
            </div>
          </div>
          <div className="mt-3 pt-2 border-t hl text-xs muted space-y-1">
            {o.lines.slice(0, 4).map((l) => (
              <div key={String(l.id)} className="flex gap-3"><span className="flex-1 truncate">{l.qty > 1 ? l.qty + ' × ' : ''}{l.name}</span><span className="tnum">{fmt(l.priceCents * l.qty)}</span></div>
            ))}
            {o.lines.length > 4 ? <div className="faint">and {o.lines.length - 4} more</div> : null}
            {o.unpricedCount ? <div className="faint">{plural(o.unpricedCount, 'item')} not sold here, not charged</div> : null}
          </div>
          <div className="mt-2 text-xs faint">{due ? 'No card on file · bag emptied, payment due' : (o.payment || 'Saved card') + ' · charged and bag emptied in one transaction'}</div>
        </div>
        {/* One coral line tells the story: half full while leaving, complete at the charge, then it drains for the minute the receipt stays here. */}
        {!still && <div className="absolute left-0 right-0 bottom-0" aria-hidden="true" style={{ height: 2, background: due ? pal.err : ACCENT, transformOrigin: 'left',
          animation: (handoff ? 'sbcComplete .3s cubic-bezier(.2,.8,.2,1) both, ' : '') + 'sbcAge ' + RECEIPT_MS / 1000 + 's linear ' + (handoff ? '.6s' : '-' + age + 's') + ' both' }} />}
      </div>
    );
  };
  const Shopper = (s, i) => (
    <div key={s.key} className={'relative px-4 py-3 ' + (i ? 'border-t hl' : '')}>
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <div className="text-sm font-medium ink truncate">{s.who}{s.bag ? <span className="faint font-normal"> · {s.bag}</span> : null}</div>
          <div className="text-xs mt-0.5">
            {s.leaving
              ? <span className="acc font-medium">Leaving · confirming {Math.min(s.outsideFixes, 2)} of 2 · {s.items.length ? 'charges as they step out' : 'nothing to charge'}</span>
              : <span className="muted">In store · {fmtDist(s.distanceM)} from the pin</span>}
            {!s.payment && s.items.length ? <span className="err"> · no card on file</span> : null}
          </div>
        </div>
        <div className="text-right shrink-0">
          <div className={'text-sm tnum ' + (s.items.length ? 'ink font-medium' : 'faint')}>{s.items.length ? fmt(s.total) : 'empty bag'}</div>
          <div className="text-xs faint whitespace-nowrap">{ago(s.seen)}</div>
        </div>
      </div>
      {s.items.length > 0 && (
        <div className="mt-2 space-y-1" style={{ fontSize: 13 }}>
          {s.items.map((it) => (
            <div key={it.key} className={'flex gap-3 rounded ' + (isNew(it.added) ? 'sbc-new' : '')}>
              <span className={'flex-1 truncate ' + (it.price == null ? 'faint' : 'muted')}>{it.qty > 1 ? it.qty + ' × ' : ''}{it.name}</span>
              <span className={'tnum ' + (it.price == null ? 'err text-xs' : 'muted')}>{it.price == null ? 'not sold here' : fmt(it.price * it.qty)}</span>
            </div>
          ))}
        </div>
      )}
      {s.leaving && <div data-leave-bar="" className="absolute left-0 bottom-0" style={{ height: 2, width: Math.min(100, (s.outsideFixes / 2) * 100) + '%', background: !s.payment && s.items.length ? pal.err : ACCENT, transition: 'width .4s' }} aria-hidden="true" />}
    </div>
  );
  const FeedRow = (e, i) => {
    const walk = e.kind === 'walkout';
    return (
      <li key={e.key} className={'px-4 py-2.5 flex items-baseline gap-3 ' + (i ? 'border-t hl ' : '') + (isNew(e.at) ? 'sbc-new' : '')} title={walk ? undefined : 'Bag sent: ' + e.line}>
        {walk
          ? <span className="text-sm min-w-0 flex-1 truncate"><span className="ink font-medium">{e.who} walked out</span>{e.code ? <span className="mono faint"> · {e.code}</span> : null}</span>
          : <span className={'text-sm min-w-0 flex-1 truncate ' + (e.kind === 'warn' ? 'muted' : 'ink')}><span className="faint">{e.who}</span> {e.text}{e.kind === 'warn' ? <span className="mono faint"> · {e.line}</span> : null}</span>}
        {walk
          ? <span className={'text-sm tnum font-medium ' + (e.due ? 'err' : 'ink')}>{e.nothing ? 'nothing to charge' : (e.due ? 'due ' : '') + e.amount}</span>
          : e.amount ? <span className="text-sm tnum muted">{e.amount}</span> : null}
        <span className="text-xs faint shrink-0 text-right" style={{ minWidth: 56 }}>{ago(e.at)}</span>
      </li>
    );
  };
  const demoShopper = async () => {
    const s = sel, mod = modRef.current, h = hostRef.current;
    if (!s || !mod || !h || demoBusy) return;
    setDemoBusy('Connecting a phone…');
    let built = null;
    try {
      const c = await withTimeout(new Promise((res, rej) => { built = mod.DbConnection.builder().withUri(h.host).withDatabaseName(h.db).onConnect((cn, id) => { demoIdRef.current = hex(id); res(cn); }).onConnectError((_x, e) => rej(e)).build(); }), 10000, 'Could not start the demo shopper.');
      const R2 = c.reducers, wait = (t) => new Promise((r) => setTimeout(r, t)), out = (s.radiusM + 90) / 111320;
      const picks = d.products.slice().sort(() => Math.random() - 0.5).slice(0, 3).map((p) => p.sku);
      await Promise.all([R2.setPayment({ label: 'Demo Visa •••• 4242' }), R2.setBag({ name: 'Demo tote' })]);
      setDemoBusy('Walking in…');
      await R2.updateLocation({ lat: s.lat, lng: s.lng, accuracyM: 8 });
      setDemoBusy('Shopping…');
      for (const sku of picks) { await wait(1300); await R2.bagLine({ line: '+' + sku + '@D' + randomStr(3) }); }
      setDemoBusy('Walking out…');
      await wait(2200); await R2.updateLocation({ lat: s.lat + out, lng: s.lng, accuracyM: 8 });
      await wait(2200);
      demoT0Ref.current = performance.now();               // one clock: from the phone's last fix to the receipt on this screen
      await R2.updateLocation({ lat: s.lat + out * 1.1, lng: s.lng, accuracyM: 8 });
    } catch (e) { demoT0Ref.current = 0; say('Demo shopper: ' + errMsg(e), 'error'); }
    setTimeout(() => { try { built && built.disconnect(); } catch (e) {} }, 800);
    setTimeout(() => { demoT0Ref.current = 0; }, 8000);
    setDemoBusy(null);
  };
  const Welcome = () => (
    <div className="space-y-3">
      <div className={box + ' p-5'}>
        <div className="flex flex-wrap items-center gap-3">
          <div className="min-w-0 flex-1" style={{ minWidth: 200 }}>
            <div className="text-sm ink font-medium">The floor is empty.</div>
            <div className="text-sm muted">Bags appear here the moment a phone walks in, and turn into receipts when it walks out.</div>
          </div>
          {canEdit && <button className="btn btn-primary" disabled={!!demoBusy || !d.products.length} onClick={demoShopper}>{demoBusy || 'Run a demo shopper'}</button>}
        </div>
        <details className="mt-4 pt-3 border-t hl">
          <summary className="text-sm muted cursor-pointer select-none">How to start a real trip</summary>
          <div className="mt-3 flex flex-wrap items-start gap-5">
            <ol className="flex-1 space-y-2 text-sm muted" style={{ minWidth: 220, paddingLeft: 18, listStyle: 'decimal' }}>
              <li>Install SmartBag on an Android phone (scan the code).</li>
              <li>Open it near {sel ? sel.name : 'the store'}; it lands in the store by itself.</li>
              <li>Drop items in the bag. No bag yet? Long-press the bag status in the app.</li>
              <li>Walk out, or long-press the store name to simulate it.</li>
            </ol>
            {qr ? <img src={qr} alt="QR code: download the SmartBag Android app" className="rounded-lg border hl" style={{ width: 112, height: 112 }} /> : null}
          </div>
        </details>
      </div>
      {d.orders[0] && (
        <div>
          <div className="text-xs faint pb-1.5">Last walk-out · {ago(d.orders[0].at)}</div>
          {Receipt(d.orders[0], true)}
        </div>
      )}
    </div>
  );
  // First run for a signed-in person who doesn't run a store yet: two ways in, nothing else.
  const GetStarted = () => (
    <div className={box + ' p-5'}>
      <div className="text-base font-semibold tracking-tight ink">Welcome{meName ? ', ' + meName.split(' ')[0] : ''}. Set up your store.</div>
      <div className="mt-1 text-sm muted">Open a new store where you are standing, or join a team. Invites sent to your email show up right here.</div>
      {invites.length ? <div className="mt-4">{InviteBanner()}</div> : null}
      <div className="mt-4 grid gap-4" style={{ gridTemplateColumns: width >= 640 ? '1fr 1fr' : '1fr' }}>
        <form onSubmit={pinHere} className="rounded-xl sunken p-4">
          <div className="text-sm font-medium ink">Open a store here</div>
          <div className="mt-0.5 text-xs muted">Uses this device’s location and a starter catalog you can edit.</div>
          <div className="mt-3 flex gap-2">
            <input value={pinName} onChange={(e) => setPinName(e.target.value)} maxLength={80} placeholder="Store name" aria-label="New store name" className="field" />
            <button type="submit" disabled={!R || geo.status === 'asking'} className="btn btn-primary shrink-0">{geo.status === 'asking' ? 'Locating…' : 'Open'}</button>
          </div>
          {geo.err && <div className="mt-1.5 text-xs err">{geo.err}</div>}
        </form>
        <form onSubmit={join} className="rounded-xl sunken p-4">
          <div className="text-sm font-medium ink">Join with a code</div>
          <div className="mt-0.5 text-xs muted">Ask a teammate for the store’s 8-letter join code.</div>
          <div className="mt-3 flex gap-2">
            <input value={joinCode} onChange={(e) => setJoinCode(e.target.value)} placeholder="ABCD2345" aria-label="Join code" autoComplete="off" spellCheck={false} className="field mono uppercase" maxLength={12} />
            <button type="submit" disabled={(!R && !needsSignIn) || !joinCode.trim()} className="btn shrink-0">{needsSignIn ? 'Sign in to join' : 'Join'}</button>
          </div>
        </form>
      </div>
      {allStores.length > 0 && <button className="mt-4 text-sm muted underline underline-offset-2" onClick={() => setLookAround(true)}>Or look around a demo store first</button>}
    </div>
  );
  const SignInBanner = () => (!canEdit && connected && !isOwner) ? (
    <div className="text-sm muted">{signedIn ? 'You’re viewing ' + (sel ? sel.name : 'this store') + '. Only its team can make changes.' : authReady ? 'You’re looking around as a guest. Sign in (top right) to run your own store or join your team.' : 'Viewing only. Sign-in for store teams is coming soon.'}</div>
  ) : null;
  const InviteBanner = () => invites.length ? (
    <div className="space-y-2">
      {invites.map((inv) => (
        <div key={String(inv.id)} className={box + ' p-4 flex flex-wrap items-center gap-3'}>
          <div className="min-w-0 flex-1 text-sm" style={{ minWidth: 200 }}><span className="ink font-medium">{inv.invitedBy}</span> <span className="muted">invited you to the {inv.storeName} team.</span></div>
          <div className="flex gap-2">
            <button className="btn btn-sm" disabled={!R} onClick={() => run('Decline', R.declineInvite({ id: inv.id }), 'Invite declined.').catch(() => {})}>Decline</button>
            <button className="btn btn-sm btn-primary" disabled={!R} onClick={() => run('Join', R.acceptInvite({ id: inv.id }), 'Welcome to the ' + inv.storeName + ' team.').catch(() => {})}>Join team</button>
          </div>
        </div>
      ))}
    </div>
  ) : null;
  const LiveView = () => {
    const inBags = d.shoppers.reduce((a, s) => a + s.count, 0);
    const open$ = d.shoppers.reduce((a, s) => a + s.total, 0);
    fresh.forEach((o) => { if (!seenReceipts.current.has(o.key)) seenReceipts.current.set(o.key, Date.now()); });
    const latest = fresh[0];
    const delta = latest && latest.status === 'paid' && isNew(latest.at) && seenReceipts.current.has(latest.key) && Date.now() - seenReceipts.current.get(latest.key) < 4000 ? latest.totalCents : 0;
    const stats = [
      [String(d.shoppers.length), 'shoppers in store', null, d.shoppers.length === 0],
      [String(inBags), 'items in bags', null, inBags === 0],
      [fmt(open$), 'in open bags', null, open$ === 0],
      [fmt(paidShown), 'paid today', dueToday ? dueToday + ' due' : null, paidTotal === 0, delta],
    ];
    const leaving = d.shoppers.filter((s) => s.leaving), inStore = d.shoppers.filter((s) => !s.leaving);
    leaving.forEach((s) => leftRef.current.set(s.key, Date.now()));
    const visible = d.events.filter((e) => e.kind !== 'info');
    const feedRows = wide || feedAll ? visible : visible.slice(0, 12);
    // The walk-out handoff: a leaving shopper and its receipt share one keyed slot (the shopper's identity),
    // so when SpacetimeDB empties the bag and writes the order in one transaction, the card becomes the
    // receipt in the same place on the same frame.
    const used = new Set();
    const slotKey = (h, alt) => { const k = used.has(h) ? h + ':' + alt : h; used.add(k); return k; };
    const slots = [
      ...leaving.map((s) => ({ k: slotKey(s.key, 's'), s })),
      ...fresh.map((o) => ({ k: slotKey(hex(o.shopper), o.key), o })),
    ];
    const floor = (
      <div className="min-w-0 space-y-3">
        <div className="flex items-center justify-between gap-3 h-7"><h2 className="cap">On the floor</h2>
          {canEdit && (d.shoppers.length || fresh.length) ? <button className="btn btn-sm" disabled={!!demoBusy || !d.products.length} onClick={demoShopper}>{demoBusy || 'Run a demo shopper'}</button> : <span className="text-xs faint">charged automatically on walk-out</span>}
        </div>
        {slots.map((x) => <div key={'slot:' + x.k} style={{ interpolateSize: 'allow-keywords', transition: 'height .25s' }}>{x.o ? Receipt(x.o) : <div className={'relative overflow-hidden rounded-2xl border hl surface'}>{Shopper(x.s, 0)}</div>}</div>)}
        {inStore.length > 0 && <div className={box + ' overflow-hidden'}>{inStore.map((s, i) => Shopper(s, i))}</div>}
        {!d.shoppers.length && !fresh.length ? Welcome() : null}
      </div>
    );
    const feed = (
      <div className="min-w-0 space-y-3" style={wide ? { position: 'sticky', top: 0, alignSelf: 'start' } : null}>
        <div className="flex items-baseline justify-between"><h2 className="cap">Bag feed</h2><span className="text-xs faint">last hour</span></div>
        <div className={box + ' overflow-hidden'}>
          {visible.length ? <ul aria-label="Bag feed" className={wide ? 'overflow-y-auto' : ''} style={wide ? { maxHeight: 'min(560px, calc(var(--mana-app-height, 82vh) - 220px))' } : null}>{feedRows.map((e, i) => FeedRow(e, i))}</ul>
            : <div className="px-4 py-6 text-sm muted">Bag readings appear here the moment a phone forwards them.</div>}
          {!wide && visible.length > 12 && !feedAll && <button className="w-full border-t hl px-4 py-2.5 text-sm muted hov text-left" onClick={() => setFeedAll(true)}>Show all {visible.length}</button>}
        </div>
      </div>
    );
    return (
      <div className="space-y-6">
        {InviteBanner()}
        {SignInBanner()}
        <div className="grid border-b hl" style={{ gridTemplateColumns: width >= 560 ? 'repeat(4, minmax(0,1fr))' : 'repeat(2, minmax(0,1fr))' }}>
          {stats.map(([v, l, extra, zero, plus], i) => (
            <div key={l} className={'pb-3 ' + (width >= 560 ? (i ? 'pl-4 border-l hl' : 'pr-4') : (i % 2 ? 'pl-4 border-l hl' : 'pr-4') + (i > 1 ? ' pt-3 border-t hl' : ''))}>
              <div className="flex items-baseline gap-2">
                <span className={'tnum font-semibold tracking-tight ' + (zero ? 'faint' : 'ink')} style={{ fontSize: 28, lineHeight: 1.15 }}>{v}</span>
                {plus ? <span key={'p' + latest.key} className="sbc-plus text-sm font-medium tnum acc">+{fmt(plus)}</span> : null}
              </div>
              <div className="text-xs muted">{l}{extra ? <span className="err"> · {extra}</span> : null}</div>
            </div>
          ))}
        </div>
        {wide ? <div className="grid gap-6" style={{ gridTemplateColumns: 'minmax(0,1.35fr) minmax(0,1fr)' }}>{floor}{feed}</div> : <div className="space-y-6">{floor}{feed}</div>}
      </div>
    );
  };

  // ---- Orders
  const OrdersView = () => {
    const groups = [];
    d.orders.forEach((o) => { const g = dayLabel(o.at); const last = groups[groups.length - 1]; if (last && last.label === g) last.items.push(o); else groups.push({ label: g, items: [o] }); });
    return (
      <div className="space-y-6">
        <div className="grid border-b hl" style={{ gridTemplateColumns: 'repeat(3, minmax(0,1fr))' }}>
          {[[fmt(paidTotal), 'paid today'], [String(todays.length), 'walk-outs today'], [String(dueToday), 'payment due']].map(([v, l], i) => (
            <div key={l} className={'pb-3 ' + (i ? 'pl-4 border-l hl' : 'pr-4')}>
              <div className={'tnum font-semibold tracking-tight ' + (l === 'payment due' && dueToday ? 'err' : (v === '0' || v === '$0.00') ? 'faint' : 'ink')} style={{ fontSize: 28, lineHeight: 1.15 }}>{v}</div>
              <div className="text-xs muted">{l}</div>
            </div>
          ))}
        </div>
        {!d.orders.length && Note('No walk-outs yet. A bag is charged and shows up here the moment its shopper leaves the store.')}
        {groups.map((g) => Section(g.label, plural(g.items.length, 'order') + ' · ' + fmt(g.items.filter((o) => o.status === 'paid').reduce((a, o) => a + o.totalCents, 0)) + ' paid', (
          <div className={box + ' overflow-hidden'}>
            {g.items.map((o, i) => {
              const isOpen = open === o.key;
              return (
                <div key={o.key} className={(i ? 'border-t hl ' : '') + (isNew(o.at) ? 'sbc-new' : '')}>
                  <button onClick={() => setOpen(isOpen ? null : o.key)} aria-expanded={isOpen} className="w-full text-left px-4 py-3 flex items-center gap-3 hov">
                    <div className="min-w-0 flex-1">
                      <div className="text-sm ink truncate">{o.who}<span className="muted"> · <span className="mono">{o.code}</span></span></div>
                      <div className="text-xs muted">{clock(o.at)} · {plural(o.itemCount, 'item')}{o.status === 'paid' ? null : <span className="err"> · payment due</span>}{o.unpricedCount ? ' · ' + o.unpricedCount + ' not sold here' : ''}</div>
                    </div>
                    <div className="text-sm tnum ink font-medium">{fmt(o.totalCents)}</div>
                    <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true" className="faint shrink-0" style={{ transform: isOpen ? 'rotate(90deg)' : 'none', transition: 'transform .15s' }}><path d="M4.5 2.5 8 6l-3.5 3.5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" /></svg>
                  </button>
                  {isOpen && (
                    <div className="px-4 pb-4 text-sm">
                      <div className="rounded-xl sunken px-3 py-2">
                        {o.lines.map((l) => (
                          <div key={String(l.id)} className="flex gap-3 py-1"><span className="flex-1 truncate ink">{l.name}</span><span className="muted tnum">{l.qty} × {fmt(l.priceCents)}</span><span className="tnum ink" style={{ minWidth: 64, textAlign: 'right' }}>{fmt(l.priceCents * l.qty)}</span></div>
                        ))}
                        {!o.lines.length && <div className="py-1 muted">Line items are loading.</div>}
                        <div className="flex gap-3 pt-2 mt-1 border-t hl font-medium"><span className="flex-1 ink">Total</span><span className="tnum ink">{fmt(o.totalCents)}</span></div>
                      </div>
                      <div className="mt-2 text-xs muted">{o.status === 'paid' ? (o.payment || 'Saved card') + ' charged automatically' : 'No card on file, payment due'} when {o.who} walked out at {clock(o.at)}.{o.unpricedCount ? ' ' + plural(o.unpricedCount, 'item') + ' in the bag ' + (o.unpricedCount === 1 ? 'is' : 'are') + ' not sold here and ' + (o.unpricedCount === 1 ? 'was' : 'were') + ' not charged.' : ''}</div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        ), g === groups[0]))}
        {d.orders.length > 0 && <div className="text-xs faint">Showing the last few days. Payments are simulated.</div>}
      </div>
    );
  };
  // ---- Settings for the selected store
  const SettingsView = () => {
    const s = sel;
    if (!s) return Note('Store not found.');
    const dis = !canEdit;
    const prods = d.products.slice().sort((a, b) => a.name.localeCompare(b.name));
    const f = catSearch.trim().toLowerCase();
    const shown = f ? prods.filter((p) => p.name.toLowerCase().includes(f) || p.sku.toLowerCase().includes(f)) : prods;
    const skuOk = SKU_RE.test(draft.sku.trim()), nameOk = !!draft.name.trim(), priceOk = parsePrice(draft.price) != null;
    const exists = skuOk && d.priceBySku.has(draft.sku.trim());
    const inside = d.shoppers.filter((x) => x.items.length).length;
    const lbl = 'block text-xs muted mb-1';
    return (
      <div className="space-y-0">
        {dis && (
          <div className="mb-5 flex flex-wrap items-center gap-3 rounded-xl sunken px-4 py-3" role="note">
            <div className="min-w-0 flex-1 text-sm muted" style={{ minWidth: 200 }}>{!connected ? 'Offline. Editing comes back with the connection.' : signedIn ? 'You can look, not edit: you’re not on the ' + s.name + ' team. Ask a teammate to invite your email, or join with a code under Stores.' : authReady ? 'You’re a guest here. Sign in (top right) to edit a store you run.' : 'View only.'}</div>
          </div>
        )}
        {Section('Store', Msg('name') || Msg('address') || Msg('radius') || Msg('loc'), (
          <div className={box + ' p-4'}>
            <div className="grid gap-4" style={{ gridTemplateColumns: width >= 620 ? 'minmax(0,1fr) minmax(0,1fr)' : 'minmax(0,1fr)' }}>
              <label className="block"><span className={lbl}>Name</span>
                {dis ? <div className="text-sm ink py-2">{s.name}</div> : <input key={'n' + s.id} ref={syncRef(s.name)} defaultValue={s.name} maxLength={80} className="field"
                  onBlur={(e) => { const v = e.target.value.trim(); if (!v) { e.target.value = s.name; return; } if (v !== s.name) saveStore('name', s, { name: v }); }}
                  onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); if (e.key === 'Escape') { e.currentTarget.value = s.name; e.currentTarget.blur(); } }} />}
              </label>
              <label className="block"><span className={lbl}>Address</span>
                {dis ? <div className="text-sm ink py-2">{s.address || '—'}</div> : <input key={'a' + s.id} ref={syncRef(s.address)} defaultValue={s.address} maxLength={120} placeholder="Street, city" className="field"
                  onBlur={(e) => { const v = e.target.value.trim(); if (v !== s.address) saveStore('address', s, { address: v }); }}
                  onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); if (e.key === 'Escape') { e.currentTarget.value = s.address; e.currentTarget.blur(); } }} />}
              </label>
              <label className="block"><span className={lbl}>Detection radius, meters (10 to 400)</span>
                {dis ? <div className="text-sm ink py-2 tnum">{s.radiusM} m</div> : <input key={'r' + s.id} ref={syncRef(s.radiusM)} type="number" min="10" max="400" step="5" defaultValue={s.radiusM} className="field tnum"
                  onBlur={(e) => { const v = parseInt(e.target.value, 10); if (!(v >= 10 && v <= 400)) { e.target.value = s.radiusM; setFieldMsg((m) => ({ ...m, radius: 'Not saved: use 10 to 400 m' })); return; } if (v !== s.radiusM) saveStore('radius', s, { radiusM: v }); }}
                  onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); }} />}
                <span className="block mt-1 text-xs faint">Phones inside the circle are placed in the store. Two accurate fixes beyond radius + 40 m count as walking out.</span>
              </label>
              <div><span className={lbl}>Pin</span>
                <div className="flex flex-wrap items-center gap-2">
                  <span className="mono text-xs ink tnum">{s.lat.toFixed(5)}, {s.lng.toFixed(5)}</span>
                  {!dis && <button className="btn btn-sm" disabled={geo.status === 'asking'} onClick={() => moveHere(s)}>{geo.status === 'asking' ? 'Locating…' : 'Move pin to my location'}</button>}
                </div>
                {geo.err && <span className="block mt-1 text-xs err">{geo.err}</span>}
              </div>
            </div>
            {!mapFailed && (
              <div className="mt-4 overflow-hidden rounded-xl border hl sunken" style={{ aspectRatio: '16 / 5', maxWidth: 640 }}>
                <img src={geofenceMap(s, dark)} alt={'Map of ' + s.name + ' with its ' + s.radiusM + ' m detection circle'} className="w-full h-full object-cover" loading="lazy" onError={() => setMapFailed(true)} />
              </div>
            )}
          </div>
        ), true)}
        {Section('Catalog', plural(prods.length, 'item') + ' · price changes reach every open bag instantly', (
          <div className={box + ' overflow-hidden'}>
            {prods.length > 8 && (
              <div className="px-4 pt-3 pb-2"><input value={catSearch} onChange={(e) => setCatSearch(e.target.value)} placeholder="Search products or SKUs" aria-label="Search catalog" className="field" /></div>
            )}
            <div className={'px-4 py-2 grid gap-2 text-xs faint ' + (prods.length > 8 ? 'border-t hl' : '')} style={{ gridTemplateColumns: dis ? '56px minmax(0,1fr) 84px' : '56px minmax(0,1fr) 84px 28px' }}>
              <span>SKU</span><span>Name</span><span className="text-right">Price</span>{dis ? null : <span />}
            </div>
            <ul aria-label="Catalog">
              {shown.map((p) => (
                <li key={p.sku} className="px-4 py-1 grid gap-2 items-center border-t hl" style={{ gridTemplateColumns: dis ? '56px minmax(0,1fr) 84px' : '56px minmax(0,1fr) 84px 28px' }}>
                  <span className="mono text-xs faint truncate">{p.sku}</span>
                  <div className="min-w-0">
                    {dis ? <div className="text-sm ink py-1.5 truncate">{p.name}</div> : <input key={'pn' + p.sku} ref={syncRef(p.name)} defaultValue={p.name} maxLength={80} aria-label={'Name of ' + p.sku} className="cell"
                      onBlur={(e) => { const v = e.target.value.trim(); if (!v) { e.target.value = p.name; return; } if (v !== p.name) saveProduct('p' + p.sku, p, { name: v }); }}
                      onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); if (e.key === 'Escape') { e.currentTarget.value = p.name; e.currentTarget.blur(); } }} />}
                    {fieldMsg['p' + p.sku] ? <div className="pb-1">{Msg('p' + p.sku)}</div> : (p.unit ? <div className="text-xs faint px-0.5 pb-1 truncate">{p.unit}</div> : null)}
                  </div>
                  {dis ? <div className="text-sm ink tnum text-right">{fmt(p.priceCents)}</div> : <input key={'pp' + p.sku} ref={syncRef((p.priceCents / 100).toFixed(2))} defaultValue={(p.priceCents / 100).toFixed(2)} inputMode="decimal" aria-label={'Price of ' + p.name + ', dollars'} className="cell tnum text-right"
                    onBlur={(e) => { const v = parsePrice(e.target.value); if (v == null) { e.target.value = (p.priceCents / 100).toFixed(2); setFieldMsg((m) => ({ ...m, ['p' + p.sku]: 'Not saved: enter a price like 4.69' })); return; } if (v !== p.priceCents) saveProduct('p' + p.sku, p, { priceCents: v }); else e.target.value = (v / 100).toFixed(2); }}
                    onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); if (e.key === 'Escape') { e.currentTarget.value = (p.priceCents / 100).toFixed(2); e.currentTarget.blur(); } }} />}
                  {!dis && <button aria-label={'Remove ' + p.name} title="Remove" onClick={() => removeProduct(p)} className="w-7 h-7 rounded-full faint hov flex items-center justify-center">
                    <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true"><path d="M2 2l6 6M8 2 2 8" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" /></svg>
                  </button>}
                </li>
              ))}
              {f && !shown.length && <li className="px-4 py-4 text-sm muted border-t hl">Nothing matches “{catSearch}”.</li>}
            </ul>
            {!dis && <form onSubmit={addProduct} noValidate className="px-4 py-3 border-t hl sunken" aria-label="Add a product">
              <div className="grid gap-2" style={{ gridTemplateColumns: narrow ? '84px minmax(0,1fr)' : '84px minmax(0,1fr) 96px auto' }}>
                <input value={draft.sku} onChange={(e) => setDraft({ ...draft, sku: e.target.value })} placeholder="SKU" aria-label="New product SKU" aria-invalid={draftTried && !skuOk} disabled={dis} className="field mono" />
                <input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} placeholder="Product name" aria-label="New product name" aria-invalid={draftTried && !nameOk} disabled={dis} maxLength={80} className="field" />
                <input value={draft.price} onChange={(e) => setDraft({ ...draft, price: e.target.value })} placeholder="Price" inputMode="decimal" aria-label="New product price, dollars" aria-invalid={draftTried && !priceOk} disabled={dis} className="field tnum" />
                <button type="submit" disabled={dis} className="btn">{exists ? 'Update' : 'Add'}</button>
              </div>
              <div className="mt-1.5 text-xs" aria-live="polite">
                {draftTried && !skuOk ? <span className="err">SKU: letters, numbers, - or _. </span> : null}
                {draftTried && !nameOk ? <span className="err">Add a name. </span> : null}
                {draftTried && !priceOk ? <span className="err">Price like 4.69. </span> : null}
                {exists && !(draftTried && (!nameOk || !priceOk)) ? <span className="faint">SKU {draft.sku.trim()} exists ({d.priceBySku.get(draft.sku.trim()).name}); this updates it.</span> : null}
              </div>
            </form>}
          </div>
        ))}
        {Section('Team', 'You: ' + roleLabel, (
          <div className={box + ' overflow-hidden'}>
            <ul aria-label={'Team of ' + s.name}>
              {storeTeam.filter((t) => t.kind !== 'invite').sort((a, b) => (a.kind === 'owner' ? -1 : 0) - (b.kind === 'owner' ? -1 : 0)).map((t, i) => (
                <li key={t.key} className={'px-4 py-2.5 flex items-center gap-3 ' + (i ? 'border-t hl' : '')}>
                  {Avatar(t.name, 28)}
                  <div className="min-w-0 flex-1"><div className="text-sm ink truncate">{t.name}{t.isYou ? <span className="faint"> · you</span> : null}</div><div className="text-xs muted">{t.kind === 'owner' ? 'Owner' : 'Staff'}</div></div>
                  {canEdit && t.kind !== 'owner' && (t.isYou || canManage) && !(t.isYou && !admin && !storeTeam.some((x) => x.kind === 'owner') && storeTeam.filter((x) => x.kind !== 'invite').length <= 1) && (confirm === 'rm:' + t.key
                    ? <div className="flex gap-2"><button className="btn btn-sm" onClick={() => setConfirm(null)}>Keep</button><button className="btn btn-sm btn-danger" onClick={() => { setConfirm(null); run(t.isYou ? 'Leave' : 'Remove', R.removeStaff({ storeId: s.id, member: t.member }), t.isYou ? 'You left ' + s.name + '.' : t.name + ' is off the team.').catch(() => {}); }}>{t.isYou ? 'Leave team' : 'Remove'}</button></div>
                    : <button className="btn btn-sm" disabled={!R} onClick={() => setConfirm('rm:' + t.key)}>{t.isYou ? 'Leave' : 'Remove'}</button>)}
                </li>
              ))}
              {storeTeam.filter((t) => t.kind === 'invite').map((t) => (
                <li key={t.key} className="px-4 py-2.5 flex items-center gap-3 border-t hl">
                  <span aria-hidden="true" className="inline-flex items-center justify-center rounded-full shrink-0" style={{ width: 28, height: 28, border: '1px dashed ' + pal.strong }} />
                  <div className="min-w-0 flex-1"><div className="text-sm ink truncate">{t.email}</div><div className="text-xs muted">Invited · waiting for them to join</div></div>
                  {canEdit && <button className="btn btn-sm" disabled={!R} onClick={() => run('Cancel invite', R.revokeStaffInvite({ id: t.inviteId }), 'Invite for ' + t.email + ' cancelled.').catch(() => {})}>Cancel invite</button>}
                </li>
              ))}
              {!storeTeam.length && <li className="px-4 py-3 text-sm muted">{canEdit ? (role && role.role === 'admin' ? 'No staff yet. As an admin you can manage every store.' : 'Just you so far.') : 'Only the team can see who’s on it.'}</li>}
            </ul>
            {canEdit && (
              <form onSubmit={invite} noValidate className="px-4 py-3 border-t hl sunken">
                <div className="flex items-baseline gap-3">
                  <div className="min-w-0 flex-1"><div className="text-sm ink">Invite a teammate</div>
                  <div className="text-xs muted">Add their email, then send them the console link. After they sign in with that email, they tap Join.{isOwner ? ' (They need access to this page: Share, top right in Mana.)' : ''}</div></div>
                  <button type="button" className="btn btn-sm shrink-0" onClick={() => copy('link', window.location.origin + window.location.pathname + '?store=' + encodeURIComponent(s.id))}>{copied === 'link' ? 'Link copied' : copied === 'failed:link' ? 'Copy failed' : 'Copy link'}</button>
                </div>
                <div className="mt-2 flex gap-2">
                  <input type="email" value={inviteEmail} onChange={(e) => setInviteEmail(e.target.value)} placeholder="sam@store.com" aria-label="Teammate email" aria-invalid={!!fieldMsg.invite} className="field" autoComplete="off" />
                  <button type="submit" disabled={!R || !inviteEmail.trim()} className="btn shrink-0">Invite</button>
                </div>
                {Msg('invite')}
              </form>
            )}
            {canEdit && role && role.inviteCode ? (
              <div className="px-4 py-3 border-t hl flex flex-wrap items-center gap-3">
                <div className="min-w-0 flex-1" style={{ minWidth: 180 }}>
                  <div className="text-sm ink">Or share a join code</div>
                  <div className="text-xs muted">Anyone with it can join under Stores.{canManage ? '' : ' Only the owner can make a new one.'}</div>
                </div>
                <span className="mono text-sm ink tnum" aria-label={showInvite ? 'Join code ' + role.inviteCode.split('').join(' ') : 'Join code hidden'}>{showInvite ? role.inviteCode : '•••• ••••'}</span>
                <div className="flex gap-2">
                  <button className="btn btn-sm" onClick={() => setShowInvite(!showInvite)}>{showInvite ? 'Hide' : 'Show'}</button>
                  <button className="btn btn-sm" onClick={() => copy('invite', role.inviteCode)}>{copied === 'invite' ? 'Copied' : copied === 'failed:invite' ? 'Copy failed' : 'Copy'}</button>
                  {canManage && <button className="btn btn-sm" disabled={!R} onClick={() => run('New code', R.rotateInvite({ storeId: s.id }), 'New join code made. The old one stops working.').catch(() => {})}>New code</button>}
                </div>
              </div>
            ) : null}
          </div>
        ))}
        {canEdit && Section('Recent changes', 'last 7 days', (
          <div className={box + ' overflow-hidden'}>
            {storeActivity.length ? (
              <ul aria-label="Recent changes">
                {storeActivity.map((a, i) => (
                  <li key={String(a.id)} className={'px-4 py-2.5 flex items-baseline gap-3 text-sm ' + (i ? 'border-t hl' : '')}>
                    <span className="min-w-0 flex-1"><span className="ink font-medium">{a.isYou ? 'You' : a.actorName}</span> <span className="muted">{a.action}</span></span>
                    <span className="text-xs faint shrink-0">{ago(ms(a.at))}</span>
                  </li>
                ))}
              </ul>
            ) : <div className="px-4 py-3 text-sm muted">Price changes, new products and team changes show up here with who made them.</div>}
          </div>
        ))}
        {isOwner && !authReady && Section('Sign-in', 'not turned on yet', (
          <div className={box + ' p-4 text-sm muted'}>
            Personal sign-in (email link, no passwords) is built in and waits for one switch: open the SpacetimeDB dashboard for this database, choose SpacetimeAuth and press Enable. Then ask Mana to “finish SmartBag sign-in setup”. Until then this console runs as the owner’s admin identity and visitors are guests.
          </div>
        ))}
        {canManage && Section('Demo data', null, (
          <div className={box + ' p-4 flex flex-wrap items-center gap-3'}>
            <div className="min-w-0 flex-1" style={{ minWidth: 200 }}>
              <div className="text-sm ink">Reset this store</div>
              <div className="text-xs muted">Clears its shoppers, bags, bag feed and orders. The catalog stays.</div>
            </div>
            {confirm === 'reset'
              ? <div className="flex gap-2"><button className="btn btn-sm" onClick={() => setConfirm(null)}>Cancel</button><button className="btn btn-sm btn-danger" disabled={!R} onClick={() => { setConfirm(null); run('Reset', R.resetDemo({ storeId: s.id }), s.name + ' reset.').catch(() => {}); }}>Clear {s.name}</button></div>
              : <button className="btn btn-sm" disabled={dis} onClick={() => setConfirm('reset')}>Reset…</button>}
          </div>
        ))}
        {canManage && Section('Danger zone', null, (
          <div className={box + ' p-4 flex flex-wrap items-center gap-3'}>
            <div className="min-w-0 flex-1" style={{ minWidth: 200 }}>
              <div className="text-sm ink">Delete {s.name}</div>
              <div className="text-xs muted">{confirm === 'delete' ? 'Removes the store and its ' + plural(prods.length, 'product') + '.' + (inside ? ' ' + plural(inside, 'shopper') + ' with items would not be charged.' : '') : 'Removes the store, its catalog and staff access.'}</div>
            </div>
            {confirm === 'delete'
              ? <div className="flex gap-2"><button className="btn btn-sm" onClick={() => setConfirm(null)}>Keep</button><button className="btn btn-sm btn-danger" disabled={!R} onClick={() => { setConfirm(null); run('Delete store', R.deleteStore({ id: s.id }), 'Deleted ' + s.name + '.').then(() => { try { localStorage.removeItem(lsKey); } catch (e) {} nav({ view: 'stores' }, false); }, () => {}); }}>Delete store</button></div>
              : <button className="btn btn-sm btn-danger" disabled={dis} onClick={() => setConfirm('delete')}>Delete…</button>}
          </div>
        ))}
      </div>
    );
  };

  // ---- All stores (scales to thousands: search, show the first 200)
  const StoresView = () => {
    const f = storeSearch.trim().toLowerCase();
    const rows = storeRows;
    return (
      <div>
        {Section('Stores', plural(allStores.length, 'store'), (
          <div className={box + ' overflow-hidden'}>
            <div className="px-4 py-3 flex flex-wrap gap-2 items-center">
              <input value={storeSearch} onChange={(e) => setStoreSearch(e.target.value)} placeholder="Search stores" aria-label="Search stores" className="field flex-1" style={{ minWidth: 160 }} />
              <button className="btn" disabled={geo.status === 'asking'} onClick={() => locate().catch(() => {})}>{geo.status === 'asking' ? 'Locating…' : geo.pos ? 'Nearest first' : 'Sort by distance'}</button>
            </div>
            {geo.err && <div className="px-4 pb-2 text-xs err">{geo.err}</div>}
            <ul aria-label="Stores">
              {rows.slice(0, 200).map((s) => {
                const r = roles.get(s.id);
                return (
                  <li key={s.id} className="border-t hl">
                    <button onClick={() => nav({ view: 'live', store: s.id }, true)} aria-current={s.id === selId ? 'true' : undefined} className="w-full text-left px-4 py-3 flex items-center gap-3 hov">
                      <div className="min-w-0 flex-1">
                        <div className="text-sm ink truncate">{s.name}{s.id === selId ? <span className="faint"> · open</span> : null}</div>
                        <div className="text-xs muted truncate">{[s.address, s.radiusM + ' m radius', s.dist != null ? fmtDist(s.dist) + ' away' : null].filter(Boolean).join(' · ')}</div>
                      </div>
                      {r && r.role !== 'admin' ? <span className="text-xs muted shrink-0">{r.role === 'owner' ? 'you own it' : 'your team'}</span> : null}
                      <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true" className="faint shrink-0"><path d="M4.5 2.5 8 6l-3.5 3.5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" /></svg>
                    </button>
                  </li>
                );
              })}
              {rows.length > 200 && <li className="border-t hl px-4 py-3 text-xs muted">Showing 200 of {rows.length}. Search to narrow it down.</li>}
              {!rows.length && <li className="border-t hl px-4 py-4 text-sm muted">{f ? 'No store matches “' + storeSearch + '”.' : 'No stores yet. Pin one below.'}</li>}
            </ul>
          </div>
        ), true)}
        {Section('Open a store here', admin ? null : 'up to 5 per person', (
          <form onSubmit={pinHere} className={box + ' p-4'}>
            <div className="text-sm muted">Creates a store where this device is, with a starter catalog. Phones that walk in land in it automatically.</div>
            <div className="mt-3 flex gap-2">
              <input value={pinName} onChange={(e) => setPinName(e.target.value)} maxLength={80} placeholder="Store name" aria-label="New store name" className="field" />
              <button type="submit" disabled={(!R && !needsSignIn) || geo.status === 'asking'} className="btn btn-primary shrink-0">{needsSignIn ? 'Sign in to open' : geo.status === 'asking' ? 'Locating…' : 'Open here'}</button>
            </div>
          </form>
        ))}
        {Section('Join a team', null, (
          <form onSubmit={join} className={box + ' p-4'}>
            <div className="text-sm muted">Got a join code from a teammate? Enter it to manage that store too.</div>
            <div className="mt-3 flex gap-2">
              <input value={joinCode} onChange={(e) => setJoinCode(e.target.value)} placeholder="ABCD2345" aria-label="Join code" autoComplete="off" spellCheck={false} className="field mono uppercase" maxLength={12} />
              <button type="submit" disabled={(!R && !needsSignIn) || !joinCode.trim()} className="btn shrink-0">{needsSignIn ? 'Sign in to join' : 'Join'}</button>
            </div>
          </form>
        ))}
      </div>
    );
  };

  // ---- Setup: the customer app and the bag hardware
  const SetupView = () => {
    const proto = [['1005  or  +1005', 'add one'], ['-1005', 'remove one'], ['+1005@E28011', 'add; repeat reads of that tag are ignored'], ['{"op":"add","sku":"1005"}', 'JSON works too']];
    return (
      <div>
        {Section('Customer app', null, (
          <div className={box + ' p-5'}>
            <div className="flex flex-wrap items-start gap-6">
              <div className="flex-1 min-w-0" style={{ minWidth: 230 }}>
                <div className="text-base font-semibold tracking-tight ink">SmartBag for Android {android ? android.version : ''}</div>
                <div className="mt-1 text-sm muted">One WebSocket to the database. The app forwards bag readings and GPS fixes; the database places the phone in a store, prices the bag and charges it the moment the shopper walks out. There is no pay button.</div>
                <div className="mt-4 flex flex-wrap gap-2">
                  <a href={apkUrl || undefined} download="SmartBag.apk" aria-disabled={!apkUrl} className={'btn btn-primary' + (apkUrl ? '' : ' pointer-events-none')}>Download APK</a>
                  {srcUrl && <a href={srcUrl} download="SmartBag-source.zip" className="btn">Android source</a>}
                  {iosUrl && <a href={iosUrl} download="SmartBag-iOS-source.zip" className="btn">iPhone source</a>}
                </div>
                <ol className="mt-4 space-y-1.5 text-sm muted" style={{ paddingLeft: 18, listStyle: 'decimal' }}>
                  <li>Scan the code with the phone, or open this page on it and tap Download APK.</li>
                  <li>Open the file and allow installs from this source when Android asks.</li>
                  <li>Launch SmartBag, tap Get started, and allow Bluetooth and Location.</li>
                </ol>
                {ios && <div className="mt-3 text-xs faint">iPhone {ios.version}: build from source in Xcode ({ios.minIOS ? 'iOS ' + ios.minIOS + '+' : 'iOS 16+'}). Not yet tested on a device.</div>}
                <div className="mt-3 text-xs faint">Demo without hardware: long-press the bag status to drop in a random item; long-press the store name to simulate walking out.</div>
              </div>
              <div className="flex flex-col items-center">
                {qr ? <img src={qr} alt="QR code: download the SmartBag Android app" className="rounded-lg border hl" style={{ width: 152, height: 152 }} />
                  : <div className="rounded-lg border hl sunken" style={{ width: 152, height: 152 }} />}
                <div className="mt-2 text-xs faint">Scan to install</div>
              </div>
            </div>
          </div>
        ), true)}
        {Section('Bag hardware', 'BLE Nordic UART Service', (
          <div className={box + ' overflow-hidden'}>
            <div className="px-4 pt-4 text-sm muted">The bag notifies newline-terminated text on TX. The phone forwards each line to the database, which parses it and answers the bag on RX with <span className="mono ink whitespace-nowrap">OK +1005 4.69</span>, <span className="mono ink whitespace-nowrap">ERR 9999 unknown</span>, or <span className="mono ink whitespace-nowrap">PAID 7KQ2XD</span> once the shopper walks out.</div>
            <div className="mt-3 text-xs">
              {proto.map(([a, b]) => (
                <div key={a} className="px-4 py-2 flex items-baseline gap-3 border-t hl"><span className="mono shrink-0 ink">{a}</span><span className="ml-auto text-right muted">{b}</span></div>
              ))}
              {[['Service', NUS], ['TX, notify', NUS_TX], ['RX, write', NUS_RX]].map(([a, b]) => (
                <div key={a} className="px-4 py-2 flex items-center gap-3 border-t hl">
                  <span className="shrink-0 muted">{a}</span>
                  <span className="mono ml-auto truncate ink">{b}</span>
                  <button onClick={() => copy(a, b)} className="btn btn-sm shrink-0" aria-label={'Copy ' + a + ' UUID'}>{copied === a ? 'Copied' : 'Copy'}</button>
                </div>
              ))}
            </div>
            <div className="px-4 pt-4 pb-2 flex items-center gap-3 border-t hl">
              <div className="min-w-0 flex-1"><div className="text-sm font-medium ink">ESP32 firmware</div><div className="text-xs muted">Arduino sketch, advertises as SmartBag-01</div></div>
              <button className="btn btn-sm" onClick={() => copy('fw', firmware)} disabled={!firmware}>{copied === 'fw' ? 'Copied' : copied === 'failed:fw' ? 'Copy failed' : 'Copy sketch'}</button>
            </div>
            <pre className="mx-4 mb-4 mt-1 p-3 rounded-xl overflow-auto sunken muted mono" style={{ fontSize: 11, maxHeight: 320, lineHeight: 1.55 }}>{firmware || 'Loading…'}</pre>
          </div>
        ))}
      </div>
    );
  };

  // ---- shell
  const tabs = [['live', 'Live'], ['orders', 'Orders'], ['settings', 'Settings'], ['setup', 'Setup']];
  const view = route.view === 'store' ? 'settings' : (route.view === 'app' || route.view === 'hardware') ? 'setup' : route.view;
  const known = view === 'stores' || tabs.some(([t]) => t === view);
  const cur = known ? view : 'live';
  const needsStore = ['live', 'orders', 'settings'].includes(cur);
  let content;
  if (authBusy === 'exchanging') content = Note('Signing you in…');
  else if (cur !== 'setup' && !conn) content = status.startsWith('error') ? Note('Could not reach the store database: ' + status.slice(7)) : Note('Connecting…');
  else if (needsStore && needsWelcome) content = GetStarted();
  else if (needsStore && !selId) content = (
    <div className="py-14 text-center"><div className="text-sm muted">No stores yet.</div><button className="btn mt-3" onClick={() => nav({ view: 'stores' }, true)}>Open a store</button></div>
  );
  else if (needsStore && storeErr && scoped !== selId) content = (
    <div className="py-14 text-center"><div className="text-sm err">Could not load {sel ? sel.name : 'this store'}: {storeErr}</div><button className="btn mt-3" onClick={() => setRetryKey((k) => k + 1)}>Try again</button></div>
  );
  else if (needsStore && scoped !== selId) content = Note('Loading ' + (sel ? sel.name : 'store') + '…');
  else if (cur === 'orders') content = OrdersView();
  else if (cur === 'settings') content = SettingsView();
  else if (cur === 'stores') content = StoresView();
  else if (cur === 'setup') content = SetupView();
  else content = LiveView();

  if (Date.now() - beatRef.current.t > 2000 && beatRef.current.tick !== tick) beatRef.current = { t: Date.now(), tick, k: tick };
  const beatKey = beatRef.current.k;
  const statusText = connected ? 'Live' : status.startsWith('error') ? 'Offline' : status === 'reconnecting' ? 'Reconnecting…' : 'Connecting…';
  const dot = connected ? '#1f9d63' : status.startsWith('error') ? pal.err : pal.faint;
  const stale = !!conn && !connected;
  const who = signedIn ? (meName || 'Signed in') : isOwner && !authReady ? 'Owner' : 'Guest';
  const Person = (size) => (
    <span aria-hidden="true" className="inline-flex items-center justify-center rounded-full shrink-0" style={{ width: size, height: size, background: pal.hover, border: '1px solid ' + pal.line, color: pal.muted }}>
      <svg width={size * 0.56} height={size * 0.56} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><circle cx="12" cy="8.5" r="3.6" /><path d="M5 20c1.3-3.6 4-5.4 7-5.4s5.7 1.8 7 5.4" /></svg>
    </span>
  );
  const chipFace = (size) => (signedIn ? Avatar(who, size) : Person(size));
  const closeMenu = () => { setMenuOpen(false); setNameDraft(null); setTimeout(() => { const t = rootRef.current && rootRef.current.querySelector('[data-sbc-trigger]'); if (t) t.focus(); }, 0); };
  const AccountMenu = () => (
    <div className="relative" data-sbc-menu="" onKeyDown={(e) => { if (e.key === 'Escape' && menuOpen) { e.stopPropagation(); closeMenu(); } }}>
      {signedIn || !authReady ? (
        <button data-sbc-trigger="" className="flex items-center gap-2 rounded-full pl-1 pr-2.5 py-1 hov" aria-label={'Account: ' + who + ', ' + roleLabel} aria-expanded={menuOpen} aria-controls="sbc-account" onClick={() => (menuOpen ? closeMenu() : setMenuOpen(true))}>
          {chipFace(26)}
          {!narrow && <span className="text-sm ink font-medium truncate" style={{ maxWidth: 140 }}>{who}</span>}
        </button>
      ) : (
        <button className="btn btn-primary" onClick={() => signIn()} disabled={!!authBusy}>{authBusy ? 'Signing in…' : 'Sign in'}</button>
      )}
      {menuOpen && (
        <div id="sbc-account" className="menu p-3 text-sm" ref={(el) => { if (el && !el.dataset.f) { el.dataset.f = '1'; const f = el.querySelector('button, input'); if (f) f.focus(); } }}>
          <div className="flex items-center gap-3">
            {chipFace(36)}
            <div className="min-w-0">
              <div className="ink font-medium truncate">{who}</div>
              <div className="text-xs muted truncate">{signedIn ? (session.email || (account && account.email) || 'Signed in') : isOwner ? 'This page’s owner' : 'Guest on this browser'}</div>
            </div>
          </div>
          <div className="mt-3 pt-3 border-t hl text-xs muted">{sel ? roleLabel + ' at ' + sel.name : roleLabel}{admin ? ' · can manage every store' : ''}</div>
          {signedIn && (nameDraft == null
            ? <button className="mt-3 btn btn-sm w-full" onClick={() => setNameDraft(meName)}>Change display name</button>
            : <form onSubmit={saveName} className="mt-3 flex gap-2"><input autoFocus value={nameDraft} onChange={(e) => setNameDraft(e.target.value)} maxLength={60} aria-label="Display name" className="field" style={{ height: 30 }} /><button type="submit" className="btn btn-sm" disabled={!R || !nameDraft.trim()}>Save</button></form>)}
          {signedIn ? <button className="mt-2 btn btn-sm w-full" onClick={() => { signOut(null); closeMenu(); }}>Sign out</button>
            : authReady ? <button className="mt-3 btn btn-primary btn-sm w-full" onClick={() => signIn()}>Sign in</button>
            : <div className="mt-3 text-xs faint">{isOwner ? 'Personal sign-in turns on once SpacetimeAuth is enabled for this database (see Settings).' : 'Personal sign-in isn’t turned on for this store yet.'}</div>}
        </div>
      )}
    </div>
  );
  // Header and content share one right edge even when the page shows a classic scrollbar.
  const gutter = (() => { const m = rootRef.current && rootRef.current.querySelector('main'); return m ? Math.max(0, m.offsetWidth - m.clientWidth) : 0; })();
  return (
    <div ref={rootRef} className="sbc w-full flex justify-center" style={{ ...vars, height: 'var(--mana-app-height, 82vh)' }}>
      <style>{CSS}</style>
      <div className="w-full h-full flex flex-col" style={{ maxWidth: 1120 }}>
        <header className="pl-4 pt-3" style={{ paddingRight: 16 + gutter }}>
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0 flex-1">
              <div className="cap whitespace-nowrap flex items-center gap-1.5">
                <span>SmartBag</span>
                <span className="inline-flex items-center gap-1 normal-case tracking-normal" style={{ fontSize: 11 }} title={connected && lagMs != null ? 'Updates reach this screen about ' + lagMs + ' ms after the database saves them (approximate: device clocks can differ).' : undefined}>
                  <span aria-hidden="true">·</span>
                  <span key={connected ? beatKey : 'x'} className={'inline-block w-1.5 h-1.5 rounded-full ' + (connected ? 'sbc-beat' : '')} style={{ background: dot }} aria-hidden="true" />
                  <span role="status" aria-live="polite">{statusText}</span>
                  {connected && lagMs != null && !narrow ? <span className="tnum" aria-hidden="true">· ≈ {lagMs} ms sync</span> : null}
                </span>
              </div>
              {allStores.length > 0 && !(needsWelcome && needsStore) ? (
                <div className="relative inline-block min-w-0 max-w-full rounded-md hov" style={{ fontSize: narrow ? 18 : 20, lineHeight: 1.3 }}>
                  <span aria-hidden="true" className="block invisible whitespace-nowrap truncate font-semibold tracking-tight pr-6" style={{ maxWidth: narrow ? 230 : 420 }}>{sel ? sel.name : 'Store'}</span>
                  <select value={selId || ''} aria-label="Store" onChange={(e) => (e.target.value === '__browse' ? nav({ view: 'stores' }, true) : nav({ view: cur === 'stores' ? 'live' : cur, store: e.target.value }, true))}
                    className="absolute inset-0 w-full appearance-none bg-transparent ink font-semibold tracking-tight pr-6 cursor-pointer truncate" style={{ fontSize: 'inherit', lineHeight: 'inherit' }}>
                    {switcherOptions}
                  </select>
                  <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true" className="absolute right-1 top-1/2 -translate-y-1/2 pointer-events-none faint"><path d="M3 4.5 6 7.5l3-3" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" /></svg>
                </div>
              ) : <div className="ink font-semibold tracking-tight" style={{ fontSize: 20 }}>Store console</div>}
            </div>
            <div className="shrink-0 pt-1">{AccountMenu()}</div>
          </div>
          {authErr && <div role="alert" className="mt-2 text-xs err flex items-center gap-2"><span className="min-w-0 flex-1">{authErr}</span><button className="underline shrink-0" onClick={() => setAuthErr(null)}>Dismiss</button></div>}
          <nav aria-label="Console sections" className="mt-3 -mx-4 px-4 flex items-center gap-1 overflow-x-auto flex-nowrap noscroll"
            style={narrow ? { WebkitMaskImage: 'linear-gradient(90deg,#000 90%,transparent)', maskImage: 'linear-gradient(90deg,#000 90%,transparent)' } : null}>
            {tabs.map(([v, label]) => {
              const on = cur === v;
              return (
                <button key={v} aria-current={on ? 'page' : undefined} onClick={() => nav({ view: v, store: selId || route.store }, false)}
                  className={'shrink-0 py-1.5 text-sm rounded-full whitespace-nowrap ' + (narrow ? 'px-2.5 ' : 'px-3.5 ') + (on ? 'ink font-medium' : 'muted hov')} style={on ? { background: dark ? pal.strong : pal.line } : null}>
                  {label}{v === 'orders' && todays.length ? <span className="faint tnum"> {todays.length}</span> : null}
                </button>
              );
            })}
            <span className="mx-1 h-4 border-l hl shrink-0" aria-hidden="true" />
            <button aria-current={cur === 'stores' ? 'page' : undefined} onClick={() => nav({ view: 'stores' }, true)}
              className={'shrink-0 py-1.5 text-sm rounded-full whitespace-nowrap ' + (narrow ? 'px-2.5 ' : 'px-3.5 ') + (cur === 'stores' ? 'ink font-medium' : 'muted hov')} style={cur === 'stores' ? { background: dark ? pal.strong : pal.line } : null}>
              {narrow ? 'Stores' : 'All stores'}
            </button>
          </nav>
          <div className="mt-2 border-b hl" />
        </header>
        {notice && (
          <div role={notice.kind === 'error' ? 'alert' : 'status'} className="mx-4 mt-3 rounded-xl px-4 py-2.5 text-sm flex items-center gap-3"
            onMouseEnter={() => { noticeHold.current = true; }} onMouseLeave={() => { noticeHold.current = false; }} onFocus={() => { noticeHold.current = true; }} onBlur={() => { noticeHold.current = false; }}
            style={{ background: notice.kind === 'error' ? (dark ? 'rgba(240,113,113,.10)' : 'rgba(180,35,24,.06)') : pal.hover }}>
            <span className={'min-w-0 flex-1 ' + (notice.kind === 'error' ? 'err' : 'ink')}>{notice.text}</span>
            {notice.undo && <button className="btn btn-sm" onClick={undo}>Undo</button>}
            <button className="w-7 h-7 rounded-full faint hov flex items-center justify-center shrink-0" aria-label="Dismiss" onClick={() => { noticeHold.current = false; setNotice(null); }}>
              <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true"><path d="M2 2l6 6M8 2 2 8" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" /></svg>
            </button>
          </div>
        )}
        {storeErr && scoped === selId && needsStore && (
          <div role="alert" className="mx-4 mt-3 rounded-xl px-4 py-2.5 text-sm flex items-center gap-3" style={{ background: pal.hover }}>
            <span className="min-w-0 flex-1 err">Live updates for {sel ? sel.name : 'this store'} stopped: {storeErr}</span>
            <button className="btn btn-sm" onClick={() => setRetryKey((k) => k + 1)}>Try again</button>
          </div>
        )}
        <div role="status" aria-live="polite" className="sr-only" style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)' }}>
          {fresh.filter((o) => isNew(o.at))[0] ? (() => { const o = fresh.filter((x) => isNew(x.at))[0]; return (o.status === 'paid' ? 'Paid ' : 'Payment due ') + fmt(o.totalCents) + ': ' + o.who + ' walked out of ' + (sel ? sel.name : 'the store'); })() : ''}
        </div>
        <main className="flex-1 min-h-0 overflow-y-auto px-4 pt-5 pb-10" style={{ opacity: stale ? 0.55 : 1, transition: 'opacity .3s', scrollbarGutter: 'stable' }} aria-busy={stale || undefined}>
          {content}
        </main>
      </div>
    </div>
  );
}

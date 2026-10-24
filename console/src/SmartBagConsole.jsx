import * as QRMod from 'qrcode@1.5.4';

const QRLib = (QRMod && typeof QRMod.toDataURL === 'function') ? QRMod : ((QRMod && QRMod.default) || null);
const ACCENT = '#E8654A';
const GREEN = '#1f9d63';
const RED = '#d64545';
const MONO = 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace';
const CAP = { fontSize: 11, letterSpacing: '0.18em', textTransform: 'uppercase' };
const NUS = '6e400001-b5a3-f393-e0a9-e50e24dcca9e';
const NUS_RX = '6e400002-b5a3-f393-e0a9-e50e24dcca9e';
const NUS_TX = '6e400003-b5a3-f393-e0a9-e50e24dcca9e';
const TABLES = ['store', 'myStores', 'product', 'shopper', 'cartItem', 'shopOrder', 'orderLine', 'bagEvent'];
// Scales to thousands of stores: the console always holds the (small) store list, plus the live rows
// of ONE store at a time. Every per-store query hits an index on store_id in the module.
const GLOBAL_QUERIES = ['SELECT * FROM store', 'SELECT * FROM my_stores'];
const q = (v) => "'" + String(v).replace(/'/g, "''") + "'";
const utcDay = (offsetDays) => new Date(Date.now() + offsetDays * 86400000).toISOString().slice(0, 10);
function storeQueries(id) {
  const days = [utcDay(0), utcDay(-1)];
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
function haversine(a, b) {
  const R = 6371000, r = (x) => x * Math.PI / 180;
  const dLat = r(b.lat - a.lat), dLng = r(b.lng - a.lng);
  const s = Math.sin(dLat / 2) ** 2 + Math.cos(r(a.lat)) * Math.cos(r(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(s));
}
function fmtDist(m) { if (m == null) return ''; return m < 1000 ? Math.round(m) + ' m' : (m / 1000).toFixed(m < 10000 ? 1 : 0) + ' km'; }
function ago(t) { const s = (Date.now() - t) / 1000; if (s < 10) return 'now'; if (s < 60) return Math.floor(s) + 's ago'; if (s < 3600) return Math.floor(s / 60) + ' min ago'; if (s < 86400) return Math.floor(s / 3600) + ' h ago'; return new Date(t).toLocaleDateString(); }
function readUrl() { const p = new URLSearchParams(window.location.search); return { view: p.get('view') || 'live', store: p.get('store') }; }

const CSS = `
.sbc-in { animation: sbcIn .5s cubic-bezier(.2,.8,.2,1); }
@keyframes sbcIn { from { opacity: 0; transform: translateY(-6px); background: rgba(232,101,74,.14); } to { opacity: 1; transform: none; background: transparent; } }
.sbc-live { animation: sbcLive 1.6s ease-in-out infinite; }
@keyframes sbcLive { 0%,100% { opacity: 1; } 50% { opacity: .35; } }
@media (prefers-reduced-motion: reduce) { .sbc-in, .sbc-live { animation: none; } }
`;

function UserDiv() {
  const { ct } = useTheme();
  const stdbAddr = relPathAddress('spacetime');
  const fwAddr = relPathAddress('firmware');
  const androidAddr = relPathAddress('android');

  const [cfg, setCfg] = React.useState(null);
  const [conn, setConn] = React.useState(null);
  const [status, setStatus] = React.useState('connecting');
  const [operator, setOperator] = React.useState(null);
  const [tick, setTick] = React.useState(0);
  const [firmware, setFirmware] = React.useState('');
  const [android, setAndroid] = React.useState(null);
  const [apkUrl, setApkUrl] = React.useState(null);
  const [srcUrl, setSrcUrl] = React.useState(null);
  const [qr, setQr] = React.useState(null);
  const [route, setRoute] = React.useState(readUrl);
  const [pos, setPos] = React.useState(null);
  const [geoErr, setGeoErr] = React.useState(null);
  const [open, setOpen] = React.useState(null);
  const [confirmDel, setConfirmDel] = React.useState(null);
  const [copied, setCopied] = React.useState(null);
  const [notice, setNotice] = React.useState(null);
  const [, setClock] = React.useState(0);
  const [scoped, setScoped] = React.useState(null);

  React.useEffect(() => { const t = setInterval(() => setClock((x) => x + 1), 15000); return () => clearInterval(t); }, []);

  React.useEffect(() => {
    if (!stdbAddr) return;
    const offs = [
      FS.onValue(FS.ref(FS.db, fwAddr + '/code'), (s) => setFirmware(s.val() || '')),
      FS.onValue(FS.ref(FS.db, androidAddr), (s) => setAndroid(s.val())),
    ];
    return () => offs.forEach((o) => { try { o && o(); } catch (e) {} });
  }, [stdbAddr]);

  // ---- SpacetimeDB connection: one live subscription keeps every table here in sync.
  // If the socket drops, the whole connection is rebuilt with backoff (1s, 2s, 4s … 15s).
  React.useEffect(() => {
    if (!stdbAddr) return;
    let cancelled = false, c = null, blobUrl = null, timer = null, retry = null, attempt = 0, mod = null, conf = null;
    const bump = () => { if (!timer) timer = setTimeout(() => { timer = null; if (!cancelled) setTick((t) => t + 1); }, 40); };
    const schedule = (why) => {
      if (cancelled || retry) return;
      setStatus(why);
      const wait = Math.min(15000, 1000 * Math.pow(2, attempt++));
      retry = setTimeout(() => { retry = null; open(); }, wait);
    };
    const open = () => {
      if (cancelled) return;
      try { c && c.disconnect(); } catch (e) {}
      c = null;
      let mine = null;
      let b = mod.DbConnection.builder().withUri(conf.host).withDatabaseName(conf.db);
      if (conf.consoleToken) b = b.withToken(conf.consoleToken);
      b = b.onConnect((cn, identity, token) => {
        if (cancelled || cn !== mine) { try { cn.disconnect(); } catch (e) {} return; }
        if (token && token !== conf.consoleToken) { conf.consoleToken = token; FS.set(FS.ref(FS.db, stdbAddr + '/consoleToken'), token); }
        Promise.resolve(cn.reducers.claimOperator({ code: conf.operatorCode })).then(() => setOperator(true), () => setOperator(false));
        TABLES.forEach((tn) => { const t = cn.db[tn]; if (!t) return; t.onInsert(bump); t.onDelete(bump); if (t.onUpdate) t.onUpdate(bump); });
        const sb = cn.subscriptionBuilder().onApplied(() => {
          if (cancelled || cn !== mine) return;
          attempt = 0; setConn(cn); setStatus('connected'); bump();
        });
        if (sb.onError) sb.onError(() => { if (cn === mine) schedule('reconnecting'); });
        sb.subscribe(GLOBAL_QUERIES);
      }).onConnectError(() => { if (!cancelled) schedule('reconnecting'); });
      if (b.onDisconnect) b = b.onDisconnect(() => { if (!cancelled && c === mine) schedule('reconnecting'); });
      mine = b.build();
      c = mine;
    };
    (async () => {
      const snap = await FS.get(FS.ref(FS.db, stdbAddr));
      conf = snap.val();
      if (!conf || !conf.client || !conf.client.code) throw new Error('SpacetimeDB client missing');
      if (cancelled) return;
      setCfg({ host: conf.host, db: conf.db });
      blobUrl = URL.createObjectURL(new Blob([conf.client.code], { type: 'text/javascript' }));
      mod = await import(/* @vite-ignore */ blobUrl);
      open();
    })().catch((e) => { if (!cancelled) setStatus('error: ' + (e.message || e)); });
    const wake = () => { if (document.visibilityState === 'visible' && !cancelled && retry) { clearTimeout(retry); retry = null; attempt = 0; open(); } };
    document.addEventListener('visibilitychange', wake);
    return () => {
      cancelled = true; clearTimeout(timer); clearTimeout(retry);
      document.removeEventListener('visibilitychange', wake);
      try { c && c.disconnect(); } catch (e) {}
      if (blobUrl) URL.revokeObjectURL(blobUrl);
    };
  }, [stdbAddr]);

  React.useEffect(() => {
    if (!android || !android.apkPath) return;
    let alive = true;
    FS.getDownloadURL(FS.storageRef(FS.storage, ownerId + '/' + android.apkPath)).then((u) => { if (alive) setApkUrl(u); }).catch(() => {});
    if (android.sourcePath) FS.getDownloadURL(FS.storageRef(FS.storage, ownerId + '/' + android.sourcePath)).then((u) => { if (alive) setSrcUrl(u); }).catch(() => {});
    return () => { alive = false; };
  }, [android && android.apkPath, android && android.sourcePath]);

  React.useEffect(() => {
    if (!apkUrl || !QRLib) return;
    QRLib.toDataURL(apkUrl, { margin: 1, width: 360, color: { dark: '#18181b', light: '#ffffff' } }).then(setQr).catch(() => {});
  }, [apkUrl]);

  React.useEffect(() => {
    if (!navigator.geolocation) return;
    const id = navigator.geolocation.watchPosition(
      (p) => { setPos({ lat: p.coords.latitude, lng: p.coords.longitude }); setGeoErr(null); },
      (e) => setGeoErr(e.code === 1 ? 'Location permission is off.' : 'Could not get your location.'),
      { enableHighAccuracy: true, maximumAge: 10000, timeout: 20000 });
    return () => navigator.geolocation.clearWatch(id);
  }, []);

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

  // ---- which store this console is looking at: ?store= in the URL, else the nearest, else the first
  const allStores = React.useMemo(() => (conn ? [...conn.db.store.iter()] : []), [tick, conn]);
  const selId = React.useMemo(() => {
    if (route.store && allStores.some((s) => s.id === route.store)) return route.store;
    if (!allStores.length) return null;
    if (pos) return allStores.slice().sort((a, b) => haversine(pos, a) - haversine(pos, b))[0].id;
    return allStores.slice().sort((a, b) => a.name.localeCompare(b.name))[0].id;
  }, [route.store, allStores, pos && Math.round(pos.lat * 1000), pos && Math.round(pos.lng * 1000)]);

  // ---- live rows for the selected store only; switching stores swaps the subscription
  React.useEffect(() => {
    if (!conn || !selId) return;
    let alive = true;
    setScoped(null);
    const h = conn.subscriptionBuilder()
      .onApplied(() => { if (alive) { setScoped(selId); setTick((t) => t + 1); } })
      .subscribe(storeQueries(selId));
    return () => { alive = false; try { h.unsubscribe(); } catch (e) {} };
  }, [conn, selId]);

  // ---- derived data from the live client cache
  const d = React.useMemo(() => {
    const all = (tn) => (conn && conn.db[tn] ? [...conn.db[tn].iter()] : []);
    const stores = all('store');
    const products = all('product').filter((p) => p.storeId === selId);
    const price = new Map(products.map((p) => [p.storeId + '|' + p.sku, p]));
    const storeName = new Map(stores.map((s) => [s.id, s.name]));
    const cart = all('cartItem');
    const cartBy = new Map();
    cart.forEach((c) => { const k = hex(c.shopper); if (!cartBy.has(k)) cartBy.set(k, []); cartBy.get(k).push(c); });
    const shoppers = all('shopper').filter((s) => s.storeId === selId).map((s) => {
      const k = hex(s.identity);
      const items = (cartBy.get(k) || []).slice().sort((a, b) => ms(a.addedAt) - ms(b.addedAt)).map((c) => {
        const p = s.storeId ? price.get(s.storeId + '|' + c.sku) : null;
        return { sku: c.sku, qty: c.qty, name: p ? p.name : 'Item ' + c.sku, price: p ? p.priceCents : null, pending: !s.storeId };
      });
      return { ...s, key: k, items, total: items.reduce((a, i) => a + (i.price || 0) * i.qty, 0), count: items.reduce((a, i) => a + i.qty, 0), seen: ms(s.updatedAt) };
    }).filter((s) => s.items.length || Date.now() - s.seen < 2 * 3600e3).sort((a, b) => b.seen - a.seen);
    const lines = all('orderLine');
    const linesBy = new Map();
    lines.forEach((l) => { const k = String(l.orderId); if (!linesBy.has(k)) linesBy.set(k, []); linesBy.get(k).push(l); });
    const orders = all('shopOrder').filter((o) => o.storeId === selId).map((o) => ({ ...o, key: String(o.id), at: ms(o.paidAt), lines: linesBy.get(String(o.id)) || [] })).sort((a, b) => b.at - a.at);
    const events = all('bagEvent').filter((e) => e.storeId === selId).map((e) => ({ ...e, key: String(e.id), at: ms(e.at), who: hex(e.shopper).slice(0, 6), storeName: storeName.get(e.storeId) || '' })).sort((a, b) => b.at - a.at || Number(b.id - a.id)).slice(0, 40);
    return { stores, products, shoppers, orders, events };
  }, [tick, conn, selId]);

  const storeList = d.stores.map((s) => ({ ...s, dist: pos ? haversine(pos, s) : null, products: s.id === selId ? d.products : [] }))
    .sort((a, b) => (a.dist == null ? 1e12 : a.dist) - (b.dist == null ? 1e12 : b.dist));
  const today = d.orders.filter((o) => Date.now() - o.at < 86400000);
  const todayTotal = today.reduce((s, o) => s + o.totalCents, 0);
  const inStores = d.shoppers.filter((s) => s.storeId);

  const run = (label, p) => Promise.resolve(p).then(() => setNotice(null), (e) => setNotice(label + ': ' + ((e && e.message) || e)));
  const R = conn ? conn.reducers : null;
  const pinHere = (e) => { e.preventDefault(); if (!pos || !R) return; run('Pin store', R.pinStore({ name: (e.currentTarget.elements.name.value || 'Demo Market').trim(), lat: pos.lat, lng: pos.lng })); };
  const sel = storeList.find((s) => s.id === selId) || null;
  const saveStore = (s, patch) => R && run('Save store', R.upsertStore({ id: s.id, name: s.name, address: s.address, lat: s.lat, lng: s.lng, radiusM: s.radiusM, ...patch }));
  const saveProduct = (storeId, p, patch) => R && run('Save product', R.upsertProduct({ storeId, sku: p.sku, name: p.name, priceCents: p.priceCents, unit: p.unit || '', ...patch }));
  const addProduct = (e, storeId) => {
    e.preventDefault();
    const f = e.currentTarget.elements;
    const sku = f.sku.value.trim(), name = f.name.value.trim(), priceCents = Math.round(parseFloat(f.price.value) * 100);
    if (!sku || !name || !(priceCents >= 0) || !R) return;
    run('Add product', R.upsertProduct({ storeId, sku, name, priceCents, unit: '' }));
    e.currentTarget.reset();
  };
  const copy = async (what, text) => { const r = await copyText(text); setCopied(r === 'failed' ? 'failed' : what); setTimeout(() => setCopied(null), 1600); };

  const ink = ct('text-zinc-100', 'text-zinc-900');
  const muted = ct('text-zinc-400', 'text-zinc-500');
  const faint = ct('text-zinc-500', 'text-zinc-400');
  const line = ct('border-zinc-800', 'border-zinc-200');
  const surface = ct('bg-zinc-900', 'bg-white');
  const card = 'rounded-2xl border ' + line + ' ' + surface;
  const inputCls = 'w-full rounded-lg border ' + line + ' ' + ct('bg-zinc-950 text-zinc-100 placeholder-zinc-600', 'bg-white text-zinc-900 placeholder-zinc-400') + ' px-3 py-2 text-sm outline-none';
  const btnPrimary = 'rounded-full px-5 py-2.5 text-sm font-medium text-white disabled:opacity-40';
  const btnGhost = 'rounded-full px-4 py-2 text-sm border ' + line + ' ' + ink + ' ' + ct('hover:bg-zinc-800', 'hover:bg-zinc-100') + ' disabled:opacity-40';
  const connected = status === 'connected' && !!conn;

  if (!stdbAddr) return <div className={'w-full p-6 text-sm ' + muted}>Open this app from its page in Mana.</div>;

  const Waiting = () => (
    <div className={'py-16 text-center text-sm ' + muted}>{status.startsWith('error') ? 'Could not reach SpacetimeDB. ' + status.slice(7) : 'Connecting to SpacetimeDB…'}</div>
  );

  const LiveView = () => (
    <div className="space-y-4">
      <div className="grid gap-3" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))' }}>
        {[[inStores.length, 'shoppers in a store'], [inStores.reduce((a, s) => a + s.count, 0), 'items in bags'], [fmt(inStores.reduce((a, s) => a + s.total, 0)), 'in open bags'], [fmt(todayTotal), 'paid today']].map(([v, l]) => (
          <div key={l} className={card + ' px-4 py-3'}>
            <div className={'text-2xl tabular-nums ' + ink} style={{ fontFamily: MONO }}>{v}</div>
            <div className={'text-xs ' + muted}>{l}</div>
          </div>
        ))}
      </div>
      <div className="grid gap-4" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))' }}>
        <div className="space-y-3 min-w-0">
          <div className="flex items-center justify-between">
            <div className={muted} style={CAP}>Shoppers</div>
            {operator && (confirmDel === '__reset'
              ? <span className="text-xs"><button className="underline" style={{ color: RED }} onClick={() => { setConfirmDel(null); run('Reset', R.resetDemo({ storeId: selId || '' })); }}>Clear this store's shoppers, carts and orders</button> <button className={'ml-2 underline ' + muted} onClick={() => setConfirmDel(null)}>Cancel</button></span>
              : <button className={'text-xs underline ' + muted} onClick={() => setConfirmDel('__reset')}>Reset demo</button>)}
          </div>
          {d.shoppers.length === 0 && <div className={card + ' px-4 py-8 text-center text-sm ' + muted}>Nobody is shopping here right now. Shoppers appear when their phone enters the store and are charged automatically when they walk out.</div>}
          {d.shoppers.map((s) => (
            <div key={s.key} className={card}>
              <div className="px-4 pt-3 pb-2 flex items-start gap-3">
                <div className="min-w-0 flex-1">
                  <div className={'text-sm font-medium ' + ink}>Shopper {s.key.slice(0, 6)}</div>
                  <div className={'text-xs ' + muted}>
                    {s.outsideFixes > 0 ? <span style={{ color: ACCENT }}>walking out, paying now</span> : <span style={{ color: GREEN }}>in store · {fmtDist(s.distanceM)} from center</span>}
                    {' · '}{s.bag ? s.bag : 'no bag'} · {ago(s.seen)}
                  </div>
                </div>
                <div className={'text-sm tabular-nums ' + ink} style={{ fontFamily: MONO }}>{fmt(s.total)}</div>
              </div>
              {s.items.length > 0 && (
                <div className={'px-4 pb-3 border-t ' + line} style={{ fontFamily: MONO, fontSize: 12 }}>
                  {s.items.map((i) => (
                    <div key={i.sku} className="sbc-in flex gap-3 pt-2">
                      <span className={'flex-1 truncate ' + (i.price == null && !i.pending ? faint + ' line-through' : ink)}>{i.qty > 1 ? i.qty + ' × ' : ''}{i.name}</span>
                      <span className={i.price == null ? (i.pending ? faint : '') : muted} style={i.price == null && !i.pending ? { color: RED } : null}>{i.price == null ? (i.pending ? 'no store yet' : 'not sold here') : fmt(i.price * i.qty)}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>
        <div className="space-y-3 min-w-0">
          <div className="flex items-center gap-2">
            <span className="sbc-live inline-block w-1.5 h-1.5 rounded-full" style={{ background: ACCENT }} />
            <div className={muted} style={CAP}>Bag feed</div>
          </div>
          <div className={card}>
            {d.events.length === 0 && <div className={'px-4 py-8 text-center text-sm ' + muted}>Waiting for bags.</div>}
            {d.events.map((e, i) => (
              <div key={e.key} className={'sbc-in px-4 py-2 flex items-baseline gap-3 ' + (i ? 'border-t ' + line : '')} style={{ fontFamily: MONO, fontSize: 12 }}>
                <span className={'shrink-0 ' + faint}>{e.who}</span>
                <span className={'truncate ' + ink}>{e.line}</span>
                <span className={'ml-auto shrink-0 text-right truncate ' + muted} style={{ maxWidth: '50%' }}>{e.result}</span>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );

  const OrdersView = () => (
    <div className="space-y-3">
      <div className="flex items-baseline justify-between">
        <div className={muted} style={CAP}>Orders · last 2 days (UTC)</div>
        <div className={'text-sm ' + muted}>Today {fmt(todayTotal)}</div>
      </div>
      <div className={card}>
        {d.orders.length === 0 && <div className={'px-4 py-8 text-center text-sm ' + muted}>No orders yet. A shopper's bag is charged and appears here the moment they walk out.</div>}
        {d.orders.map((o, i) => (
          <div key={o.key} className={'sbc-in ' + (i ? 'border-t ' + line : '')}>
            <button onClick={() => setOpen(open === o.key ? null : o.key)} className="w-full text-left px-4 py-3 flex items-center gap-3">
              <div className="min-w-0 flex-1">
                <div className={'text-sm truncate ' + ink}>{o.storeName} <span className={faint} style={{ fontFamily: MONO, fontSize: 12 }}>· {o.code}</span></div>
                <div className={'text-xs ' + muted}>walked out {ago(o.at)} · {o.itemCount} item{o.itemCount === 1 ? '' : 's'} · {o.status === 'paid' ? <span style={{ color: GREEN }}>paid{o.payment ? ' · ' + o.payment : ''}</span> : <span style={{ color: RED }}>payment due</span>}{o.unpricedCount ? ' · ' + o.unpricedCount + ' not sold here' : ''}</div>
              </div>
              <div className={'text-sm tabular-nums ' + ink} style={{ fontFamily: MONO }}>{fmt(o.totalCents)}</div>
            </button>
            {open === o.key && (
              <div className="px-4 pb-3" style={{ fontFamily: MONO, fontSize: 12 }}>
                {o.lines.map((l) => (
                  <div key={String(l.id)} className={'flex gap-3 py-1 ' + muted}><span className="flex-1 truncate">{l.qty > 1 ? l.qty + ' × ' : ''}{l.name}</span><span>{fmt(l.priceCents * l.qty)}</span></div>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );

  const StoresView = () => (
    <div className="space-y-5">
      <div className={card + ' p-4'}>
        <div className={'text-sm font-medium ' + ink}>Pin a store where you are</div>
        <div className={'mt-1 text-xs ' + muted}>Creates a store at this device's location with a demo catalog. Phones standing here are placed in it by the database automatically.</div>
        <form onSubmit={pinHere} className="mt-3 flex gap-2">
          <input name="name" defaultValue="Demo Market" className={inputCls} aria-label="Store name" />
          <button disabled={!pos || !operator} className={btnPrimary + ' shrink-0'} style={{ background: ACCENT }}>Pin here</button>
        </form>
        {!pos && <div className={'mt-2 text-xs ' + faint}>{geoErr || 'Waiting for location…'}</div>}
      </div>
      <div className={card}>
        {storeList.map((s, i) => (
          <button key={s.id} onClick={() => nav({ view: 'store', store: s.id }, true)} className={'w-full text-left px-4 py-3 flex items-center gap-3 ' + (i ? 'border-t ' + line : '')}>
            <div className="min-w-0 flex-1">
              <div className={'text-sm truncate ' + ink}>{s.name}{s.id === selId ? <span className={faint}> · selected</span> : null}</div>
              <div className={'text-xs ' + muted}>{s.address ? s.address + ' · ' : ''}{s.radiusM} m radius{s.dist != null ? ' · ' + fmtDist(s.dist) + ' away' : ''}</div>
            </div>
            <span className={faint} aria-hidden="true">›</span>
          </button>
        ))}
      </div>
    </div>
  );

  const StoreDetail = (id) => {
    const s = storeList.find((x) => x.id === id);
    if (!s) return <div className={'py-10 text-center text-sm ' + muted}>Store not found.</div>;
    const prods = s.products.slice().sort((a, b) => a.name.localeCompare(b.name));
    return (
      <div className="space-y-4">
        <button onClick={() => nav({ view: 'stores', store: id }, false)} className={'text-sm ' + muted}>‹ All stores</button>
        <div className={card + ' p-4 space-y-3'}>
          <input key={'n' + s.name} defaultValue={s.name} aria-label="Store name" onBlur={(e) => { const v = e.target.value.trim(); if (v && v !== s.name) saveStore(s, { name: v }); }} className={inputCls + ' text-base font-medium'} />
          <input key={'a' + s.address} defaultValue={s.address} placeholder="Address" aria-label="Address" onBlur={(e) => { const v = e.target.value.trim(); if (v !== s.address) saveStore(s, { address: v }); }} className={inputCls} />
          <div className="grid grid-cols-2 gap-3">
            <label className={'text-xs ' + muted}>Detection radius (m)
              <input key={'r' + s.radiusM} type="number" min="10" defaultValue={s.radiusM} onBlur={(e) => { const v = parseInt(e.target.value, 10); if (v > 0 && v !== s.radiusM) saveStore(s, { radiusM: v }); }} className={inputCls + ' mt-1'} />
            </label>
            <div className={'text-xs ' + muted}>Location
              <div className={'mt-1 py-2 ' + ink} style={{ fontFamily: MONO, fontSize: 12 }}>{s.lat.toFixed(5)}, {s.lng.toFixed(5)}</div>
            </div>
          </div>
          {pos && <button className={btnGhost} onClick={() => saveStore(s, { lat: pos.lat, lng: pos.lng })}>Move store to my location</button>}
        </div>
        <div className={card}>
          <div className="px-4 pt-4 pb-2 flex items-baseline justify-between">
            <div className={'text-sm font-medium ' + ink}>Catalog</div>
            <div className={'text-xs ' + muted}>{prods.length} items · edits go live instantly</div>
          </div>
          {prods.map((p) => (
            <div key={p.sku} className={'px-4 py-2 flex items-center gap-2 border-t ' + line}>
              <div className={'shrink-0 ' + faint} style={{ fontFamily: MONO, fontSize: 11, width: 44 }}>{p.sku}</div>
              <input key={'pn' + p.sku + p.name} defaultValue={p.name} aria-label={'Name for ' + p.sku} onBlur={(e) => { const v = e.target.value.trim(); if (v && v !== p.name) saveProduct(s.id, p, { name: v }); }} className={'min-w-0 flex-1 bg-transparent text-sm outline-none ' + ink} />
              <input key={'pp' + p.sku + p.priceCents} defaultValue={(p.priceCents / 100).toFixed(2)} inputMode="decimal" aria-label={'Price for ' + p.name} onBlur={(e) => { const v = Math.round(parseFloat(e.target.value) * 100); if (v >= 0 && v !== p.priceCents) saveProduct(s.id, p, { priceCents: v }); }} className={'bg-transparent text-sm text-right tabular-nums outline-none ' + ink} style={{ fontFamily: MONO, width: 72 }} />
              <button aria-label={'Remove ' + p.name} onClick={() => R && run('Remove product', R.deleteProduct({ storeId: s.id, sku: p.sku }))} className={'w-7 h-7 rounded-full shrink-0 ' + faint + ' ' + ct('hover:bg-zinc-800', 'hover:bg-zinc-100')}>×</button>
            </div>
          ))}
          <form onSubmit={(e) => addProduct(e, s.id)} className={'px-4 py-3 border-t grid gap-2 ' + line} style={{ gridTemplateColumns: '70px 1fr 76px auto' }}>
            <input name="sku" placeholder="SKU" required className={inputCls} style={{ fontFamily: MONO }} />
            <input name="name" placeholder="Product" required className={inputCls} />
            <input name="price" placeholder="0.00" inputMode="decimal" required className={inputCls} style={{ fontFamily: MONO }} />
            <button className={btnGhost}>Add</button>
          </form>
        </div>
        {confirmDel === id ? (
          <div className={card + ' p-4'}>
            <div className={'text-sm ' + ink}>Delete {s.name} and its {prods.length} products?</div>
            <div className="mt-3 flex gap-2">
              <button className={btnGhost} onClick={() => setConfirmDel(null)}>Keep</button>
              <button className={btnPrimary} style={{ background: RED }} onClick={() => { setConfirmDel(null); R && run('Delete store', R.deleteStore({ id })); nav({ view: 'stores' }, false); }}>Delete store</button>
            </div>
          </div>
        ) : <button className={'text-sm underline ' + muted} onClick={() => setConfirmDel(id)}>Delete store</button>}
      </div>
    );
  };

  const AppView = () => (
    <div className="space-y-4">
      <div className={card + ' p-5'}>
        <div className="flex flex-wrap items-start gap-5">
          <div className="flex-1 min-w-0" style={{ minWidth: 220 }}>
            <div className={muted} style={CAP}>Customer app · Android</div>
            <div className={'mt-1 text-xl font-semibold tracking-tight ' + ink}>SmartBag {android ? android.version : ''}</div>
            <div className={'mt-2 text-sm ' + muted}>Native Kotlin app with its own SpacetimeDB identity, on one WebSocket to the database. It reports bag readings and GPS fixes; the database picks the store, prices the bag, and charges it the moment the shopper walks out. There is no pay button.</div>
            <div className="mt-4 flex flex-wrap gap-2">
              <a href={apkUrl || undefined} download="SmartBag.apk" className={btnPrimary + (apkUrl ? '' : ' pointer-events-none opacity-40')} style={{ background: ACCENT }}>Download APK</a>
              {srcUrl && <a href={srcUrl} download="SmartBag-source.zip" className={btnGhost}>Source code</a>}
            </div>
            <ol className={'mt-5 space-y-1.5 text-sm ' + muted} style={{ paddingLeft: 18, listStyle: 'decimal' }}>
              <li>Scan the code with the Android phone, or open this page on it and tap Download.</li>
              <li>Open the file and allow installs from this source when Android asks.</li>
              <li>Launch SmartBag, tap Get started, and allow Bluetooth and Location.</li>
            </ol>
          </div>
          <div className="flex flex-col items-center">
            {qr ? <img src={qr} alt="QR code to download the SmartBag APK" className="rounded-lg" style={{ width: 168, height: 168 }} />
              : <div className={'rounded-lg border ' + line} style={{ width: 168, height: 168 }} />}
            <div className={'mt-2 ' + faint} style={{ fontSize: 11 }}>Scan to install</div>
          </div>
        </div>
      </div>
      <div className={'text-xs ' + faint}>Demo tips: long-press the bag status in the app to drop in a random item without hardware. Long-press the store name to simulate walking out (two GPS fixes 300 m away), which charges the bag.</div>
    </div>
  );

  const HardwareView = () => {
    const proto = [['1005  or  +1005', 'add one'], ['-1005', 'remove one'], ['+1005@E28011', 'add, ignore repeat reads of that tag'], ['{"op":"add","sku":"1005"}', 'JSON works too']];
    return (
      <div className="space-y-4">
        <div className={card + ' p-4'}>
          <div className={'text-sm font-medium ' + ink}>Bag protocol</div>
          <div className={'mt-0.5 text-xs ' + muted}>BLE Nordic UART Service. The bag notifies newline-terminated text on TX; the phone forwards each line to the database, which parses it, and answers the bag on RX with OK +1005 4.69, ERR 9999 unknown, or PAID 7KQ2XD once the shopper walks out.</div>
          <div className="mt-3" style={{ fontFamily: MONO, fontSize: 12 }}>
            {proto.map(([a, b]) => (
              <div key={a} className={'flex items-baseline gap-3 py-1.5 border-t ' + line}><span className={'shrink-0 ' + ink}>{a}</span><span className={'ml-auto text-right ' + muted}>{b}</span></div>
            ))}
            {[['Service', NUS], ['TX (notify)', NUS_TX], ['RX (write)', NUS_RX]].map(([a, b]) => (
              <div key={a} className={'flex items-baseline gap-3 py-1.5 border-t ' + line}>
                <span className={'shrink-0 ' + muted}>{a}</span>
                <button onClick={() => copy(a, b)} className={'ml-auto truncate text-right ' + ink} title="Copy">{copied === a ? 'copied' : b}</button>
              </div>
            ))}
          </div>
        </div>
        <div className={card}>
          <div className="px-4 pt-4 pb-2 flex items-center justify-between">
            <div>
              <div className={'text-sm font-medium ' + ink}>ESP32 firmware</div>
              <div className={'text-xs ' + muted}>Arduino sketch, advertises as SmartBag-01</div>
            </div>
            <button className={btnGhost} onClick={() => copy('fw', firmware)} disabled={!firmware}>{copied === 'fw' ? 'Copied' : copied === 'failed' ? 'Copy failed' : 'Copy'}</button>
          </div>
          <pre className={'mx-4 mb-4 mt-1 p-3 rounded-lg overflow-auto ' + ct('bg-zinc-950 text-zinc-300', 'bg-zinc-50 text-zinc-700')} style={{ fontFamily: MONO, fontSize: 11, maxHeight: 320, lineHeight: 1.5 }}>{firmware || 'Loading…'}</pre>
        </div>
      </div>
    );
  };

  const tabs = [['live', 'Live'], ['orders', 'Orders'], ['store', 'Store'], ['stores', 'All stores'], ['app', 'Customer app'], ['hardware', 'Bag hardware']];
  const needsDb = ['live', 'orders', 'store', 'stores'].includes(route.view) || !tabs.some(([t]) => t === route.view);
  const scopedReady = scoped === selId;
  let content;
  if (needsDb && !conn) content = Waiting();
  else if (['live', 'orders', 'store'].includes(route.view) && !selId) content = <div className={'py-16 text-center text-sm ' + muted}>No stores yet. Pin one under All stores.</div>;
  else if (['live', 'orders', 'store'].includes(route.view) && !scopedReady) content = <div className={'py-16 text-center text-sm ' + muted}>Loading {sel ? sel.name : 'store'}…</div>;
  else if (route.view === 'orders') content = OrdersView();
  else if (route.view === 'store') content = StoreDetail(selId);
  else if (route.view === 'stores') content = StoresView();
  else if (route.view === 'app') content = AppView();
  else if (route.view === 'hardware') content = HardwareView();
  else content = LiveView();

  return (
    <div className="w-full flex justify-center" style={{ height: 'var(--mana-app-height, 82vh)' }}>
      <style>{CSS}</style>
      <div className="w-full h-full flex flex-col" style={{ maxWidth: 980 }}>
        <div className="px-4 pt-3 pb-3 flex flex-wrap items-center justify-between gap-3">
          <div>
            <div className={'text-base font-semibold tracking-tight ' + ink}>SmartBag Console</div>
            <div className={'text-xs flex items-center gap-1.5 ' + muted}>
              <span className={'inline-block w-1.5 h-1.5 rounded-full ' + (connected ? 'sbc-live' : '')} style={{ background: connected ? GREEN : status.startsWith('error') ? RED : '#a1a1aa' }} />
              {connected ? 'Live on SpacetimeDB · ' + (cfg ? cfg.db : '') : status.startsWith('error') ? 'SpacetimeDB unreachable' : status === 'reconnecting' ? 'Reconnecting to SpacetimeDB…' : 'Connecting to SpacetimeDB…'}
              {connected && operator === false ? ' · read-only' : ''}
            </div>
          </div>
          {allStores.length > 0 && (
            <select value={selId || ''} onChange={(e) => nav({ view: route.view === 'stores' ? 'live' : route.view, store: e.target.value }, true)} aria-label="Store"
              className={'rounded-full border px-3 py-1.5 text-sm outline-none ' + line + ' ' + ink + ' ' + ct('bg-zinc-900', 'bg-white')} style={{ maxWidth: 220 }}>
              {allStores.slice().sort((a, b) => a.name.localeCompare(b.name)).map((st) => <option key={st.id} value={st.id}>{st.name}</option>)}
            </select>
          )}
          <div className={'flex flex-wrap gap-1 rounded-full border p-1 ' + line}>
            {tabs.map(([v, label]) => {
              const on = route.view === v || (v === 'live' && !tabs.some(([t]) => t === route.view));
              return (
                <button key={v} onClick={() => nav({ view: v, store: route.store }, false)} className={'px-3 py-1.5 text-sm rounded-full ' + (on ? ink + ' font-medium ' + ct('bg-zinc-800', 'bg-zinc-100') : muted)}>
                  {label}{v === 'orders' && today.length ? ' · ' + today.length : ''}
                </button>
              );
            })}
          </div>
        </div>
        {notice && <div className="mx-4 mb-2 text-xs" style={{ color: RED }}>{notice}</div>}
        <div className="flex-1 min-h-0 overflow-y-auto px-4 pb-8">{content}</div>
      </div>
    </div>
  );
}

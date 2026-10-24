import { schema, table, t, SenderError, type InferSchema, type ReducerCtx } from 'spacetimedb/server';
import { ScheduleAt } from 'spacetimedb';

// SmartBag v3: built to scale to thousands of stores and shoppers.
// - Store lookup is a spatial grid index (0.01° cells), so a GPS fix touches only nearby stores.
// - Everything a store needs (shoppers, carts, orders, bag feed) is indexed by store, so a console
//   subscribes to one store's rows instead of the whole database.
// - Phones use procedures: one HTTP round trip per action returns the fresh, priced cart.
// - Shoppers never press Pay: the bag is charged automatically when the phone leaves the store.
// - Stores are multi-tenant: each has its own staff; global admins see everything.
// - A scheduled job trims the bag feed and idle shoppers so tables don't grow without bound.

const MAX_RADIUS_M = 400;          // keeps every store inside the 3x3 grid neighbourhood of a fix
const CELL = 100;                  // cells per degree (0.01°)
const FEED_TTL_MICROS = 3_600_000_000n;        // bag feed kept for 1 hour
const IDLE_SHOPPER_MICROS = 43_200_000_000n;   // shoppers with empty bags dropped after 12 hours
const MAX_STORES_PER_OWNER = 5;
const EXIT_MARGIN_M = 40;          // you have left once you are this far beyond the store radius…
const EXIT_MAX_ACCURACY_M = 75;    // …measured by a fix at least this accurate…
const EXIT_FIXES = 2;              // …twice in a row. Then the bag is charged automatically.

// ───────────────────────── Tables ─────────────────────────

const store = table({ name: 'store', public: true }, {
  id: t.string().primaryKey(),
  name: t.string(),
  address: t.string(),
  lat: t.f64(),
  lng: t.f64(),
  radiusM: t.u32(),
  cell: t.string().index('btree'),
});

const product = table(
  { name: 'product', public: true, indexes: [{ accessor: 'by_store_sku', algorithm: 'btree', columns: ['storeId', 'sku'] }] },
  { id: t.u64().primaryKey().autoInc(), storeId: t.string(), sku: t.string(), name: t.string(), priceCents: t.u32(), unit: t.string() }
);

const shopper = table({ name: 'shopper', public: true }, {
  identity: t.identity().primaryKey(),
  storeId: t.string().index('btree'),
  storeName: t.string(),
  storeAddress: t.string(),
  distanceM: t.u32(),
  nearestName: t.string(),
  nearestM: t.u32(),
  lat: t.f64(),
  lng: t.f64(),
  bag: t.string(),
  payment: t.string(),        // label of the saved payment method, e.g. "Visa •••• 4242" ('' = none yet)
  outsideFixes: t.u32(),      // consecutive accurate fixes outside the current store
  updatedAt: t.timestamp(),
});

const cartItem = table(
  { name: 'cart_item', public: true, indexes: [{ accessor: 'by_shopper_sku', algorithm: 'btree', columns: ['shopper', 'sku'] }] },
  {
    id: t.u64().primaryKey().autoInc(),
    shopper: t.identity(),
    storeId: t.string().index('btree'),   // denormalised from shopper so a store console can subscribe to its own carts
    sku: t.string(),
    qty: t.u32(),
    tags: t.string(),
    addedAt: t.timestamp(),
  }
);

const shopOrder = table(
  { name: 'shop_order', public: true, indexes: [{ accessor: 'by_store_day', algorithm: 'btree', columns: ['storeId', 'day'] }] },
  {
    id: t.u64().primaryKey().autoInc(),
    code: t.string().unique(),
    requestId: t.string().unique(),
    shopper: t.identity().index('btree'),
    storeId: t.string(),
    day: t.string(),                       // UTC yyyy-mm-dd
    storeName: t.string(),
    totalCents: t.u32(),
    itemCount: t.u32(),
    unpricedCount: t.u32(),   // items in the bag that this store does not sell (not charged)
    status: t.string(),       // 'paid' | 'payment_due' (no payment method on file)
    payment: t.string(),
    trigger: t.string(),      // 'walkout'
    paidAt: t.timestamp(),
  }
);

const orderLine = table(
  { name: 'order_line', public: true, indexes: [{ accessor: 'by_store_day', algorithm: 'btree', columns: ['storeId', 'day'] }] },
  {
    id: t.u64().primaryKey().autoInc(),
    orderId: t.u64().index('btree'),
    storeId: t.string(),
    day: t.string(),
    sku: t.string(),
    name: t.string(),
    priceCents: t.u32(),
    qty: t.u32(),
  }
);

const bagEvent = table({ name: 'bag_event', public: true }, {
  id: t.u64().primaryKey().autoInc(),
  shopper: t.identity(),
  storeId: t.string().index('btree'),
  line: t.string(),
  result: t.string(),
  at: t.timestamp(),
});

// Private
const operator = table({ name: 'operator' }, { identity: t.identity().primaryKey() });     // global admins
const config = table({ name: 'config' }, { key: t.string().primaryKey(), value: t.string() });
const storeStaff = table({ name: 'store_staff' }, {
  id: t.u64().primaryKey().autoInc(),
  identity: t.identity().index('btree'),
  storeId: t.string().index('btree'),
});
const storeInvite = table({ name: 'store_invite' }, { storeId: t.string().primaryKey(), code: t.string().unique() });

const cleanupTimer = table({ name: 'cleanup_timer' }, {
  scheduledId: t.u64().primaryKey().autoInc(),
  scheduledAt: t.scheduleAt(),
});


// ── Accounts (v4, additive): people sign in with SpacetimeAuth (OIDC); their SpacetimeDB identity is
// derived from the token's issuer + subject, so the same person has the same identity on every device.
const account = table({ name: 'account' }, {          // private: holds emails
  identity: t.identity().primaryKey(),
  name: t.string(),
  email: t.string().index('btree'),
  emailVerified: t.bool(),
  picture: t.string(),
  subject: t.string(),
  signedInAt: t.timestamp(),
});
const profile = table({ name: 'profile', public: true }, {   // public: display name only, never email
  identity: t.identity().primaryKey(),
  name: t.string(),
});
const staffEmailInvite = table({ name: 'staff_email_invite' }, {   // private
  id: t.u64().primaryKey().autoInc(),
  storeId: t.string().index('btree'),
  email: t.string().index('btree'),
  invitedBy: t.string(),
  at: t.timestamp(),
});
const storeOwner = table({ name: 'store_owner' }, {    // private: who opened a store (owners can delete it and manage the team)
  id: t.u64().primaryKey().autoInc(),
  storeId: t.string().index('btree'),
  identity: t.identity().index('btree'),
});
const memberName = table({ name: 'member_name' }, {    // private: display names of signed-in people (read via caller-scoped views)
  identity: t.identity().primaryKey(),
  name: t.string(),
});
const activity = table({ name: 'activity' }, {        // private: who changed what, read through my_store_activity
  id: t.u64().primaryKey().autoInc(),
  storeId: t.string().index('btree'),
  actor: t.identity(),
  actorName: t.string(),
  action: t.string(),
  at: t.timestamp(),
});

const spacetimedb = schema({
  store, product, shopper, cartItem, shopOrder, orderLine, bagEvent, operator, config, storeStaff, storeInvite, cleanupTimer,
  account, profile, staffEmailInvite, activity, storeOwner, memberName,
});
export default spacetimedb;

type Ctx = ReducerCtx<InferSchema<typeof spacetimedb>>;

// ───────────────────────── Helpers ─────────────────────────

const DEMO: Array<[string, string, number, string]> = [
  ['1001', 'Organic bananas', 129, 'bunch'], ['1002', 'Hass avocado', 179, 'each'], ['1003', 'Honeycrisp apples', 399, '2 lb bag'],
  ['1004', 'Baby spinach', 349, '5 oz'], ['1005', 'Whole milk', 469, 'half gallon'], ['1006', 'Greek yogurt', 599, '32 oz'],
  ['1007', 'Free-range eggs', 549, 'dozen'], ['1008', 'Sourdough loaf', 649, 'each'], ['1009', 'Cold brew coffee', 499, '32 oz'],
  ['1010', 'Sparkling water', 699, '8 pack'], ['1011', 'Dark chocolate', 349, 'bar'], ['1012', 'Tortilla chips', 429, 'bag'],
  ['1013', 'Spaghetti', 229, '1 lb'], ['1014', 'Marinara sauce', 549, 'jar'], ['1015', 'Aged cheddar', 499, '8 oz'],
  ['1016', 'Oat milk', 499, 'half gallon'],
];
const CODE_CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

function haversine(aLat: number, aLng: number, bLat: number, bLng: number): number {
  const r = (x: number) => (x * Math.PI) / 180;
  const dLat = r(bLat - aLat), dLng = r(bLng - aLng);
  const s = Math.sin(dLat / 2) ** 2 + Math.cos(r(aLat)) * Math.cos(r(bLat)) * Math.sin(dLng / 2) ** 2;
  return 2 * 6371000 * Math.asin(Math.sqrt(s));
}
function cellKey(i: number, j: number) { return i + ':' + j; }
function cellOf(lat: number, lng: number) { return cellKey(Math.floor(lat * CELL), Math.floor(lng * CELL)); }

function randomCode(ctx: Ctx, n: number): string {
  let s = '';
  for (let i = 0; i < n; i++) s += CODE_CHARS[ctx.random.integerInRange(0, CODE_CHARS.length - 1)];
  return s;
}
function dayOf(ctx: Ctx): string { return new Date(Number(ctx.timestamp.microsSinceUnixEpoch / 1000n)).toISOString().slice(0, 10); }

// A SpacetimeAuth token minted for some other app (wrong audience) never carries privileges here.
function authOk(ctx: Ctx) {
  const jwt = (ctx.senderAuth as any)?.jwt;
  return !(jwt && jwt.issuer === AUTH_ISSUER && !verifiedClaims(ctx));
}
function isAdmin(ctx: Ctx) { return !!ctx.db.operator.identity.find(ctx.sender) && authOk(ctx); }
function isOwnerOf(ctx: Ctx, storeId: string) {
  if (isAdmin(ctx)) return true;
  if (!authOk(ctx)) return false;
  for (const o of ctx.db.storeOwner.identity.filter(ctx.sender)) if (o.storeId === storeId) return true;
  return false;
}
function requireOwner(ctx: Ctx, storeId: string) { if (!isOwnerOf(ctx, storeId)) throw new SenderError('Only the store’s owner can do that.'); }
function isStaff(ctx: Ctx, storeId: string) {
  if (isAdmin(ctx)) return true;
  if (!authOk(ctx)) return false;
  for (const s of ctx.db.storeStaff.identity.filter(ctx.sender)) if (s.storeId === storeId) return true;
  return false;
}
function requireStaff(ctx: Ctx, storeId: string) { if (!isStaff(ctx, storeId)) throw new SenderError('Only staff of this store can do that.'); }
function requireAdmin(ctx: Ctx) { if (!isAdmin(ctx)) throw new SenderError('Admins only.'); }

const AUTH_ISSUER = 'https://auth.spacetimedb.com/oidc';
function shortHex(id: { toHexString(): string }) { return id.toHexString().slice(-6); }
function actorName(ctx: Ctx) {
  const p = ctx.db.memberName.identity.find(ctx.sender);
  if (p && p.name) return p.name;
  return ctx.db.operator.identity.find(ctx.sender) ? 'Admin console' : 'Staff ·' + shortHex(ctx.sender);
}
function logActivity(ctx: Ctx, storeId: string, action: string) {
  ctx.db.activity.insert({ id: 0n, storeId, actor: ctx.sender, actorName: actorName(ctx).slice(0, 60), action: action.slice(0, 160), at: ctx.timestamp });
}
function addStaff(ctx: Ctx, who: any, storeId: string) {
  for (const s of ctx.db.storeStaff.identity.filter(who)) if (s.storeId === storeId) return false;
  ctx.db.storeStaff.insert({ id: 0n, identity: who, storeId });
  return true;
}
// Accept only SpacetimeAuth tokens issued for this app's own client(s) (config 'auth_client_ids').
function verifiedClaims(ctx: Ctx): any {
  const jwt = (ctx.senderAuth as any)?.jwt;
  if (!jwt || jwt.issuer !== AUTH_ISSUER) return null;
  const c = ctx.db.config.key.find('auth_client_ids');
  const allowed = c ? c.value.split(',').map((x: string) => x.trim()).filter(Boolean) : [];
  if (!allowed.length || !jwt.audience.some((a: string) => allowed.includes(a))) return null;
  return jwt;
}
function doSignIn(ctx: Ctx): boolean {
  const jwt = verifiedClaims(ctx);
  if (!jwt) return false;
  const p: any = jwt.fullPayload || {};
  const email = String(p.email || '').trim().toLowerCase().slice(0, 120);
  const verified = p.email_verified === true || p.email_verified === 'true' || p.login_method === 'magic_link';
  const name = String(p.name || p.preferred_username || p.nickname || p.given_name || (email ? email.split('@')[0] : '') || 'Member').slice(0, 60);
  const row = { identity: ctx.sender, name, email, emailVerified: verified, picture: String(p.picture || '').slice(0, 300), subject: String(jwt.subject).slice(0, 120), signedInAt: ctx.timestamp };
  const cur = ctx.db.account.identity.find(ctx.sender);
  if (cur) ctx.db.account.identity.update({ ...row, name: cur.name || name }); else ctx.db.account.insert(row);
  if (!ctx.db.memberName.identity.find(ctx.sender)) ctx.db.memberName.insert({ identity: ctx.sender, name });
  // Pending email invites are shown to the invitee (my_invites) and only take effect when they accept.
  return true;
}


function findProduct(ctx: Ctx, storeId: string, sku: string) {
  for (const p of ctx.db.product.by_store_sku.filter([storeId, sku])) return p;
  return null;
}
function findCartLine(ctx: Ctx, sku: string) {
  for (const c of ctx.db.cartItem.by_shopper_sku.filter([ctx.sender, sku])) return c;
  return null;
}
function shopperRow(ctx: Ctx) {
  const s = ctx.db.shopper.identity.find(ctx.sender);
  if (s) return s;
  return ctx.db.shopper.insert({
    identity: ctx.sender, storeId: '', storeName: '', storeAddress: '', distanceM: 0, nearestName: '', nearestM: 0,
    lat: 0, lng: 0, bag: '', payment: '', outsideFixes: 0, updatedAt: ctx.timestamp,
  });
}
function logBag(ctx: Ctx, storeId: string, line: string, result: string) {
  ctx.db.bagEvent.insert({ id: 0n, shopper: ctx.sender, storeId, line, result, at: ctx.timestamp });
}
function addCatalog(ctx: Ctx, storeId: string, adjust: Record<string, number>, drop: string[]) {
  for (const [sku, pname, price, unit] of DEMO) {
    if (drop.includes(sku)) continue;
    ctx.db.product.insert({ id: 0n, storeId, sku, name: pname, priceCents: Math.max(1, price + (adjust[sku] ?? 0)), unit });
  }
}
function createStore(ctx: Ctx, id: string, name: string, address: string, lat: number, lng: number, radiusM: number) {
  if (!(lat >= -85 && lat <= 85 && lng >= -180 && lng <= 180)) throw new SenderError('Invalid location.');
  ctx.db.store.insert({ id, name: name.slice(0, 80), address: address.slice(0, 120), lat, lng, radiusM: Math.min(MAX_RADIUS_M, Math.max(10, radiusM)), cell: cellOf(lat, lng) });
  let code = randomCode(ctx, 8);
  while (ctx.db.storeInvite.code.find(code)) code = randomCode(ctx, 8);
  ctx.db.storeInvite.insert({ storeId: id, code });
}
function removeStore(ctx: Ctx, id: string) {
  for (const p of [...ctx.db.product.by_store_sku.filter(id)]) ctx.db.product.id.delete(p.id);
  for (const s of [...ctx.db.storeStaff.storeId.filter(id)]) ctx.db.storeStaff.id.delete(s.id);
  ctx.db.storeInvite.storeId.delete(id);
  for (const o of [...ctx.db.storeOwner.storeId.filter(id)]) ctx.db.storeOwner.id.delete(o.id);
  for (const c of [...ctx.db.cartItem.storeId.filter(id)]) ctx.db.cartItem.id.delete(c.id);
  for (const sh of [...ctx.db.shopper.storeId.filter(id)]) ctx.db.shopper.identity.update({ ...sh, storeId: '', storeName: '', storeAddress: '', outsideFixes: 0 });
  for (const i of [...ctx.db.staffEmailInvite.storeId.filter(id)]) ctx.db.staffEmailInvite.id.delete(i.id);
  ctx.db.store.id.delete(id);
}

// ───────────────────────── Core shopper logic (shared by reducers and procedures) ─────────────────────────

// Geofencing via the grid index: only stores in the 3x3 cells around the fix are examined.
// Membership is sticky: once in a store you stay in it until you have clearly left, and leaving
// is what pays (see walkOut).
function doLocate(ctx: Ctx, lat: number, lng: number, accuracyM: number) {
  const acc = Math.min(Math.max(accuracyM, 0), 100);
  const ci = Math.floor(lat * CELL), cj = Math.floor(lng * CELL);
  type Hit = { id: string; name: string; address: string; d: number };
  const found: { inside: Hit | null; nearest: Hit | null } = { inside: null, nearest: null };
  for (let ring = 0; ring <= 2; ring++) {
    for (let di = -ring; di <= ring; di++) for (let dj = -ring; dj <= ring; dj++) {
      if (Math.max(Math.abs(di), Math.abs(dj)) !== ring) continue;
      for (const s of ctx.db.store.cell.filter(cellKey(ci + di, cj + dj))) {
        const d = haversine(lat, lng, s.lat, s.lng);
        const hit = { id: s.id, name: s.name, address: s.address, d };
        if (!found.nearest || d < found.nearest.d) found.nearest = hit;
        if (ring <= 1 && d <= s.radiusM + acc && (!found.inside || d < found.inside.d)) found.inside = hit;
      }
    }
    if (found.inside && ring >= 1) break;
  }
  let row = shopperRow(ctx);
  const nearest = found.nearest;
  let keep: Hit | null = null;
  let outside = 0;
  if (row.storeId) {
    const cur = ctx.db.store.id.find(row.storeId);
    if (cur) {
      const d = haversine(lat, lng, cur.lat, cur.lng);
      const clearlyOut = d > cur.radiusM + EXIT_MARGIN_M + acc && accuracyM <= EXIT_MAX_ACCURACY_M;
      if (!clearlyOut) keep = { id: cur.id, name: cur.name, address: cur.address, d };          // still here (or fix too vague to tell)
      else if (row.outsideFixes + 1 < EXIT_FIXES) { keep = { id: cur.id, name: cur.name, address: cur.address, d }; outside = row.outsideFixes + 1; }
      else { walkOut(ctx, cur); row = ctx.db.shopper.identity.find(ctx.sender)!; }             // left: the bag is paid for
    }
  }
  const at = keep ?? found.inside;
  const newStore = at ? at.id : '';
  ctx.db.shopper.identity.update({
    ...row,
    storeId: newStore,
    storeName: at ? at.name : '',
    storeAddress: at ? at.address : '',
    distanceM: at ? Math.round(at.d) : 0,
    nearestName: nearest ? nearest.name : '',
    nearestM: nearest ? Math.round(nearest.d) : 0,
    outsideFixes: outside,
    lat, lng, updatedAt: ctx.timestamp,
  });
  if (row.storeId !== newStore) {
    for (const c of [...ctx.db.cartItem.by_shopper_sku.filter(ctx.sender)]) ctx.db.cartItem.id.update({ ...c, storeId: newStore });
  }
}

// The shopper walked out of `st` with their bag: price it at this store, charge the saved payment
// method, record the order and empty the bag. Items the store does not sell are not charged.
function walkOut(ctx: Ctx, st: { id: string; name: string }) {
  const row = ctx.db.shopper.identity.find(ctx.sender)!;
  const items = [...ctx.db.cartItem.by_shopper_sku.filter(ctx.sender)];
  ctx.db.shopper.identity.update({ ...row, storeId: '', storeName: '', storeAddress: '', outsideFixes: 0 });
  if (items.length === 0) return null;
  const lines: Array<{ sku: string; name: string; priceCents: number; qty: number }> = [];
  let unpriced = 0;
  for (const it of items) {
    const p = findProduct(ctx, st.id, it.sku);
    if (p) lines.push({ sku: it.sku, name: p.name, priceCents: p.priceCents, qty: it.qty }); else unpriced += it.qty;
  }
  for (const it of items) ctx.db.cartItem.id.delete(it.id);
  if (lines.length === 0) { logBag(ctx, st.id, 'WALKOUT', 'left, nothing to charge'); return null; }
  let code = randomCode(ctx, 6);
  while (ctx.db.shopOrder.code.find(code)) code = randomCode(ctx, 6);
  const total = lines.reduce((a, l) => a + l.priceCents * l.qty, 0);
  const count = lines.reduce((a, l) => a + l.qty, 0);
  const day = dayOf(ctx);
  const order = ctx.db.shopOrder.insert({
    id: 0n, code, requestId: 'walkout-' + code, shopper: ctx.sender, storeId: st.id, day, storeName: st.name,
    totalCents: total, itemCount: count, unpricedCount: unpriced,
    status: row.payment ? 'paid' : 'payment_due', payment: row.payment, trigger: 'walkout', paidAt: ctx.timestamp,
  });
  for (const l of lines) ctx.db.orderLine.insert({ id: 0n, orderId: order.id, storeId: st.id, day, ...l });
  logBag(ctx, st.id, 'WALKOUT ' + code, (row.payment ? 'charged ' : 'payment due ') + '$' + (total / 100).toFixed(2));
  return order;
}

type BagEvt = { op: 'add' | 'remove'; sku: string; tag: string; qty: number };
function parseBagLine(raw: string): BagEvt | null {
  const line = raw.trim();
  if (!line) return null;
  const clean = (s: string) => s.trim().replace(/[^A-Za-z0-9_\-]/g, '').slice(0, 40);
  if (line.startsWith('{')) {
    try {
      const j = JSON.parse(line);
      const sku = clean(String(j.sku ?? j.id ?? j.code ?? ''));
      if (!sku) return null;
      const op = String(j.op ?? 'add').toLowerCase();
      return { op: op === 'remove' || op === 'del' || op === 'rm' ? 'remove' : 'add', sku, tag: clean(String(j.uid ?? '')), qty: Math.max(1, Math.min(50, Number(j.qty) || 1)) };
    } catch { return null; }
  }
  let op: 'add' | 'remove' = 'add';
  let rest = line;
  const m = line.match(/^(ADD|DEL|REMOVE|RM)\s+(.+)$/i);
  if (m) { op = m[1].toUpperCase() === 'ADD' ? 'add' : 'remove'; rest = m[2]; }
  else if (line.startsWith('+')) rest = line.slice(1);
  else if (line.startsWith('-')) { op = 'remove'; rest = line.slice(1); }
  const at = rest.indexOf('@');
  const sku = clean(at >= 0 ? rest.slice(0, at) : rest);
  const tag = at >= 0 ? clean(rest.slice(at + 1)) : '';
  if (!sku) return null;
  return { op, sku, tag, qty: 1 };
}

function doBagLine(ctx: Ctx, rawIn: string) {
  const raw = rawIn.slice(0, 200);
  const sh = shopperRow(ctx);
  const ev = parseBagLine(raw);
  if (!ev) { logBag(ctx, sh.storeId, raw, 'unreadable'); return; }
  const cur = findCartLine(ctx, ev.sku);
  const p = sh.storeId ? findProduct(ctx, sh.storeId, ev.sku) : null;
  if (ev.op === 'add') {
    const tags = cur && cur.tags ? cur.tags.split(',') : [];
    if (ev.tag && tags.includes(ev.tag)) { logBag(ctx, sh.storeId, raw, 'already in bag'); return; }
    if (ev.tag) { tags.push(ev.tag); while (tags.length > 50) tags.shift(); }
    if (cur) ctx.db.cartItem.id.update({ ...cur, qty: Math.min(999, cur.qty + ev.qty), tags: tags.join(','), storeId: sh.storeId });
    else ctx.db.cartItem.insert({ id: 0n, shopper: ctx.sender, storeId: sh.storeId, sku: ev.sku, qty: ev.qty, tags: tags.join(','), addedAt: ctx.timestamp });
    logBag(ctx, sh.storeId, raw, p ? `+ ${p.name}` : sh.storeId ? 'not sold here' : 'added, no store yet');
  } else {
    if (!cur) { logBag(ctx, sh.storeId, raw, 'not in bag'); return; }
    if (cur.qty <= ev.qty) ctx.db.cartItem.id.delete(cur.id);
    else {
      const tags = cur.tags ? cur.tags.split(',') : [];
      const i = ev.tag ? tags.indexOf(ev.tag) : 0;
      if (tags.length && i >= 0) tags.splice(i, 1);
      ctx.db.cartItem.id.update({ ...cur, qty: cur.qty - ev.qty, tags: tags.join(',') });
    }
    logBag(ctx, sh.storeId, raw, `− ${p ? p.name : ev.sku}`);
  }
}

function doSetBag(ctx: Ctx, name: string) {
  const row = shopperRow(ctx);
  ctx.db.shopper.identity.update({ ...row, bag: name.slice(0, 40), updatedAt: ctx.timestamp });
}

// Demo: a label for the payment method that is charged on walk-out (no real card is stored).
function doSetPayment(ctx: Ctx, label: string) {
  const row = shopperRow(ctx);
  ctx.db.shopper.identity.update({ ...row, payment: label.slice(0, 40), updatedAt: ctx.timestamp });
}

function doRemove(ctx: Ctx, sku: string) {
  const cur = findCartLine(ctx, sku);
  if (cur) ctx.db.cartItem.id.delete(cur.id);
}

// ───────────────────────── Return types ─────────────────────────

const CartLine = t.object('CartLine', {
  sku: t.string(), name: t.string(), qty: t.u32(), priceCents: t.u32(), lineCents: t.u32(), sold: t.bool(), addedMicros: t.i64(),
});
const ReceiptLine = t.object('ReceiptLine', { sku: t.string(), name: t.string(), priceCents: t.u32(), qty: t.u32() });
const Receipt = t.object('Receipt', {
  orderId: t.u64(), code: t.string(), storeName: t.string(), totalCents: t.u32(), itemCount: t.u32(), unpricedCount: t.u32(),
  status: t.string(), payment: t.string(), paidMicros: t.i64(), lines: t.array(ReceiptLine),
});
const CartState = t.object('CartState', {
  storeId: t.string(), storeName: t.string(), storeAddress: t.string(), distanceM: t.u32(),
  nearestName: t.string(), nearestM: t.u32(), bag: t.string(), payment: t.string(), leaving: t.bool(),
  totalCents: t.u32(), itemCount: t.u32(), lines: t.array(CartLine),
  lastReceipt: Receipt,   // the shopper's latest walk-out order (orderId 0 = none yet)
});


type CartLineT = { sku: string; name: string; qty: number; priceCents: number; lineCents: number; sold: boolean; addedMicros: bigint };
function pricedLines(ctx: Ctx, storeId: string): CartLineT[] {
  const out: CartLineT[] = [];
  for (const it of ctx.db.cartItem.by_shopper_sku.filter(ctx.sender)) {
    const p = storeId ? findProduct(ctx, storeId, it.sku) : null;
    out.push({ sku: it.sku, name: p ? p.name : '', qty: it.qty, priceCents: p ? p.priceCents : 0, lineCents: p ? p.priceCents * it.qty : 0, sold: !!p, addedMicros: it.addedAt.microsSinceUnixEpoch });
  }
  out.sort((a, b) => (a.addedMicros < b.addedMicros ? -1 : 1));
  return out;
}
const NO_RECEIPT = { orderId: 0n, code: '', storeName: '', totalCents: 0, itemCount: 0, unpricedCount: 0, status: '', payment: '', paidMicros: 0n, lines: [] as Array<{ sku: string; name: string; priceCents: number; qty: number }> };
function receiptOf(ctx: Ctx, order: { id: bigint; code: string; storeName: string; totalCents: number; itemCount: number; unpricedCount: number; status: string; payment: string; paidAt: { microsSinceUnixEpoch: bigint } }) {
  const lines = [...ctx.db.orderLine.orderId.filter(order.id)].map(l => ({ sku: l.sku, name: l.name, priceCents: l.priceCents, qty: l.qty }));
  return { orderId: order.id, code: order.code, storeName: order.storeName, totalCents: order.totalCents, itemCount: order.itemCount,
    unpricedCount: order.unpricedCount, status: order.status, payment: order.payment, paidMicros: order.paidAt.microsSinceUnixEpoch, lines };
}
function cartState(ctx: Ctx) {
  const s = ctx.db.shopper.identity.find(ctx.sender);
  const storeId = s ? s.storeId : '';
  const lines = pricedLines(ctx, storeId);
  const sold = lines.filter(l => l.sold);
  let last: any = null;
  for (const o of ctx.db.shopOrder.shopper.filter(ctx.sender)) if (!last || o.id > last.id) last = o;
  return {
    storeId, storeName: s ? s.storeName : '', storeAddress: s ? s.storeAddress : '', distanceM: s ? s.distanceM : 0,
    nearestName: s ? s.nearestName : '', nearestM: s ? s.nearestM : 0, bag: s ? s.bag : '', payment: s ? s.payment : '',
    leaving: !!s && s.outsideFixes > 0,
    totalCents: sold.reduce((a, l) => a + l.lineCents, 0), itemCount: sold.reduce((a, l) => a + l.qty, 0), lines,
    lastReceipt: last ? receiptOf(ctx, last) : NO_RECEIPT,
  };
}

// ───────────────────────── Phone API: procedures (one round trip, returns the fresh priced cart) ─────────────────────────

export const shopState = spacetimedb.procedure(CartState, ctx => ctx.withTx(tx => cartState(tx)));
export const shopLocate = spacetimedb.procedure({ lat: t.f64(), lng: t.f64(), accuracyM: t.f64() }, CartState,
  (ctx, a) => ctx.withTx(tx => { doLocate(tx, a.lat, a.lng, a.accuracyM); return cartState(tx); }));
export const shopBagLine = spacetimedb.procedure({ line: t.string() }, CartState,
  (ctx, { line }) => ctx.withTx(tx => { doBagLine(tx, line); return cartState(tx); }));
export const shopSetBag = spacetimedb.procedure({ name: t.string() }, CartState,
  (ctx, { name }) => ctx.withTx(tx => { doSetBag(tx, name); return cartState(tx); }));
export const shopRemove = spacetimedb.procedure({ sku: t.string() }, CartState,
  (ctx, { sku }) => ctx.withTx(tx => { doRemove(tx, sku); return cartState(tx); }));
export const shopSetPayment = spacetimedb.procedure({ label: t.string() }, CartState,
  (ctx, { label }) => ctx.withTx(tx => { doSetPayment(tx, label); return cartState(tx); }));

// Same operations as reducers, for SDK clients that subscribe instead of polling.
export const updateLocation = spacetimedb.reducer({ lat: t.f64(), lng: t.f64(), accuracyM: t.f64() }, (ctx, a) => doLocate(ctx, a.lat, a.lng, a.accuracyM));
export const bagLine = spacetimedb.reducer({ line: t.string() }, (ctx, { line }) => doBagLine(ctx, line));
export const setBag = spacetimedb.reducer({ name: t.string() }, (ctx, { name }) => doSetBag(ctx, name));
export const removeItem = spacetimedb.reducer({ sku: t.string() }, (ctx, { sku }) => doRemove(ctx, sku));
export const setPayment = spacetimedb.reducer({ label: t.string() }, (ctx, { label }) => doSetPayment(ctx, label));

// ───────────────────────── Views ─────────────────────────

const PricedLine = t.row('PricedLine', {
  sku: t.string().primaryKey(), name: t.string(), qty: t.u32(), priceCents: t.u32(), lineCents: t.u32(), sold: t.bool(), addedMicros: t.i64(),
});
export const myCart = spacetimedb.view({ name: 'my_cart', public: true }, t.array(PricedLine), ctx => {
  const s = ctx.db.shopper.identity.find(ctx.sender);
  const storeId = s ? s.storeId : '';
  const out: CartLineT[] = [];
  for (const it of ctx.db.cartItem.by_shopper_sku.filter(ctx.sender)) {
    let p = null as null | { name: string; priceCents: number };
    if (storeId) for (const x of ctx.db.product.by_store_sku.filter([storeId, it.sku])) { p = x; break; }
    out.push({ sku: it.sku, name: p ? p.name : '', qty: it.qty, priceCents: p ? p.priceCents : 0, lineCents: p ? p.priceCents * it.qty : 0, sold: !!p, addedMicros: it.addedAt.microsSinceUnixEpoch });
  }
  return out;
});
export const myShopper = spacetimedb.view({ name: 'my_shopper', public: true }, t.array(shopper.rowType), ctx => {
  const s = ctx.db.shopper.identity.find(ctx.sender);
  return s ? [s] : [];
});
export const myOrders = spacetimedb.view({ name: 'my_orders', public: true }, t.array(shopOrder.rowType), ctx => [...ctx.db.shopOrder.shopper.filter(ctx.sender)]);

// Stores the caller can manage, with their invite codes (admins: every store).
const StaffStore = t.row('StaffStore', { storeId: t.string().primaryKey(), role: t.string(), inviteCode: t.string() });
export const myStores = spacetimedb.view({ name: 'my_stores', public: true }, t.array(StaffStore), ctx => {
  const invite = (id: string) => { const i = ctx.db.storeInvite.storeId.find(id); return i ? i.code : ''; };
  if (ctx.db.operator.identity.find(ctx.sender)) {
    const owned = new Set([...ctx.db.storeOwner.identity.filter(ctx.sender)].map(o => o.storeId));
    const staffed = new Set([...ctx.db.storeStaff.identity.filter(ctx.sender)].map(o => o.storeId));
    return [...ctx.db.store.iter()].map(s => ({ storeId: s.id, role: owned.has(s.id) ? 'owner' : staffed.has(s.id) ? 'staff' : 'admin', inviteCode: invite(s.id) }));
  }
  const owned = new Set([...ctx.db.storeOwner.identity.filter(ctx.sender)].map(o => o.storeId));
  return [...ctx.db.storeStaff.identity.filter(ctx.sender)].map(s => ({ storeId: s.storeId, role: owned.has(s.storeId) ? 'owner' : 'staff', inviteCode: invite(s.storeId) }));
});

// ───────────────────────── Lifecycle, setup, maintenance ─────────────────────────

function ensureCleanup(ctx: Ctx) {
  if ([...ctx.db.cleanupTimer.iter()].length === 0)
    ctx.db.cleanupTimer.insert({ scheduledId: 0n, scheduledAt: ScheduleAt.interval(300_000_000n) });
}
function setup(ctx: Ctx) {
  if (!ctx.db.operator.identity.find(ctx.sender)) ctx.db.operator.insert({ identity: ctx.sender });
  if (!ctx.db.config.key.find('operator_code')) ctx.db.config.insert({ key: 'operator_code', value: randomCode(ctx, 8) });
  if (!ctx.db.store.id.find('bayside-grocer')) {
    createStore(ctx, 'bayside-grocer', 'Bayside Grocer', 'Market St, San Francisco', 37.7897, -122.3972, 150);
    addCatalog(ctx, 'bayside-grocer', {}, []);
  }
  if (!ctx.db.store.id.find('mission-fresh')) {
    createStore(ctx, 'mission-fresh', 'Mission Fresh Market', 'Valencia St, San Francisco', 37.7599, -122.4214, 150);
    addCatalog(ctx, 'mission-fresh', { '1001': 20, '1002': -30, '1005': 60, '1008': -50, '1010': -100, '1016': 30 }, ['1009', '1011']);
  }
  ensureCleanup(ctx);
}

export const init = spacetimedb.init(ctx => setup(ctx));
export const bootstrap = spacetimedb.reducer(ctx => {
  if ([...ctx.db.operator.iter()].length > 0) throw new SenderError('Already set up.');
  setup(ctx);
});

export const cleanup = spacetimedb.reducer({ onSchedule: cleanupTimer }, { timer: cleanupTimer.rowType }, (ctx, { timer: _timer }) => {
  const now = ctx.timestamp.microsSinceUnixEpoch;
  for (const e of [...ctx.db.bagEvent.iter()]) if (now - e.at.microsSinceUnixEpoch > FEED_TTL_MICROS) ctx.db.bagEvent.id.delete(e.id);
  for (const a of [...ctx.db.activity.iter()]) if (now - a.at.microsSinceUnixEpoch > 7n * 86_400_000_000n) ctx.db.activity.id.delete(a.id);
  setRecentDays(ctx);
  for (const s of [...ctx.db.shopper.iter()]) {
    if (now - s.updatedAt.microsSinceUnixEpoch < IDLE_SHOPPER_MICROS) continue;
    let hasCart = false;
    for (const _c of ctx.db.cartItem.by_shopper_sku.filter(s.identity)) { hasCart = true; break; }
    if (!hasCart) ctx.db.shopper.identity.delete(s.identity);
  }
});

// ───────────────────────── Store staff & admin ─────────────────────────

export const claimOperator = spacetimedb.reducer({ code: t.string() }, (ctx, { code }) => {
  const c = ctx.db.config.key.find('operator_code');
  if (!c || c.value !== code) throw new SenderError('Wrong admin code.');
  if (!ctx.db.operator.identity.find(ctx.sender)) ctx.db.operator.insert({ identity: ctx.sender });
  ensureCleanup(ctx);
});

export const joinStore = spacetimedb.reducer({ code: t.string() }, (ctx, { code }) => {
  const inv = ctx.db.storeInvite.code.find(code.trim().toUpperCase());
  if (!inv) throw new SenderError('Invalid store invite code.');
  if (addStaff(ctx, ctx.sender, inv.storeId)) logActivity(ctx, inv.storeId, 'joined with an invite code');
});

export const rotateInvite = spacetimedb.reducer({ storeId: t.string() }, (ctx, { storeId }) => {
  requireOwner(ctx, storeId);
  let code = randomCode(ctx, 8);
  while (ctx.db.storeInvite.code.find(code)) code = randomCode(ctx, 8);
  const cur = ctx.db.storeInvite.storeId.find(storeId);
  if (cur) ctx.db.storeInvite.storeId.update({ storeId, code }); else ctx.db.storeInvite.insert({ storeId, code });
  logActivity(ctx, storeId, 'made a new invite code');
});

// Any identity can open a store (up to 5; admins unlimited) and becomes its first staff member.
export const pinStore = spacetimedb.reducer({ name: t.string(), lat: t.f64(), lng: t.f64() }, (ctx, { name, lat, lng }) => {
  if (!isAdmin(ctx) && [...ctx.db.storeOwner.identity.filter(ctx.sender)].length >= MAX_STORES_PER_OWNER)
    throw new SenderError('Store limit reached for this account.');
  let id = 'store-' + randomCode(ctx, 6).toLowerCase();
  while (ctx.db.store.id.find(id)) id = 'store-' + randomCode(ctx, 6).toLowerCase();
  createStore(ctx, id, name || 'Demo Market', 'Pinned from the console', lat, lng, 150);
  addCatalog(ctx, id, {}, []);
  if (!isAdmin(ctx)) ctx.db.storeStaff.insert({ id: 0n, identity: ctx.sender, storeId: id });
  ctx.db.storeOwner.insert({ id: 0n, storeId: id, identity: ctx.sender });
  logActivity(ctx, id, 'opened the store');
});

export const upsertStore = spacetimedb.reducer(
  { id: t.string(), name: t.string(), address: t.string(), lat: t.f64(), lng: t.f64(), radiusM: t.u32() },
  (ctx, a) => {
    if (!a.id || !a.name) throw new SenderError('Store needs an id and a name.');
    const cur = ctx.db.store.id.find(a.id);
    if (!cur) { requireAdmin(ctx); createStore(ctx, a.id, a.name, a.address, a.lat, a.lng, a.radiusM); return; }
    requireStaff(ctx, a.id);
    if (!(a.lat >= -85 && a.lat <= 85 && a.lng >= -180 && a.lng <= 180)) throw new SenderError('Invalid location.');
    const next = { ...cur, name: a.name.slice(0, 80), address: a.address.slice(0, 120), lat: a.lat, lng: a.lng,
      radiusM: Math.min(MAX_RADIUS_M, Math.max(10, a.radiusM)), cell: cellOf(a.lat, a.lng) };
    ctx.db.store.id.update(next);
    const changes: string[] = [];
    if (next.name !== cur.name) changes.push('renamed the store to ' + next.name);
    if (next.address !== cur.address) changes.push('changed the address');
    if (next.radiusM !== cur.radiusM) changes.push('set the radius to ' + next.radiusM + ' m');
    if (next.lat !== cur.lat || next.lng !== cur.lng) changes.push('moved the store pin');
    if (changes.length) logActivity(ctx, a.id, changes.join(', '));
  }
);

export const deleteStore = spacetimedb.reducer({ id: t.string() }, (ctx, { id }) => { requireOwner(ctx, id); removeStore(ctx, id); });

export const upsertProduct = spacetimedb.reducer(
  { storeId: t.string(), sku: t.string(), name: t.string(), priceCents: t.u32(), unit: t.string() },
  (ctx, a) => {
    requireStaff(ctx, a.storeId);
    if (!ctx.db.store.id.find(a.storeId)) throw new SenderError('No such store.');
    const sku = a.sku.trim().replace(/[^A-Za-z0-9_\-]/g, '').slice(0, 40);
    if (!sku || !a.name.trim()) throw new SenderError('Product needs a SKU and a name.');
    const existing = findProduct(ctx, a.storeId, sku);
    const money = (c: number) => '$' + (c / 100).toFixed(2);
    if (existing) {
      ctx.db.product.id.update({ ...existing, name: a.name.slice(0, 80), priceCents: a.priceCents, unit: a.unit.slice(0, 30) });
      if (existing.priceCents !== a.priceCents) logActivity(ctx, a.storeId, 'changed ' + a.name.slice(0, 80) + ' from ' + money(existing.priceCents) + ' to ' + money(a.priceCents));
      if (existing.name !== a.name.slice(0, 80)) logActivity(ctx, a.storeId, 'renamed ' + existing.name + ' to ' + a.name.slice(0, 80));
    } else {
      ctx.db.product.insert({ id: 0n, storeId: a.storeId, sku, name: a.name.slice(0, 80), priceCents: a.priceCents, unit: a.unit.slice(0, 30) });
      logActivity(ctx, a.storeId, 'added ' + a.name.slice(0, 80) + ' at ' + money(a.priceCents));
    }
  }
);

export const deleteProduct = spacetimedb.reducer({ storeId: t.string(), sku: t.string() }, (ctx, { storeId, sku }) => {
  requireStaff(ctx, storeId);
  const p = findProduct(ctx, storeId, sku);
  if (p) { ctx.db.product.id.delete(p.id); logActivity(ctx, storeId, 'removed ' + p.name); }
});

// Clears shoppers, carts, orders and the bag feed for one store (staff) or everywhere (admin, storeId '').
export const resetDemo = spacetimedb.reducer({ storeId: t.string() }, (ctx, { storeId }) => {
  if (storeId) requireOwner(ctx, storeId); else requireAdmin(ctx);
  const match = (s: string) => !storeId || s === storeId;
  for (const r of [...(storeId ? ctx.db.cartItem.storeId.filter(storeId) : ctx.db.cartItem.iter())]) ctx.db.cartItem.id.delete(r.id);
  for (const r of [...(storeId ? ctx.db.bagEvent.storeId.filter(storeId) : ctx.db.bagEvent.iter())]) ctx.db.bagEvent.id.delete(r.id);
  for (const r of [...ctx.db.orderLine.iter()]) if (match(r.storeId)) ctx.db.orderLine.id.delete(r.id);
  for (const r of [...ctx.db.shopOrder.iter()]) if (match(r.storeId)) ctx.db.shopOrder.id.delete(r.id);
  for (const r of [...(storeId ? ctx.db.shopper.storeId.filter(storeId) : ctx.db.shopper.iter())]) ctx.db.shopper.identity.delete(r.identity);
  if (storeId) logActivity(ctx, storeId, 'reset the demo data');
});

// Load-test helpers (admin only): n stores on a grid around a point, ids prefixed "lt-".
export const seedLoadTest = spacetimedb.reducer({ start: t.u32(), count: t.u32(), lat: t.f64(), lng: t.f64() }, (ctx, a) => {
  requireAdmin(ctx);
  const side = 100;
  for (let k = a.start; k < a.start + Math.min(a.count, 500); k++) {
    const id = 'lt-' + k;
    if (ctx.db.store.id.find(id)) continue;
    const lat = a.lat + (Math.floor(k / side) - side / 2) * 0.006;
    const lng = a.lng + ((k % side) - side / 2) * 0.006;
    createStore(ctx, id, 'Load Test Market ' + k, 'Synthetic', lat, lng, 150);
    addCatalog(ctx, id, {}, []);
  }
});
export const clearLoadTest = spacetimedb.reducer({ count: t.u32() }, (ctx, { count }) => {
  requireAdmin(ctx);
  let n = 0;
  for (const s of [...ctx.db.store.iter()]) {
    if (!s.id.startsWith('lt-')) continue;
    for (const c of [...ctx.db.cartItem.storeId.filter(s.id)]) ctx.db.cartItem.id.delete(c.id);
    for (const e of [...ctx.db.bagEvent.storeId.filter(s.id)]) ctx.db.bagEvent.id.delete(e.id);
    for (const sh of [...ctx.db.shopper.storeId.filter(s.id)]) ctx.db.shopper.identity.delete(sh.identity);
    removeStore(ctx, s.id);
    if (++n >= count) break;
  }
});


// ───────────────────────── Accounts, teams, activity (v4, additive) ─────────────────────────
// Sign-in: connect with a SpacetimeAuth id_token. The account is filled automatically on connect and
// again by sign_in (the console calls it right after connecting). Phones can adopt the same flow later:
// connect with an id_token from the same SpacetimeAuth client and call sign_in once; the shop_* procedures
// are unchanged and simply run under the signed-in identity, so orders then carry a named shopper.
function setRecentDays(ctx: Ctx) {
  const ms0 = Number(ctx.timestamp.microsSinceUnixEpoch / 1000n);
  const v = [1, 0, -1, -2].map((k) => new Date(ms0 + k * 86400000).toISOString().slice(0, 10)).join(',');
  const c = ctx.db.config.key.find('recent_days');
  if (!c) ctx.db.config.insert({ key: 'recent_days', value: v }); else if (c.value !== v) ctx.db.config.key.update({ key: 'recent_days', value: v });
}
export const onConnect = spacetimedb.clientConnected((ctx) => {
  try { setRecentDays(ctx); } catch (_e) {}
  const jwt = (ctx.senderAuth as any)?.jwt;
  if (jwt && jwt.issuer === AUTH_ISSUER && !verifiedClaims(ctx)) throw new SenderError('This sign-in belongs to a different app.');
  try { doSignIn(ctx); } catch (_e) { /* a profile problem never blocks a connection */ }
});

export const signIn = spacetimedb.reducer((ctx) => {
  if (!doSignIn(ctx)) throw new SenderError('Not signed in with this app’s SpacetimeAuth login.');
});

export const setDisplayName = spacetimedb.reducer({ name: t.string() }, (ctx, { name }) => {
  const n = name.trim().slice(0, 60);
  if (!n) throw new SenderError('Name can’t be empty.');
  if (!ctx.db.account.identity.find(ctx.sender)) throw new SenderError('Sign in first.');
  const p = ctx.db.memberName.identity.find(ctx.sender);
  if (p) ctx.db.memberName.identity.update({ identity: ctx.sender, name: n }); else ctx.db.memberName.insert({ identity: ctx.sender, name: n });
  const a = ctx.db.account.identity.find(ctx.sender)!;
  ctx.db.account.identity.update({ ...a, name: n });
});

// Admin: which SpacetimeAuth client ids this database accepts (comma-separated).
export const setAuthClients = spacetimedb.reducer({ ids: t.string() }, (ctx, { ids }) => {
  requireAdmin(ctx);
  const v = ids.split(',').map((x) => x.trim()).filter(Boolean).join(',').slice(0, 500);
  const c = ctx.db.config.key.find('auth_client_ids');
  if (c) ctx.db.config.key.update({ key: 'auth_client_ids', value: v }); else ctx.db.config.insert({ key: 'auth_client_ids', value: v });
});

// Invite a teammate by email: they become staff the moment they sign in with that (verified) email.
export const inviteStaff = spacetimedb.reducer({ storeId: t.string(), email: t.string() }, (ctx, { storeId, email }) => {
  requireStaff(ctx, storeId);
  const e = email.trim().toLowerCase();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(e) || e.length > 120) throw new SenderError('That doesn’t look like an email address.');
  for (const a of ctx.db.account.email.filter(e)) {
    for (const s of ctx.db.storeStaff.identity.filter(a.identity)) if (s.storeId === storeId) throw new SenderError('They’re already on the team.');
  }
  for (const inv of ctx.db.staffEmailInvite.email.filter(e)) if (inv.storeId === storeId) return;
  let pending = 0;
  for (const _i of ctx.db.staffEmailInvite.storeId.filter(storeId)) pending++;
  if (pending >= 50) throw new SenderError('Too many pending invites for this store.');
  ctx.db.staffEmailInvite.insert({ id: 0n, storeId, email: e, invitedBy: actorName(ctx).slice(0, 60), at: ctx.timestamp });
  logActivity(ctx, storeId, 'invited ' + e);
});

export const revokeStaffInvite = spacetimedb.reducer({ id: t.u64() }, (ctx, { id }) => {
  const inv = ctx.db.staffEmailInvite.id.find(id);
  if (!inv) return;
  requireStaff(ctx, inv.storeId);
  ctx.db.staffEmailInvite.id.delete(id);
  logActivity(ctx, inv.storeId, 'cancelled the invite for ' + inv.email);
});

// Remove a teammate (or yourself: leave the store).
export const removeStaff = spacetimedb.reducer({ storeId: t.string(), member: t.identity() }, (ctx, { storeId, member }) => {
  requireStaff(ctx, storeId);
  const self = member.isEqual(ctx.sender);
  let memberOwns = false;
  for (const o of ctx.db.storeOwner.identity.filter(member)) if (o.storeId === storeId) memberOwns = true;
  if (!self) requireOwner(ctx, storeId);
  if (memberOwns && !isAdmin(ctx)) throw new SenderError(self ? 'Owners can’t leave their store. Delete it, or ask an admin.' : 'The owner can’t be removed.');
  if (self && !isAdmin(ctx)) {
    let owners = 0, staff = 0;
    for (const _o of ctx.db.storeOwner.storeId.filter(storeId)) owners++;
    for (const _s of ctx.db.storeStaff.storeId.filter(storeId)) staff++;
    if (owners === 0 && staff <= 1) throw new SenderError('You’re the only one on this team, so you can’t leave it.');
  }
  for (const s of [...ctx.db.storeStaff.identity.filter(member)]) {
    if (s.storeId !== storeId) continue;
    const p = ctx.db.memberName.identity.find(member);
    const who = member.isEqual(ctx.sender) ? 'left the team' : 'removed ' + (p ? p.name : 'Staff ·' + shortHex(member)) + ' from the team';
    logActivity(ctx, storeId, who);
    ctx.db.storeStaff.id.delete(s.id);
  }
});

// The caller's own account (one row, even when signed out).
const AccountSummary = t.row('AccountSummary', {
  key: t.string().primaryKey(), signedIn: t.bool(), name: t.string(), email: t.string(), picture: t.string(), admin: t.bool(), shortId: t.string(),
});
export const myAccount = spacetimedb.view({ name: 'my_account', public: true }, t.array(AccountSummary), (ctx) => {
  const a = ctx.db.account.identity.find(ctx.sender);
  const p = ctx.db.memberName.identity.find(ctx.sender);
  return [{ key: 'me', signedIn: !!a, name: p ? p.name : (a ? a.name : ''), email: a ? a.email : '', picture: a ? a.picture : '', admin: !!ctx.db.operator.identity.find(ctx.sender), shortId: shortHex(ctx.sender) }];
});

function managedStores(ctx: any): string[] {
  if (ctx.db.operator.identity.find(ctx.sender)) {
    const ids: string[] = [];
    for (const s of ctx.db.storeStaff.identity.filter(ctx.sender)) ids.push(s.storeId);
    for (const st of ctx.db.store.iter()) if (!st.id.startsWith('lt-') && !ids.includes(st.id)) ids.push(st.id);
    return ids;
  }
  return [...ctx.db.storeStaff.identity.filter(ctx.sender)].map((s: any) => s.storeId);
}

// Team of every store the caller manages: members (names only, no emails) and pending email invites.
const TeamRow = t.row('TeamRow', {
  key: t.string().primaryKey(), storeId: t.string(), kind: t.string(), name: t.string(), email: t.string(),
  member: t.identity(), inviteId: t.u64(), isYou: t.bool(),
});
export const myTeam = spacetimedb.view({ name: 'my_team', public: true }, t.array(TeamRow), (ctx) => {
  const out: any[] = [];
  for (const storeId of managedStores(ctx)) {
    for (const s of ctx.db.storeStaff.storeId.filter(storeId)) {
      const p = ctx.db.memberName.identity.find(s.identity);
      let owns = false;
      for (const o of ctx.db.storeOwner.identity.filter(s.identity)) if (o.storeId === storeId) owns = true;
      out.push({ key: storeId + ':m:' + s.identity.toHexString(), storeId, kind: owns ? 'owner' : 'member', name: p ? p.name : 'Staff ·' + shortHex(s.identity), email: '',
        member: s.identity, inviteId: 0n, isYou: s.identity.isEqual(ctx.sender) });
    }
    for (const inv of ctx.db.staffEmailInvite.storeId.filter(storeId)) {
      out.push({ key: storeId + ':i:' + inv.id, storeId, kind: 'invite', name: '', email: inv.email, member: ctx.sender, inviteId: inv.id, isYou: false });
    }
  }
  return out;
});

// Recent changes (last 7 days) in the stores the caller manages.
const ActivityRow = t.row('ActivityRow', { id: t.u64().primaryKey(), storeId: t.string(), actorName: t.string(), action: t.string(), isYou: t.bool(), at: t.timestamp() });
export const myStoreActivity = spacetimedb.view({ name: 'my_store_activity', public: true }, t.array(ActivityRow), (ctx) => {
  const out: any[] = [];
  for (const storeId of managedStores(ctx)) {
    const rows = [...ctx.db.activity.storeId.filter(storeId)].sort((a, b) => (a.id < b.id ? 1 : -1)).slice(0, 40);
    for (const a of rows) out.push({ id: a.id, storeId, actorName: a.actorName, action: a.action, isYou: a.actor.isEqual(ctx.sender), at: a.at });
  }
  return out;
});


// Names of signed-in shoppers seen in the stores the caller manages (current shoppers and recent orders).
// Guests and other stores never see names: shoppers stay "Guest ·abc123" for them.
const PersonRow = t.row('PersonRow', { key: t.string().primaryKey(), storeId: t.string(), who: t.identity(), name: t.string() });
export const myStorePeople = spacetimedb.view({ name: 'my_store_people', public: true }, t.array(PersonRow), (ctx) => {
  const out: any[] = [];
  for (const storeId of managedStores(ctx)) {
    const seen = new Set<string>();
    const add = (who: any) => {
      const h = who.toHexString();
      if (seen.has(h)) return;
      seen.add(h);
      const n = ctx.db.memberName.identity.find(who);
      if (n) out.push({ key: storeId + ':' + h, storeId, who, name: n.name });
    };
    for (const sh of ctx.db.shopper.storeId.filter(storeId)) add(sh.identity);
    const days = (ctx.db.config.key.find('recent_days') || { value: '' }).value.split(',').filter(Boolean);
    for (const day of days) for (const o of ctx.db.shopOrder.by_store_day.filter([storeId, day])) add(o.shopper);
  }
  return out;
});


// The server's clock, so consoles can measure "commit → screen" honestly even when the laptop clock is off.
export const serverTime = spacetimedb.procedure(t.i64(), (ctx) => ctx.timestamp.microsSinceUnixEpoch);

// Invites addressed to the caller's verified email. Nothing happens until they accept.
const InviteRow = t.row('InviteRow', { id: t.u64().primaryKey(), storeId: t.string(), storeName: t.string(), invitedBy: t.string() });
export const myInvites = spacetimedb.view({ name: 'my_invites', public: true }, t.array(InviteRow), (ctx) => {
  const a = ctx.db.account.identity.find(ctx.sender);
  if (!a || !a.emailVerified || !a.email) return [];
  const out: any[] = [];
  for (const inv of ctx.db.staffEmailInvite.email.filter(a.email)) {
    const st = ctx.db.store.id.find(inv.storeId);
    if (st) out.push({ id: inv.id, storeId: inv.storeId, storeName: st.name, invitedBy: inv.invitedBy });
  }
  return out;
});
function ownInvite(ctx: Ctx, id: bigint) {
  if (!authOk(ctx)) throw new SenderError('Sign in again.');
  const inv = ctx.db.staffEmailInvite.id.find(id);
  const a = ctx.db.account.identity.find(ctx.sender);
  if (!inv || !a || !a.emailVerified || a.email !== inv.email) throw new SenderError('That invite isn’t for this account.');
  return inv;
}
export const acceptInvite = spacetimedb.reducer({ id: t.u64() }, (ctx, { id }) => {
  const inv = ownInvite(ctx, id);
  if (!ctx.db.store.id.find(inv.storeId)) { ctx.db.staffEmailInvite.id.delete(id); return; }   // store is gone: just drop the invite
  if (addStaff(ctx, ctx.sender, inv.storeId)) logActivity(ctx, inv.storeId, 'joined the team (invited by ' + inv.invitedBy + ')');
  ctx.db.staffEmailInvite.id.delete(id);
});
export const declineInvite = spacetimedb.reducer({ id: t.u64() }, (ctx, { id }) => {
  const inv = ownInvite(ctx, id);
  ctx.db.staffEmailInvite.id.delete(id);
  logActivity(ctx, inv.storeId, 'declined the invite for ' + inv.email);
});

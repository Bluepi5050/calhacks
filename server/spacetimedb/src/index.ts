import { schema, table, t, SenderError, type InferSchema, type ReducerCtx } from 'spacetimedb/server';
import { ScheduleAt, TimeDuration } from 'spacetimedb';
import { sha256, googleJwt } from './push_crypto';

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


// ── Shopper accounts (v5, additive): email + password accounts for the phone apps, a wallet of saved cards
// and paired bags. A phone keeps its own SpacetimeDB identity; signing in links that identity to the account,
// so the same account works on several phones. All private; phones read them through account_* procedures.
// Cards: only brand, last four digits and expiry are stored. The full number never reaches the database.
const shopperAccount = table({ name: 'shopper_account' }, {
  id: t.u64().primaryKey().autoInc(),
  email: t.string().unique(),          // lower-cased
  name: t.string(),
  passHash: t.string(),                // hex PBKDF2-HMAC-SHA256
  salt: t.string(),
  iterations: t.u32(),
  failedLogins: t.u32(),
  lockedUntilMicros: t.i64(),
  createdAt: t.timestamp(),
});
const shopperLogin = table({ name: 'shopper_login' }, {     // which phone identities are signed in to which account
  identity: t.identity().primaryKey(),
  accountId: t.u64().index('btree'),
  signedInAt: t.timestamp(),
});
const paymentCard = table({ name: 'payment_card' }, {
  id: t.u64().primaryKey().autoInc(),
  accountId: t.u64().index('btree'),
  brand: t.string(),
  last4: t.string(),
  expMonth: t.u32(),
  expYear: t.u32(),
  nickname: t.string(),
  isDefault: t.bool(),
  addedAt: t.timestamp(),
});
const pairedBag = table({ name: 'paired_bag' }, {
  id: t.u64().primaryKey().autoInc(),
  accountId: t.u64().index('btree'),
  bagId: t.string().unique(),          // the bag's Bluetooth name, e.g. SmartBag-3F2A (one account per bag)
  name: t.string(),
  pairedAt: t.timestamp(),
});

// Push (v5.1): phones register a Firebase Cloud Messaging token; the module itself sends pushes over HTTPS from
// procedures (OAuth service-account JWT signed in push_crypto.ts). Private. prefs = enabled categories.
const pushDevice = table({ name: 'push_device' }, {
  identity: t.identity().primaryKey(),
  accountId: t.u64().index('btree'),      // 0 = not signed in
  platform: t.string(),                   // 'android' | 'ios'
  token: t.string(),
  prefs: t.string(),                      // comma list of: trips, security
  updatedAt: t.timestamp(),
});

// Ownership (v5.2, additive): who an order belongs to is fixed in the transaction that writes it, from the
// phone's login at that moment, and never recomputed from the current login. accountId 0 = a guest (signed-out
// phone). Orders written before v5.2 have no row here: their owner is unknown, so they stay with the store
// (shop_order) but never appear in anyone's trip history or lastReceipt.
const orderOwner = table({ name: 'order_owner' }, {
  orderId: t.u64().primaryKey(),
  accountId: t.u64().index('btree'),
  identity: t.identity().index('btree'),
  at: t.timestamp(),
});
// Who started the bag currently on a phone (accountId 0 = guest). Written when an empty bag gets its first item.
// A walk-out only charges the wallet of the login that started the bag; see walkOut and the switch guard.
const cartSession = table({ name: 'cart_session' }, {
  identity: t.identity().primaryKey(),
  accountId: t.u64(),
  startedAt: t.timestamp(),
});

// ── SmartCart hardware sessions (v6, additive; contract docs/SMARTCART-BLE.md sections 4-5) ──
// A session is one store visit of one phone: opened in the shop_locate transaction that puts the phone in a store,
// completed in the walk-out transaction (linked to the order), abandoned if the store membership ends otherwise.
// The Jetson tags its events with the session id; events of a session that is not the phone's active one never
// touch the cart.
const shopSession = table({ name: 'shop_session' }, {     // private
  id: t.u64().primaryKey().autoInc(),
  sid: t.string().unique(),               // 's' + base36(id): what the phone writes in START|sid|store
  identity: t.identity().index('btree'),
  storeId: t.string(),
  status: t.string().index('btree'),      // 'active' | 'completed' | 'abandoned'
  startedAt: t.timestamp(),
  endedMicros: t.i64(),                   // 0 while active
  orderId: t.u64().index('btree'),        // the walk-out order (0 = none: abandoned, or nothing to charge)
  lastSeq: t.u64(),
  eventCount: t.u32(),                    // hardware events processed (duplicates not counted)
  budgetCents: t.u32(),                   // the shopper's budget when the session ended
});
// Processed hardware events, '<sid>:<seq>' (idempotency). Removed when the session ends.
const hwEventSeen = table({ name: 'hw_event_seen' }, {  // private
  key: t.string().primaryKey(),
  sessionId: t.u64().index('btree'),
});
// Spending budget: 'a:<accountId>' when signed in, else 'i:<identity hex>'. Carries over to later trips.
const shopperBudget = table({ name: 'shopper_budget' }, {   // private
  key: t.string().primaryKey(),
  cents: t.u32(),
  updatedAt: t.timestamp(),
});
// Camera label -> store SKU (public: the console and the phones may read it). Staff edit it with set_product_label.
const productLabel = table(
  { name: 'product_label', public: true, indexes: [{ accessor: 'by_store_label', algorithm: 'btree', columns: ['storeId', 'label'] }] },
  { id: t.u64().primaryKey().autoInc(), storeId: t.string(), label: t.string(), sku: t.string() }
);

const spacetimedb = schema({
  store, product, shopper, cartItem, shopOrder, orderLine, bagEvent, operator, config, storeStaff, storeInvite, cleanupTimer,
  account, profile, staffEmailInvite, activity, storeOwner, memberName,
  shopperAccount, shopperLogin, paymentCard, pairedBag, pushDevice,
  orderOwner, cartSession,
  shopSession, hwEventSeen, shopperBudget, productLabel,
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
// The account a phone identity is signed in to right now (0 = guest).
function principalOf(ctx: Ctx, who: any): bigint {
  const l = ctx.db.shopperLogin.identity.find(who);
  return l && ctx.db.shopperAccount.id.find(l.accountId) ? l.accountId : 0n;
}
function hasCart(ctx: Ctx, who: any) { for (const _c of ctx.db.cartItem.by_shopper_sku.filter(who)) return true; return false; }
// Whose bag is on this phone: the login that put the first item in (falls back to the current login for bags
// started before v5.2).
function cartOwnerOf(ctx: Ctx, who: any): bigint {
  const cs = ctx.db.cartSession.identity.find(who);
  return cs ? cs.accountId : principalOf(ctx, who);
}
// Mid-trip = items in the bag while in a store. Changing account then is refused: the bag is only ever charged
// to the login that started it.
function midTrip(ctx: Ctx, who: any) {
  const row = ctx.db.shopper.identity.find(who);
  return !!row && !!row.storeId && hasCart(ctx, who);
}
// What a walk-out would charge: a signed-in phone always pays with its account's default card (the wallet is
// authoritative); a guest uses the label on its shopper row (demo).
function paymentFor(ctx: Ctx, row: { identity: any; payment: string }) {
  const p = principalOf(ctx, row.identity);
  return p ? defaultLabel(ctx, p) : row.payment;
}
// The label written on the (public) order: brand and last four only, never the card's nickname.
function chargeLabel(ctx: Ctx, row: { identity: any; payment: string }) {
  const p = principalOf(ctx, row.identity);
  if (!p) return row.payment;
  const d = cardsOf(ctx, p).find((c) => c.isDefault);
  return d ? d.brand + ' •••• ' + d.last4 : '';
}
// The phone `who` is about to change account (sign-in/up to another account, sign-out, account deletion), and
// is not mid-trip. Nothing of the previous login may stay on it: drop an out-of-store bag (never priced or
// charged), its session, the bag name and the payment label. The new login's payment is synced afterwards.
function resetForNewPrincipal(ctx: Ctx, who: any) {
  if (midTrip(ctx, who)) throw new SenderError('You’re in the middle of a trip. Walk out or take everything out of your bag first.');
  for (const c of [...ctx.db.cartItem.by_shopper_sku.filter(who)]) ctx.db.cartItem.id.delete(c.id);
  ctx.db.cartSession.identity.delete(who);
  const row = ctx.db.shopper.identity.find(who);
  if (row) ctx.db.shopper.identity.update({ ...row, bag: '', payment: '', outsideFixes: 0, updatedAt: ctx.timestamp });
}
const SWITCH_MID_TRIP = 'You’re in the middle of a trip. Walk out or take everything out of your bag before you switch accounts.';
const SIGNOUT_MID_TRIP = 'You’re in the middle of a trip. Walk out or take everything out of your bag before you sign out.';
const DELETE_MID_TRIP = 'One of your phones is in the middle of a trip. Finish it before you delete your account.';
// Orders a phone may see as its own: written on this identity AND owned by its current login.
function latestOwnOrder(ctx: Ctx) {
  const p = principalOf(ctx, ctx.sender);
  let best: bigint = 0n;
  for (const o of ctx.db.orderOwner.identity.filter(ctx.sender)) if (o.accountId === p && o.orderId > best) best = o.orderId;
  return best ? ctx.db.shopOrder.id.find(best) : null;
}
function maxOrderIdOn(ctx: Ctx, who: any): bigint {
  let best: bigint = 0n;
  for (const o of ctx.db.orderOwner.identity.filter(who)) if (o.orderId > best) best = o.orderId;
  return best;
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
  for (const sh of [...ctx.db.shopper.storeId.filter(id)]) {
    ctx.db.shopper.identity.update({ ...sh, storeId: '', storeName: '', storeAddress: '', outsideFixes: 0 });
    abandonSessionOf(ctx, sh.identity);
  }
  for (const i of [...ctx.db.staffEmailInvite.storeId.filter(id)]) ctx.db.staffEmailInvite.id.delete(i.id);
  for (const l of [...ctx.db.productLabel.by_store_label.filter(id)]) ctx.db.productLabel.id.delete(l.id);
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
    lat: Math.round(lat * 100) / 100, lng: Math.round(lng * 100) / 100,   // public table: coarse (~1 km) only
    updatedAt: ctx.timestamp,
  });
  if (row.storeId !== newStore) {
    for (const c of [...ctx.db.cartItem.by_shopper_sku.filter(ctx.sender)]) ctx.db.cartItem.id.update({ ...c, storeId: newStore });
  }
  syncSession(ctx);      // v6: entering a store opens a hardware session in this same transaction
}

// The shopper walked out of `st` with their bag: price it at this store, charge the saved payment
// method, record the order and empty the bag. Items the store does not sell are not charged.
function walkOut(ctx: Ctx, st: { id: string; name: string }) {
  const order = walkOutCharge(ctx, st);
  // v6: the store visit's hardware session completes in the walk-out transaction, linked to the order.
  const sess = activeSessionOf(ctx, ctx.sender);
  if (sess) endSession(ctx, sess, 'completed', order ? order.id : 0n);
  return order;
}
function walkOutCharge(ctx: Ctx, st: { id: string; name: string }) {
  const row = ctx.db.shopper.identity.find(ctx.sender)!;
  const items = [...ctx.db.cartItem.by_shopper_sku.filter(ctx.sender)];
  ctx.db.shopper.identity.update({ ...row, storeId: '', storeName: '', storeAddress: '', outsideFixes: 0 });
  // Ownership is decided here, inside the charging transaction. If the bag was started under a different login
  // than the current one (the switch guard should make this impossible), never charge the current login's
  // wallet: record the trip for whoever started the bag, as payment due.
  const principal = principalOf(ctx, ctx.sender);
  const owner = cartOwnerOf(ctx, ctx.sender);
  const payment = owner === principal ? chargeLabel(ctx, row) : '';
  ctx.db.cartSession.identity.delete(ctx.sender);
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
    status: payment ? 'paid' : 'payment_due', payment, trigger: 'walkout', paidAt: ctx.timestamp,
  });
  ctx.db.orderOwner.insert({ orderId: order.id, accountId: owner, identity: ctx.sender, at: ctx.timestamp });
  for (const l of lines) ctx.db.orderLine.insert({ id: 0n, orderId: order.id, storeId: st.id, day, ...l });
  logBag(ctx, st.id, 'WALKOUT ' + code, (payment ? 'charged ' : 'payment due ') + '$' + (total / 100).toFixed(2));
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
    if (!hasCart(ctx, ctx.sender)) {
      const sess = { identity: ctx.sender, accountId: principalOf(ctx, ctx.sender), startedAt: ctx.timestamp };
      if (ctx.db.cartSession.identity.find(ctx.sender)) ctx.db.cartSession.identity.update(sess); else ctx.db.cartSession.insert(sess);
    }
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
  const last = latestOwnOrder(ctx);    // never another login's receipt, even on the same phone
  return {
    storeId, storeName: s ? s.storeName : '', storeAddress: s ? s.storeAddress : '', distanceM: s ? s.distanceM : 0,
    nearestName: s ? s.nearestName : '', nearestM: s ? s.nearestM : 0, bag: s ? s.bag : '', payment: s ? paymentFor(ctx, s) : '',
    leaving: !!s && s.outsideFixes > 0,
    totalCents: sold.reduce((a, l) => a + l.lineCents, 0), itemCount: sold.reduce((a, l) => a + l.qty, 0), lines,
    lastReceipt: last ? receiptOf(ctx, last) : NO_RECEIPT,
  };
}

// ───────────────────────── Phone API: procedures (one round trip, returns the fresh priced cart) ─────────────────────────

export const shopState = spacetimedb.procedure(CartState, ctx => ctx.withTx(tx => cartState(tx)));
export const shopLocate = spacetimedb.procedure({ lat: t.f64(), lng: t.f64(), accuracyM: t.f64() }, CartState, (ctx, a) => {
  const r = ctx.withTx(tx => {
    const before = maxOrderIdOn(tx, tx.sender);
    doLocate(tx, a.lat, a.lng, a.accuracyM);
    const state = cartState(tx);
    const after = maxOrderIdOn(tx, tx.sender);
    const order = after > before ? tx.db.shopOrder.id.find(after) : null;
    return { state, pushes: order ? tripPushes(tx, order) : [] };
  });
  if (r.pushes.length) { try { sendPushes(ctx, r.pushes); } catch (_e) { /* a push problem never fails the walk-out */ } }
  return r.state;
});
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
// The caller's own orders: written on this identity and owned by its current login (not another account's
// trips from the same phone; orders from before v5.2 have no known owner and are left out).
export const myOrders = spacetimedb.view({ name: 'my_orders', public: true }, t.array(shopOrder.rowType), ctx => {
  const l = ctx.db.shopperLogin.identity.find(ctx.sender);
  const p = l ? l.accountId : 0n;
  const out: any[] = [];
  for (const o of ctx.db.orderOwner.identity.filter(ctx.sender)) {
    if (o.accountId !== p) continue;
    const r = ctx.db.shopOrder.id.find(o.orderId);
    if (r) out.push(r);
  }
  return out;
});

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
    seedLabels(ctx, 'bayside-grocer');
  }
  if (!ctx.db.store.id.find('mission-fresh')) {
    createStore(ctx, 'mission-fresh', 'Mission Fresh Market', 'Valencia St, San Francisco', 37.7599, -122.4214, 150);
    addCatalog(ctx, 'mission-fresh', { '1001': 20, '1002': -30, '1005': 60, '1008': -50, '1010': -100, '1016': 30 }, ['1009', '1011']);
    seedLabels(ctx, 'mission-fresh');
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
    if (!hasCart) { ctx.db.shopper.identity.delete(s.identity); ctx.db.cartSession.identity.delete(s.identity); ctx.db.shopperBudget.key.delete('i:' + s.identity.toHexString()); }
  }
  sweepSessions(ctx, now);
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
  seedLabels(ctx, id);
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
  for (const r of [...ctx.db.shopOrder.iter()]) if (match(r.storeId)) { ctx.db.orderOwner.orderId.delete(r.id); ctx.db.shopOrder.id.delete(r.id); }
  for (const r of [...(storeId ? ctx.db.shopper.storeId.filter(storeId) : ctx.db.shopper.iter())]) { abandonSessionOf(ctx, r.identity); ctx.db.shopper.identity.delete(r.identity); }
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
    seedLabels(ctx, id);
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
  // Names are per phone identity, but a shared phone can carry orders of several accounts. An identity is named only
  // if every recent order of it in this store is owned by its current login; otherwise it stays 'Guest ·id' rather
  // than showing one person's name on another person's (or an unknown owner's) trip.
  const principal = (who: any) => { const l = ctx.db.shopperLogin.identity.find(who); return l ? l.accountId : 0n; };
  for (const storeId of managedStores(ctx)) {
    const people = new Map<string, any>();
    const mixed = new Set<string>();
    for (const sh of ctx.db.shopper.storeId.filter(storeId)) people.set(sh.identity.toHexString(), sh.identity);
    const days = (ctx.db.config.key.find('recent_days') || { value: '' }).value.split(',').filter(Boolean);
    for (const day of days) for (const o of ctx.db.shopOrder.by_store_day.filter([storeId, day])) {
      const h = o.shopper.toHexString();
      people.set(h, o.shopper);
      const ow = ctx.db.orderOwner.orderId.find(o.id);
      if (!ow || ow.accountId !== principal(o.shopper)) mixed.add(h);
    }
    for (const [h, who] of people) {
      if (mixed.has(h)) continue;
      const n = ctx.db.memberName.identity.find(who);
      if (n) out.push({ key: storeId + ':' + h, storeId, who, name: n.name });
    }
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


// ───────────────────────── Shopper accounts, wallet, bags, trips (v5) ─────────────────────────

// SHA-256 / HMAC / PBKDF2 in plain TypeScript (modules have no crypto library).
const utf8 = (x: string) => new TextEncoder().encode(x);
const toHex = (b: Uint8Array) => Array.from(b).map((x) => x.toString(16).padStart(2, '0')).join('');
function hmacSha256(key: Uint8Array, msg: Uint8Array): Uint8Array {
  let k = key.length > 64 ? sha256(key) : key;
  const ipad = new Uint8Array(64 + msg.length), opad = new Uint8Array(64 + 32);
  for (let i = 0; i < 64; i++) { const kb = i < k.length ? k[i] : 0; ipad[i] = kb ^ 0x36; opad[i] = kb ^ 0x5c; }
  ipad.set(msg, 64);
  opad.set(sha256(ipad), 64);
  return sha256(opad);
}
function pbkdf2(password: string, salt: string, iterations: number): string {
  const p = utf8(password), sb = utf8(salt);
  const first = new Uint8Array(sb.length + 4); first.set(sb); first[sb.length + 3] = 1;
  let u = hmacSha256(p, first); const out = u.slice();
  for (let i = 1; i < iterations; i++) { u = hmacSha256(p, u); for (let j = 0; j < 32; j++) out[j] ^= u[j]; }
  return toHex(out);
}
function sameHex(a: string, b: string) { if (a.length !== b.length) return false; let d = 0; for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i); return d === 0; }

const PBKDF2_ITER = 3000;
const MAX_FAILED = 8;
const LOCK_MICROS = 900_000_000n;     // 15 minutes
const MAX_CARDS = 10, MAX_BAGS = 5;
const BRANDS = ['Visa', 'Mastercard', 'Amex', 'Discover', 'Card'];
const EMAIL_RE = /^[^\s@]{1,64}@[^\s@]{1,190}\.[^\s@]{2,}$/;
const BAG_ID_RE = /^[A-Za-z0-9_-]{1,40}$/;

function loginOf(ctx: Ctx) { return ctx.db.shopperLogin.identity.find(ctx.sender); }
function requireLogin(ctx: Ctx) {
  const l = loginOf(ctx);
  const acct = l ? ctx.db.shopperAccount.id.find(l.accountId) : null;
  if (!l || !acct) throw new SenderError('Log in first.');
  return acct;
}
function cardLabel(c: { brand: string; last4: string; nickname: string }) {
  return (c.nickname ? c.nickname + ' ' : '') + c.brand + ' •••• ' + c.last4;
}
function cardsOf(ctx: Ctx, accountId: bigint) {
  return [...ctx.db.paymentCard.accountId.filter(accountId)].sort((a, b) => (a.id < b.id ? -1 : 1));
}
function defaultLabel(ctx: Ctx, accountId: bigint) {
  const d = cardsOf(ctx, accountId).find((c) => c.isDefault);
  return d ? cardLabel(d) : '';
}
// shopper is a public table, so a signed-in phone's row only says whether a card is on file (the console shows
// 'no card on file' from it); the walk-out and CartState read the wallet itself (paymentFor / chargeLabel).
const CARD_ON_FILE = 'Card on file';
function syncPayment(ctx: Ctx, accountId: bigint) {
  const label = defaultLabel(ctx, accountId) ? CARD_ON_FILE : '';
  for (const l of [...ctx.db.shopperLogin.accountId.filter(accountId)]) {
    const row = ctx.db.shopper.identity.find(l.identity);
    if (row && row.payment !== label) ctx.db.shopper.identity.update({ ...row, payment: label, updatedAt: ctx.timestamp });
    else if (!row && l.identity.toHexString() === ctx.sender.toHexString()) { const r = shopperRow(ctx); ctx.db.shopper.identity.update({ ...r, payment: label }); }
  }
}
function setShopperName(ctx: Ctx, name: string) {
  if (ctx.db.account.identity.find(ctx.sender)) return;      // console (SpacetimeAuth) accounts keep their own name
  const n = ctx.db.memberName.identity.find(ctx.sender);
  if (n) ctx.db.memberName.identity.update({ identity: ctx.sender, name }); else ctx.db.memberName.insert({ identity: ctx.sender, name });
}
function linkLogin(ctx: Ctx, acct: { id: bigint; name: string }) {
  if (principalOf(ctx, ctx.sender) !== acct.id) {
    if (midTrip(ctx, ctx.sender)) throw new SenderError(SWITCH_MID_TRIP);
    resetForNewPrincipal(ctx, ctx.sender);     // nothing of the previous login (or guest) stays on this phone
  }
  const cur = loginOf(ctx);
  if (cur) ctx.db.shopperLogin.identity.update({ identity: ctx.sender, accountId: acct.id, signedInAt: ctx.timestamp });
  else ctx.db.shopperLogin.insert({ identity: ctx.sender, accountId: acct.id, signedInAt: ctx.timestamp });
  setShopperName(ctx, acct.name);
  syncPayment(ctx, acct.id);
  const pd = ctx.db.pushDevice.identity.find(ctx.sender);
  if (pd && pd.accountId !== acct.id) ctx.db.pushDevice.identity.update({ ...pd, accountId: acct.id });
}
function unlinkLogin(ctx: Ctx) {
  if (principalOf(ctx, ctx.sender) !== 0n) {
    if (midTrip(ctx, ctx.sender)) throw new SenderError(SIGNOUT_MID_TRIP);
    resetForNewPrincipal(ctx, ctx.sender);     // the next person on this phone starts with nothing of this account
  }
  ctx.db.shopperLogin.identity.delete(ctx.sender);
  ctx.db.pushDevice.identity.delete(ctx.sender);   // a signed-out phone gets no account pushes
  const row = ctx.db.shopper.identity.find(ctx.sender);
  if (row) ctx.db.shopper.identity.update({ ...row, payment: '', bag: '', updatedAt: ctx.timestamp });
  if (!ctx.db.account.identity.find(ctx.sender)) ctx.db.memberName.identity.delete(ctx.sender);
}
function nowYm(ctx: Ctx) { const d = new Date(Number(ctx.timestamp.microsSinceUnixEpoch / 1000n)); return { y: d.getUTCFullYear(), m: d.getUTCMonth() + 1 }; }

const CardInfo = t.object('CardInfo', {
  id: t.u64(), brand: t.string(), last4: t.string(), expMonth: t.u32(), expYear: t.u32(), nickname: t.string(), isDefault: t.bool(),
});
const BagInfo = t.object('BagInfo', { id: t.u64(), bagId: t.string(), name: t.string(), pairedMicros: t.i64() });
const AccountState = t.object('AccountState', {
  signedIn: t.bool(), name: t.string(), email: t.string(), cards: t.array(CardInfo), bags: t.array(BagInfo), payment: t.string(),
});
function acctState(ctx: Ctx) {
  const l = loginOf(ctx);
  const acct = l ? ctx.db.shopperAccount.id.find(l.accountId) : null;
  if (!acct) return { signedIn: false, name: '', email: '', cards: [], bags: [], payment: '' };
  const cards = cardsOf(ctx, acct.id).map((c) => ({ id: c.id, brand: c.brand, last4: c.last4, expMonth: c.expMonth, expYear: c.expYear, nickname: c.nickname, isDefault: c.isDefault }));
  const bags = [...ctx.db.pairedBag.accountId.filter(acct.id)].sort((a, b) => (a.id < b.id ? -1 : 1))
    .map((b) => ({ id: b.id, bagId: b.bagId, name: b.name, pairedMicros: b.pairedAt.microsSinceUnixEpoch }));
  return { signedIn: true, name: acct.name, email: acct.email, cards, bags, payment: defaultLabel(ctx, acct.id) };
}
function cleanName(name: string) {
  const n = name.trim().replace(/\s+/g, ' ').slice(0, 60);
  if (!n) throw new SenderError('Enter your name.');
  return n;
}
function cleanEmail(email: string) {
  const e = email.trim().toLowerCase();
  if (!EMAIL_RE.test(e) || e.length > 254) throw new SenderError('Enter a valid email address.');
  return e;
}
function checkPassword(pw: string) {
  if (pw.length < 8) throw new SenderError('Use at least 8 characters for your password.');
  if (pw.length > 128) throw new SenderError('That password is too long.');
}
function verifyPassword(ctx: Ctx, acct: any, password: string) {
  const now = ctx.timestamp.microsSinceUnixEpoch;
  if (acct.lockedUntilMicros > now) {
    const mins = Number((acct.lockedUntilMicros - now) / 60_000_000n) + 1;
    throw new SenderError('Too many tries. Try again in ' + mins + ' minute' + (mins === 1 ? '' : 's') + '.');
  }
  return sameHex(pbkdf2(password, acct.salt, acct.iterations), acct.passHash);
}

function doSignUp(ctx: Ctx, name: string, email: string, password: string) {
  const n = cleanName(name), e = cleanEmail(email);
  checkPassword(password);
  if (ctx.db.shopperAccount.email.find(e)) throw new SenderError('That email already has an account. Log in instead.');
  if (midTrip(ctx, ctx.sender)) throw new SenderError(SWITCH_MID_TRIP);
  const salt = randomCode(ctx, 16);
  const acct = ctx.db.shopperAccount.insert({
    id: 0n, email: e, name: n, passHash: pbkdf2(password, salt, PBKDF2_ITER), salt, iterations: PBKDF2_ITER,
    failedLogins: 0, lockedUntilMicros: 0n, createdAt: ctx.timestamp,
  });
  linkLogin(ctx, acct);
}
// Failed attempts are counted in their own transaction so the error does not roll the count back.
function signInAttempt(ctx: Ctx, email: string, password: string): string {
  const e = email.trim().toLowerCase();
  const acct = ctx.db.shopperAccount.email.find(e);
  if (!acct) { pbkdf2(password, 'x', PBKDF2_ITER); return 'Email or password is wrong.'; }     // same cost either way
  const ok = verifyPassword(ctx, acct, password);
  if (!ok) {
    const failed = acct.failedLogins + 1;
    const lock = failed >= MAX_FAILED;
    ctx.db.shopperAccount.id.update({ ...acct, failedLogins: lock ? 0 : failed, lockedUntilMicros: lock ? ctx.timestamp.microsSinceUnixEpoch + LOCK_MICROS : acct.lockedUntilMicros });
    return lock ? 'Too many tries. Try again in 15 minutes.' : 'Email or password is wrong.';
  }
  if (acct.failedLogins) ctx.db.shopperAccount.id.update({ ...acct, failedLogins: 0 });
  if (principalOf(ctx, ctx.sender) !== acct.id && midTrip(ctx, ctx.sender)) return SWITCH_MID_TRIP;
  linkLogin(ctx, ctx.db.shopperAccount.id.find(acct.id)!);
  return '';
}
function doDeleteAccount(ctx: Ctx, password: string): string {
  const acct = requireLogin(ctx);
  if (!verifyPassword(ctx, acct, password)) return 'That password is wrong.';
  const logins = [...ctx.db.shopperLogin.accountId.filter(acct.id)];
  if (logins.some((l) => midTrip(ctx, l.identity))) return DELETE_MID_TRIP;
  for (const l of logins) {
    resetForNewPrincipal(ctx, l.identity);
    ctx.db.shopperLogin.identity.delete(l.identity);
    ctx.db.pushDevice.identity.delete(l.identity);
    const row = ctx.db.shopper.identity.find(l.identity);
    if (row) ctx.db.shopper.identity.update({ ...row, payment: '', bag: '' });
    if (!ctx.db.account.identity.find(l.identity)) ctx.db.memberName.identity.delete(l.identity);
  }
  for (const c of [...ctx.db.paymentCard.accountId.filter(acct.id)]) ctx.db.paymentCard.id.delete(c.id);
  for (const b of [...ctx.db.pairedBag.accountId.filter(acct.id)]) ctx.db.pairedBag.id.delete(b.id);
  // Receipts stay with the store (shop_order); only the private link from them to this person is removed.
  for (const o of [...ctx.db.orderOwner.accountId.filter(acct.id)]) ctx.db.orderOwner.orderId.delete(o.orderId);
  ctx.db.shopperBudget.key.delete('a:' + acct.id);
  ctx.db.shopperAccount.id.delete(acct.id);
  return '';
}
function doCardAdd(ctx: Ctx, a: { brand: string; last4: string; expMonth: number; expYear: number; nickname: string; makeDefault: boolean }) {
  const acct = requireLogin(ctx);
  const brand = BRANDS.find((b) => b.toLowerCase() === a.brand.trim().toLowerCase());
  if (!brand) throw new SenderError('That card type isn’t supported.');
  if (!/^\d{4}$/.test(a.last4)) throw new SenderError('Check the card number.');
  if (a.expMonth < 1 || a.expMonth > 12) throw new SenderError('Check the expiry date.');
  const year = a.expYear < 100 ? 2000 + a.expYear : a.expYear;
  const now = nowYm(ctx);
  if (year < now.y || (year === now.y && a.expMonth < now.m)) throw new SenderError('This card has expired.');
  if (year > now.y + 25) throw new SenderError('Check the expiry date.');
  const cards = cardsOf(ctx, acct.id);
  if (cards.length >= MAX_CARDS) throw new SenderError('You can save up to ' + MAX_CARDS + ' cards.');
  if (cards.some((c) => c.brand === brand && c.last4 === a.last4 && c.expMonth === a.expMonth && c.expYear === year)) throw new SenderError('This card is already in your wallet.');
  const makeDefault = a.makeDefault || cards.length === 0;
  if (makeDefault) for (const c of cards) if (c.isDefault) ctx.db.paymentCard.id.update({ ...c, isDefault: false });
  ctx.db.paymentCard.insert({ id: 0n, accountId: acct.id, brand, last4: a.last4, expMonth: a.expMonth, expYear: year,
    nickname: a.nickname.trim().replace(/\s+/g, ' ').slice(0, 24), isDefault: makeDefault, addedAt: ctx.timestamp });
  syncPayment(ctx, acct.id);
}
function ownCard(ctx: Ctx, id: bigint) {
  const acct = requireLogin(ctx);
  const c = ctx.db.paymentCard.id.find(id);
  if (!c || c.accountId !== acct.id) throw new SenderError('That card isn’t in your wallet.');
  return { acct, c };
}
function doCardRemove(ctx: Ctx, id: bigint) {
  const { acct, c } = ownCard(ctx, id);
  ctx.db.paymentCard.id.delete(c.id);
  if (c.isDefault) { const next = cardsOf(ctx, acct.id)[0]; if (next) ctx.db.paymentCard.id.update({ ...next, isDefault: true }); }
  syncPayment(ctx, acct.id);
}
function doCardDefault(ctx: Ctx, id: bigint) {
  const { acct, c } = ownCard(ctx, id);
  for (const o of cardsOf(ctx, acct.id)) if (o.isDefault !== (o.id === c.id)) ctx.db.paymentCard.id.update({ ...o, isDefault: o.id === c.id });
  syncPayment(ctx, acct.id);
}
function doBagPair(ctx: Ctx, bagId: string, name: string) {
  const acct = requireLogin(ctx);
  const id = bagId.trim();
  if (!BAG_ID_RE.test(id)) throw new SenderError('That doesn’t look like a Bag It bag.');
  const n = name.trim().replace(/\s+/g, ' ').slice(0, 30) || 'My bag';
  const existing = ctx.db.pairedBag.bagId.find(id);
  if (existing && existing.accountId === acct.id) { ctx.db.pairedBag.id.update({ ...existing, name: n }); return; }
  const mine = [...ctx.db.pairedBag.accountId.filter(acct.id)];
  if (mine.length >= MAX_BAGS) throw new SenderError('You can pair up to ' + MAX_BAGS + ' bags.');
  if (existing) ctx.db.pairedBag.id.delete(existing.id);      // pairing needs the bag in hand: the newest owner wins
  ctx.db.pairedBag.insert({ id: 0n, accountId: acct.id, bagId: id, name: n, pairedAt: ctx.timestamp });
}
function ownBag(ctx: Ctx, id: bigint) {
  const acct = requireLogin(ctx);
  const b = ctx.db.pairedBag.id.find(id);
  if (!b || b.accountId !== acct.id) throw new SenderError('That bag isn’t paired to your account.');
  return b;
}
// Trip history by durable ownership (order_owner), never by which phones are linked right now:
// signed in = every order owned by the account, from any phone, including phones since signed out or switched;
// guest = this phone's own guest orders. Orders without a known owner (before v5.2) are never listed.
function tripsOf(ctx: Ctx, limit: number) {
  const p = principalOf(ctx, ctx.sender);
  const ids: bigint[] = [];
  if (p) for (const o of ctx.db.orderOwner.accountId.filter(p)) ids.push(o.orderId);
  else for (const o of ctx.db.orderOwner.identity.filter(ctx.sender)) if (o.accountId === 0n) ids.push(o.orderId);
  ids.sort((a, b) => (a > b ? -1 : a < b ? 1 : 0));
  const out: any[] = [];
  const max = Math.max(1, Math.min(limit || 50, 200));
  for (const id of ids) {
    if (out.length >= max) break;
    const o = ctx.db.shopOrder.id.find(id);
    if (o) out.push(receiptOf(ctx, o));
  }
  return out;
}

// Sign-in failures must persist (attempt counting), so they are returned, not thrown, inside the transaction.
function orThrow(msg: string) { if (msg) throw new SenderError(msg); }

export const accountState = spacetimedb.procedure(AccountState, (ctx) => ctx.withTx((tx) => acctState(tx)));
export const accountSignUp = spacetimedb.procedure({ name: t.string(), email: t.string(), password: t.string() }, AccountState,
  (ctx, a) => ctx.withTx((tx) => { doSignUp(tx, a.name, a.email, a.password); return acctState(tx); }));
export const accountSignIn = spacetimedb.procedure({ email: t.string(), password: t.string() }, AccountState, (ctx, a) => {
  const msg = ctx.withTx((tx) => signInAttempt(tx, a.email, a.password));
  orThrow(msg);
  const r = ctx.withTx((tx) => {
    const l = loginOf(tx);
    const pushes = l ? pushesForAccount(tx, l.accountId, 'security', 'New sign-in to Bag It', 'Your account was just used to log in on another phone. If that wasn’t you, sign out of it from Account and change nothing else until you’ve checked your wallet.', { type: 'sign_in' }) : [];
    return { state: acctState(tx), pushes };
  });
  if (r.pushes.length) { try { sendPushes(ctx, r.pushes); } catch (_e) {} }
  return r.state;
});
export const accountSignOut = spacetimedb.procedure(AccountState, (ctx) => ctx.withTx((tx) => { unlinkLogin(tx); return acctState(tx); }));
export const accountSetName = spacetimedb.procedure({ name: t.string() }, AccountState, (ctx, { name }) => ctx.withTx((tx) => {
  const acct = requireLogin(tx); const n = cleanName(name);
  tx.db.shopperAccount.id.update({ ...acct, name: n });
  for (const l of [...tx.db.shopperLogin.accountId.filter(acct.id)]) {
    if (tx.db.account.identity.find(l.identity)) continue;
    const m = tx.db.memberName.identity.find(l.identity);
    if (m) tx.db.memberName.identity.update({ identity: l.identity, name: n }); else tx.db.memberName.insert({ identity: l.identity, name: n });
  }
  return acctState(tx);
}));
export const accountDelete = spacetimedb.procedure({ password: t.string() }, AccountState, (ctx, { password }) => {
  orThrow(ctx.withTx((tx) => doDeleteAccount(tx, password)));
  return ctx.withTx((tx) => acctState(tx));
});
export const cardAdd = spacetimedb.procedure(
  { brand: t.string(), last4: t.string(), expMonth: t.u32(), expYear: t.u32(), nickname: t.string(), makeDefault: t.bool() }, AccountState,
  (ctx, a) => {
    const r = ctx.withTx((tx) => {
      doCardAdd(tx, a);
      const l = loginOf(tx)!;
      return { state: acctState(tx), pushes: pushesForAccount(tx, l.accountId, 'security', 'Card added', 'A ' + a.brand + ' ending ' + a.last4 + ' was added to your Bag It wallet on another phone.', { type: 'card_added' }) };
    });
    if (r.pushes.length) { try { sendPushes(ctx, r.pushes); } catch (_e) {} }
    return r.state;
  });
export const cardRemove = spacetimedb.procedure({ id: t.u64() }, AccountState, (ctx, { id }) => ctx.withTx((tx) => { doCardRemove(tx, id); return acctState(tx); }));
export const cardSetDefault = spacetimedb.procedure({ id: t.u64() }, AccountState, (ctx, { id }) => ctx.withTx((tx) => { doCardDefault(tx, id); return acctState(tx); }));
export const bagPair = spacetimedb.procedure({ bagId: t.string(), name: t.string() }, AccountState,
  (ctx, a) => ctx.withTx((tx) => { doBagPair(tx, a.bagId, a.name); return acctState(tx); }));
export const bagRename = spacetimedb.procedure({ id: t.u64(), name: t.string() }, AccountState, (ctx, a) => ctx.withTx((tx) => {
  const b = ownBag(tx, a.id); tx.db.pairedBag.id.update({ ...b, name: a.name.trim().replace(/\s+/g, ' ').slice(0, 30) || 'My bag' }); return acctState(tx);
}));
export const bagUnpair = spacetimedb.procedure({ id: t.u64() }, AccountState, (ctx, { id }) => ctx.withTx((tx) => {
  const b = ownBag(tx, id); tx.db.pairedBag.id.delete(b.id); return acctState(tx);
}));
export const shopTrips = spacetimedb.procedure({ limit: t.u32() }, t.array(Receipt), (ctx, { limit }) => ctx.withTx((tx) => tripsOf(tx, limit)));


// ───────────────────────── Push notifications (v5.1) ─────────────────────────
// Phones show their own local notifications for what they see happen (walk-out on this phone, arrivals, bag
// alerts). The module pushes what a phone cannot see: a trip charged on another phone of the same account, a new
// sign-in, a card added or removed elsewhere. Sending is a no-op until set_push_config stores an FCM service account.

type Push = { token: string; identity: string; title: string; body: string; kind: string; data: Record<string, string> };
const PUSH_CATS = ['trips', 'security'];
function cfg(ctx: Ctx, key: string) { const c = ctx.db.config.key.find(key); return c ? c.value : ''; }
function setCfg(ctx: Ctx, key: string, value: string) {
  if (ctx.db.config.key.find(key)) ctx.db.config.key.update({ key, value }); else ctx.db.config.insert({ key, value });
}
function pushesForAccount(ctx: Ctx, accountId: bigint, cat: string, title: string, body: string, data: Record<string, string>, includeSender = false): Push[] {
  if (!cfg(ctx, 'fcm_project_id')) return [];
  const out: Push[] = [];
  for (const d of ctx.db.pushDevice.accountId.filter(accountId)) {
    if (!includeSender && d.identity.toHexString() === ctx.sender.toHexString()) continue;
    if (!d.prefs.split(',').includes(cat)) continue;
    out.push({ token: d.token, identity: d.identity.toHexString(), title, body, kind: cat, data });
  }
  return out;
}
const money = (c: number) => '$' + (c / 100).toFixed(2);
function tripPushes(ctx: Ctx, order: any): Push[] {
  const ow = ctx.db.orderOwner.orderId.find(order.id);     // the order's owner, not whoever is signed in now
  if (!ow || ow.accountId === 0n) return [];
  const due = order.status !== 'paid';
  return pushesForAccount(ctx, ow.accountId, 'trips',
    due ? 'Payment due at ' + order.storeName : 'Paid ' + money(order.totalCents) + ' at ' + order.storeName,
    due ? money(order.totalCents) + ' for ' + order.itemCount + (order.itemCount === 1 ? ' item' : ' items') + '. Show exit code ' + order.code + ' to store staff.'
        : order.itemCount + (order.itemCount === 1 ? ' item' : ' items') + ' with ' + order.payment + '. Exit code ' + order.code + '.',
    { type: due ? 'payment_due' : 'trip', code: order.code });
}

// HTTPS outside any transaction. Returns a short status for push_test.
function sendPushes(ctx: any, pushes: Push[]): string {
  if (!pushes.length) return 'nothing to send';
  const conf = ctx.withTx((tx: Ctx) => ({
    project: cfg(tx, 'fcm_project_id'), email: cfg(tx, 'fcm_client_email'), key: cfg(tx, 'fcm_private_key'),
    tokenUri: cfg(tx, 'fcm_token_uri') || 'https://oauth2.googleapis.com/token',
    access: cfg(tx, 'fcm_access_token'), exp: Number(cfg(tx, 'fcm_access_exp') || '0'),
    now: Number(tx.timestamp.microsSinceUnixEpoch / 1_000_000n),
  }));
  if (!conf.project || !conf.key) return 'push not configured';
  let access = conf.access;
  if (!access || conf.exp < conf.now + 120) {
    try {
      const jwt = googleJwt(conf.email, conf.key, conf.tokenUri, conf.now);
      const r = ctx.http.fetch(conf.tokenUri, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' },
        body: 'grant_type=' + encodeURIComponent('urn:ietf:params:oauth:grant-type:jwt-bearer') + '&assertion=' + jwt });
      const j = r.json();
      if (!r.ok || !j.access_token) return 'oauth failed: ' + r.status + ' ' + String(j.error_description || j.error || '').slice(0, 120);
      access = j.access_token;
      const exp = String(conf.now + Number(j.expires_in || 3600));
      ctx.withTx((tx: Ctx) => { setCfg(tx, 'fcm_access_token', access); setCfg(tx, 'fcm_access_exp', exp); });
    } catch (e: any) { return 'oauth error: ' + String(e?.message || e).slice(0, 120); }
  }
  const dead: string[] = [];
  let sent = 0, last = '';
  for (const p of pushes) {
    const msg = { message: {
      token: p.token,
      notification: { title: p.title, body: p.body },
      data: { kind: p.kind, ...p.data },
      android: { priority: 'HIGH', notification: { channel_id: p.kind === 'security' ? 'account' : 'receipts' } },
      apns: { payload: { aps: { sound: 'default', 'thread-id': p.kind } } },
    } };
    try {
      const r = ctx.http.fetch('https://fcm.googleapis.com/v1/projects/' + conf.project + '/messages:send', {
        method: 'POST', headers: { authorization: 'Bearer ' + access, 'content-type': 'application/json' }, body: JSON.stringify(msg) });
      if (r.ok) sent++;
      else {
        const txt = r.text(); last = r.status + ' ' + txt.slice(0, 160);
        if (r.status === 404 || txt.includes('UNREGISTERED') || (r.status === 400 && txt.includes('registration token'))) dead.push(p.identity);
        if (r.status === 401) ctx.withTx((tx: Ctx) => setCfg(tx, 'fcm_access_exp', '0'));
      }
    } catch (e: any) { last = String(e?.message || e).slice(0, 120); }
  }
  if (dead.length) ctx.withTx((tx: Ctx) => {
    for (const d of [...tx.db.pushDevice.iter()]) if (dead.includes(d.identity.toHexString())) tx.db.pushDevice.identity.delete(d.identity);
  });
  return 'sent ' + sent + '/' + pushes.length + (last ? ' (last error ' + last + ')' : '');
}

const PushState = t.object('PushState', { serverPush: t.bool(), registered: t.bool(), prefs: t.string() });
function pushState(ctx: Ctx) {
  const d = ctx.db.pushDevice.identity.find(ctx.sender);
  return { serverPush: !!cfg(ctx, 'fcm_project_id'), registered: !!d, prefs: d ? d.prefs : '' };
}
function cleanPrefs(p: string) { return p.split(',').map((x) => x.trim()).filter((x) => PUSH_CATS.includes(x)).join(','); }

// A phone registers (or refreshes) its FCM token. Only signed-in phones get account pushes.
export const pushRegister = spacetimedb.procedure({ platform: t.string(), token: t.string(), prefs: t.string() }, PushState, (ctx, a) => ctx.withTx((tx) => {
  const acct = requireLogin(tx);
  const platform = a.platform === 'ios' ? 'ios' : 'android';
  const token = a.token.trim();
  if (token.length < 20 || token.length > 4096) throw new SenderError('That notification token isn’t valid.');
  for (const d of [...tx.db.pushDevice.iter()]) if (d.token === token && d.identity.toHexString() !== tx.sender.toHexString()) tx.db.pushDevice.identity.delete(d.identity);
  const row = { identity: tx.sender, accountId: acct.id, platform, token, prefs: cleanPrefs(a.prefs), updatedAt: tx.timestamp };
  if (tx.db.pushDevice.identity.find(tx.sender)) tx.db.pushDevice.identity.update(row); else tx.db.pushDevice.insert(row);
  return pushState(tx);
}));
export const pushSetPrefs = spacetimedb.procedure({ prefs: t.string() }, PushState, (ctx, { prefs }) => ctx.withTx((tx) => {
  const d = tx.db.pushDevice.identity.find(tx.sender);
  if (d) tx.db.pushDevice.identity.update({ ...d, prefs: cleanPrefs(prefs), updatedAt: tx.timestamp });
  return pushState(tx);
}));
export const pushUnregister = spacetimedb.procedure(PushState, (ctx) => ctx.withTx((tx) => { tx.db.pushDevice.identity.delete(tx.sender); return pushState(tx); }));
export const pushStatus = spacetimedb.procedure(PushState, (ctx) => ctx.withTx((tx) => pushState(tx)));
// Sends a test notification to this phone. Returns what happened (e.g. 'sent 1/1', 'push not configured').
export const pushTest = spacetimedb.procedure(t.string(), (ctx) => {
  const pushes = ctx.withTx((tx) => {
    const d = tx.db.pushDevice.identity.find(tx.sender);
    if (!d) return [] as Push[];
    return [{ token: d.token, identity: d.identity.toHexString(), title: 'Notifications are on', body: 'Bag It will tell you when a trip is charged, even with the app closed.', kind: 'trips', data: { type: 'test' } }];
  });
  if (!pushes.length) return 'not registered';
  return sendPushes(ctx, pushes);
});
// Admin: store the FCM service account (JSON from Firebase console › Project settings › Service accounts). '' turns push off.
export const setPushConfig = spacetimedb.reducer({ serviceAccountJson: t.string() }, (ctx, { serviceAccountJson }) => {
  requireAdmin(ctx);
  if (!serviceAccountJson.trim()) { for (const k of ['fcm_project_id', 'fcm_client_email', 'fcm_private_key', 'fcm_token_uri', 'fcm_access_token', 'fcm_access_exp']) setCfg(ctx, k, ''); return; }
  let j: any;
  try { j = JSON.parse(serviceAccountJson); } catch { throw new SenderError('That isn’t valid JSON.'); }
  if (!j.project_id || !j.client_email || !j.private_key) throw new SenderError('Expected a Firebase service account JSON (project_id, client_email, private_key).');
  setCfg(ctx, 'fcm_project_id', String(j.project_id)); setCfg(ctx, 'fcm_client_email', String(j.client_email));
  setCfg(ctx, 'fcm_private_key', String(j.private_key)); setCfg(ctx, 'fcm_token_uri', String(j.token_uri || 'https://oauth2.googleapis.com/token'));
  setCfg(ctx, 'fcm_access_token', ''); setCfg(ctx, 'fcm_access_exp', '0');
});


// Nearby stores for background geofencing on phones (iOS region monitoring allows 20 regions; Android geofences 100).
const NearbyStore = t.object('NearbyStore', { id: t.string(), name: t.string(), address: t.string(), lat: t.f64(), lng: t.f64(), radiusM: t.u32(), distanceM: t.u32() });
export const shopNearbyStores = spacetimedb.procedure({ lat: t.f64(), lng: t.f64(), limit: t.u32() }, t.array(NearbyStore), (ctx, a) => ctx.withTx((tx) => {
  const ci = Math.floor(a.lat * CELL), cj = Math.floor(a.lng * CELL);
  const out: any[] = [];
  for (let di = -5; di <= 5; di++) for (let dj = -5; dj <= 5; dj++)
    for (const s of tx.db.store.cell.filter(cellKey(ci + di, cj + dj)))
      out.push({ id: s.id, name: s.name, address: s.address, lat: s.lat, lng: s.lng, radiusM: s.radiusM, distanceM: Math.round(haversine(a.lat, a.lng, s.lat, s.lng)) });
  out.sort((x, y) => x.distanceM - y.distanceM);
  return out.slice(0, Math.max(1, Math.min(a.limit || 20, 50)));
}));


// ───────────────────────── SmartCart hardware sessions, budget, recommendations, summary (v6) ─────────────────────────
// Contract: docs/SMARTCART-BLE.md sections 4 and 5. Additive: no existing table, procedure or return type changed.

const SESSION_TTL_MICROS = 30n * 86_400_000_000n;   // finished sessions kept 30 days (trip summaries use them)
const MAX_SEQ = 999_999n;
const MAX_BUDGET_CENTS = 10_000_000;                // $100,000

function activeSessionOf(ctx: Ctx, who: any) {
  for (const s of ctx.db.shopSession.identity.filter(who)) if (s.status === 'active') return s;
  return null;
}
type SessionRow = NonNullable<ReturnType<typeof activeSessionOf>>;
function budgetKey(ctx: Ctx, who: any) {
  const p = principalOf(ctx, who);
  return p ? 'a:' + p : 'i:' + who.toHexString();
}
function budgetOf(ctx: Ctx, who: any): number {
  const b = ctx.db.shopperBudget.key.find(budgetKey(ctx, who));
  return b ? b.cents : 0;
}
function openSession(ctx: Ctx, who: any, storeId: string) {
  const row = ctx.db.shopSession.insert({ id: 0n, sid: 'tmp:' + who.toHexString(), identity: who, storeId, status: 'active',
    startedAt: ctx.timestamp, endedMicros: 0n, orderId: 0n, lastSeq: 0n, eventCount: 0, budgetCents: 0 });
  const sid = 's' + row.id.toString(36);
  ctx.db.shopSession.id.update({ ...row, sid });
  return { ...row, sid };
}
function endSession(ctx: Ctx, s: SessionRow, status: 'completed' | 'abandoned', orderId: bigint) {
  for (const e of [...ctx.db.hwEventSeen.sessionId.filter(s.id)]) ctx.db.hwEventSeen.key.delete(e.key);
  ctx.db.shopSession.id.update({ ...s, status, orderId, endedMicros: ctx.timestamp.microsSinceUnixEpoch, budgetCents: budgetOf(ctx, s.identity) });
}
function abandonSessionOf(ctx: Ctx, who: any) {
  const s = activeSessionOf(ctx, who);
  if (s) endSession(ctx, s, 'abandoned', 0n);
}
// The caller's session must match its store membership: one active session exactly while it is in a store.
function syncSession(ctx: Ctx) {
  const row = ctx.db.shopper.identity.find(ctx.sender);
  const storeId = row ? row.storeId : '';
  let act = activeSessionOf(ctx, ctx.sender);
  if (act && act.storeId !== storeId) { endSession(ctx, act, 'abandoned', 0n); act = null; }
  if (!act && storeId) openSession(ctx, ctx.sender, storeId);
}
// Scheduled: end sessions whose phone is no longer in that store (shopper dropped, store removed), prune old ones.
function sweepSessions(ctx: Ctx, now: bigint) {
  for (const s of [...ctx.db.shopSession.status.filter('active')]) {
    const sh = ctx.db.shopper.identity.find(s.identity);
    if (!sh || sh.storeId !== s.storeId) endSession(ctx, s, 'abandoned', 0n);
  }
  for (const s of [...ctx.db.shopSession.iter()]) {
    if (s.status !== 'active' && now - s.endedMicros > SESSION_TTL_MICROS) {
      for (const e of [...ctx.db.hwEventSeen.sessionId.filter(s.id)]) ctx.db.hwEventSeen.key.delete(e.key);
      ctx.db.shopSession.id.delete(s.id);
    }
  }
}

// ── Labels ──
function normLabel(raw: string) { return raw.trim().toLowerCase().replace(/\s+/g, ' ').slice(0, 20); }
function labelSku(ctx: Ctx, storeId: string, label: string): string {
  for (const l of ctx.db.productLabel.by_store_label.filter([storeId, label])) return l.sku;
  return '';
}
function putLabel(ctx: Ctx, storeId: string, label: string, sku: string) {
  let cur = null as null | { id: bigint; storeId: string; label: string; sku: string };
  for (const l of ctx.db.productLabel.by_store_label.filter([storeId, label])) { cur = l; break; }
  if (!sku) { if (cur) ctx.db.productLabel.id.delete(cur.id); return; }
  if (cur) { if (cur.sku !== sku) ctx.db.productLabel.id.update({ ...cur, sku }); }
  else ctx.db.productLabel.insert({ id: 0n, storeId, label, sku });
}
// Default camera labels = the COCO classes in the teammate's data/detectable_products.json: apple, banana, orange,
// bottle, carrot, broccoli. Products the catalog lacks (Oranges, Carrots, Broccoli) are added, priced like the store's
// other produce (offset by how this store prices bananas against the standard catalog). Never overwrites a mapping
// staff already set, never adds a product twice.
const EXTRA_PRODUCE: Array<[string, string, RegExp, number, string]> = [
  ['1017', 'Oranges', /orange/, 99, 'each'],
  ['1018', 'Carrots', /carrot/, 149, '1 lb bag'],
  ['1019', 'Broccoli', /broccoli/, 249, 'bunch'],
];
function seedLabels(ctx: Ctx, storeId: string): number {
  const products = [...ctx.db.product.by_store_sku.filter(storeId)];
  if (!products.length) return 0;
  const byName = (re: RegExp) => products.find((p) => re.test(p.name.toLowerCase()));
  const bySku = (sku: string) => products.find((p) => p.sku === sku);
  const banana = bySku('1001') || byName(/banana/);
  const offset = banana ? banana.priceCents - 129 : 0;
  const extra: Record<string, any> = {};
  for (const [pref, name, re, base, unit] of EXTRA_PRODUCE) {
    let p: any = byName(re);
    if (!p) {
      let n = Number(pref);
      while (bySku(String(n))) n++;
      p = ctx.db.product.insert({ id: 0n, storeId, sku: String(n), name, priceCents: Math.max(49, base + offset), unit });
      products.push(p);
    }
    extra[name] = p;
  }
  const want: Array<[string, any]> = [
    ['apple', bySku('1003') || byName(/apple/)],
    ['banana', banana],
    ['orange', extra['Oranges']],
    ['bottle', bySku('1010') || byName(/water|bottle|soda|juice/)],
    ['carrot', extra['Carrots']],
    ['broccoli', extra['Broccoli']],
  ];
  let n = 0;
  for (const [label, p] of want) {
    if (!p || labelSku(ctx, storeId, label)) continue;
    putLabel(ctx, storeId, label, p.sku); n++;
  }
  return n;
}

export const setProductLabel = spacetimedb.reducer({ storeId: t.string(), label: t.string(), sku: t.string() }, (ctx, a) => {
  requireStaff(ctx, a.storeId);
  if (!ctx.db.store.id.find(a.storeId)) throw new SenderError('No such store.');
  const label = normLabel(a.label);
  if (!label || label.includes('|')) throw new SenderError('Enter the camera label, for example apple.');
  const sku = a.sku.trim();
  if (sku && !findProduct(ctx, a.storeId, sku)) throw new SenderError('This store has no product with SKU ' + sku.slice(0, 40) + '.');
  const before = labelSku(ctx, a.storeId, label);
  putLabel(ctx, a.storeId, label, sku);
  if (before !== sku) {
    const p = sku ? findProduct(ctx, a.storeId, sku) : null;
    logActivity(ctx, a.storeId, sku ? 'mapped camera label ' + label + ' to ' + (p ? p.name : sku) : 'removed camera label ' + label);
  }
});
// Seeds the default labels for one store (staff) or every store (admin, storeId ''). Safe to run again.
export const seedProductLabels = spacetimedb.reducer({ storeId: t.string() }, (ctx, { storeId }) => {
  if (storeId) { requireStaff(ctx, storeId); seedLabels(ctx, storeId); return; }
  requireAdmin(ctx);
  for (const st of [...ctx.db.store.iter()]) seedLabels(ctx, st.id);
});

// ── Cart helpers shared by the hardware path and the manual corrections ──
function startCartIfEmpty(ctx: Ctx) {
  if (hasCart(ctx, ctx.sender)) return;
  const sess = { identity: ctx.sender, accountId: principalOf(ctx, ctx.sender), startedAt: ctx.timestamp };
  if (ctx.db.cartSession.identity.find(ctx.sender)) ctx.db.cartSession.identity.update(sess); else ctx.db.cartSession.insert(sess);
}
function cartAdd(ctx: Ctx, storeId: string, sku: string, qty: number) {
  startCartIfEmpty(ctx);
  const cur = findCartLine(ctx, sku);
  if (cur) ctx.db.cartItem.id.update({ ...cur, qty: Math.min(999, cur.qty + qty), storeId });
  else ctx.db.cartItem.insert({ id: 0n, shopper: ctx.sender, storeId, sku, qty: Math.min(999, qty), tags: '', addedAt: ctx.timestamp });
}
function cartTake(ctx: Ctx, sku: string, qty: number): boolean {
  const cur = findCartLine(ctx, sku);
  if (!cur || cur.qty === 0) return false;
  if (cur.qty <= qty) ctx.db.cartItem.id.delete(cur.id);
  else {
    const tags = cur.tags ? cur.tags.split(',') : [];
    tags.splice(0, qty);
    ctx.db.cartItem.id.update({ ...cur, qty: cur.qty - qty, tags: tags.join(',') });
  }
  return true;
}
// Cart key for a camera label the store doesn't map: the label itself (an unpriced "not sold here" line), never
// colliding with a real SKU of this store.
function unknownKey(ctx: Ctx, storeId: string, label: string) {
  const k = label.replace(/\s+/g, '-');
  return findProduct(ctx, storeId, k) ? 'x-' + k : k;
}

// ── Return types (BSATN field order = the contract's order) ──
const SessionState = t.object('SessionState', {
  sessionId: t.string(), storeId: t.string(), storeName: t.string(), active: t.bool(), lastSeq: t.u64(),
  budgetCents: t.u32(), spentCents: t.u32(), remainingCents: t.i64(), cart: CartState,
});
const HwEventResult = t.object('HwEventResult', { status: t.string(), message: t.string(), state: SessionState });
const Recommendation = t.object('Recommendation', { sku: t.string(), name: t.string(), priceCents: t.u32(), reason: t.string() });
const Recommendations = t.object('Recommendations', { source: t.string(), note: t.string(), items: t.array(Recommendation) });
const TripSummary = t.object('TripSummary', {
  receipt: Receipt, sessionId: t.string(), startedMicros: t.i64(), endedMicros: t.i64(), eventCount: t.u32(),
  budgetCents: t.u32(), underBudgetCents: t.i64(), text: t.string(),
});

// sessionId '' (active false) when the phone is not on a store visit. remainingCents = budget - priced total
// (negative = over budget); 0 when no budget is set.
function sessionState(ctx: Ctx) {
  const cart = cartState(ctx);
  const act = activeSessionOf(ctx, ctx.sender);
  const budget = budgetOf(ctx, ctx.sender);
  const st = act ? ctx.db.store.id.find(act.storeId) : null;
  return {
    sessionId: act ? act.sid : '', storeId: act ? act.storeId : cart.storeId, storeName: st ? st.name : cart.storeName,
    active: !!act, lastSeq: act ? act.lastSeq : 0n, budgetCents: budget, spentCents: cart.totalCents,
    remainingCents: budget ? BigInt(budget) - BigInt(cart.totalCents) : 0n, cart,
  };
}

const cap = (x: string) => (x ? x[0].toUpperCase() + x.slice(1) : x);
function doHwEvent(ctx: Ctx, sid: string, seq: bigint, actionIn: string, labelIn: string, confIn: number) {
  const action = actionIn.trim().toUpperCase();
  const label = normLabel(labelIn);
  const conf = Math.min(100, Math.floor(confIn));
  const line = (action.slice(0, 3) + ' ' + label + ' ' + conf + '% #' + seq).slice(0, 80);
  const res = (status: string, message: string) => ({ status, message, state: sessionState(ctx) });
  // Malformed events get a terminal status (the phone acks it) instead of an error that would block its queue.
  if ((action !== 'A' && action !== 'R') || seq < 1n || seq > MAX_SEQ || !label || label.includes('|')) {
    const row = ctx.db.shopper.identity.find(ctx.sender);
    logBag(ctx, row ? row.storeId : '', line, 'unreadable, ignored');
    return res('rejected', 'Bag It couldn’t read that event.');
  }
  const sess = sid ? ctx.db.shopSession.sid.find(sid.trim().slice(0, 12)) : null;
  const mine = !!sess && sess.identity.toHexString() === ctx.sender.toHexString();
  if (!sess || !mine) {
    const row = ctx.db.shopper.identity.find(ctx.sender);
    logBag(ctx, row ? row.storeId : '', line, 'no trip, ignored');
    return res('no_session', 'You’re not on a trip right now.');
  }
  if (sess.status !== 'active') {
    logBag(ctx, sess.storeId, line, 'trip ended, ignored');
    return res('stale_session', 'That trip has ended.');
  }
  const key = sess.sid + ':' + seq;
  if (ctx.db.hwEventSeen.key.find(key)) { logBag(ctx, sess.storeId, line, 'repeat, ignored'); return res('duplicate', 'Already counted.'); }
  ctx.db.hwEventSeen.insert({ key, sessionId: sess.id });
  ctx.db.shopSession.id.update({ ...sess, lastSeq: seq > sess.lastSeq ? seq : sess.lastSeq, eventCount: sess.eventCount + 1 });
  const mapped = labelSku(ctx, sess.storeId, label);
  const p = mapped ? findProduct(ctx, sess.storeId, mapped) : null;
  const sku = p ? p.sku : unknownKey(ctx, sess.storeId, label);
  if (action === 'A') {
    cartAdd(ctx, sess.storeId, sku, 1);
    if (p) { logBag(ctx, sess.storeId, line, '+ ' + p.name); return res('accepted', 'Added ' + p.name + ', ' + money(p.priceCents) + '.'); }
    logBag(ctx, sess.storeId, line, 'not sold here');
    return res('unknown_product', cap(label) + ' isn’t sold here, so it won’t be charged.');
  }
  if (!cartTake(ctx, sku, 1)) {
    logBag(ctx, sess.storeId, line, 'remove, but not in bag');
    return res('invalid_remove', 'There’s no ' + (p ? p.name : label) + ' in your bag to take out.');
  }
  logBag(ctx, sess.storeId, line, '− ' + (p ? p.name : label));
  if (!p) return res('unknown_product', 'Took out ' + label + '. It isn’t sold here.');
  return res('accepted', 'Took out ' + p.name + '.');
}

function doSetQty(ctx: Ctx, skuIn: string, qtyIn: number) {
  const sku = skuIn.trim().slice(0, 40);
  const qty = Math.min(999, Math.floor(qtyIn));
  const row = shopperRow(ctx);
  const cur = findCartLine(ctx, sku);
  const p = row.storeId ? findProduct(ctx, row.storeId, sku) : null;
  if (!cur && !p) throw new SenderError(row.storeId ? 'That item isn’t sold here.' : 'Walk into a Bag It store first.');
  if (qty === 0) { if (cur) ctx.db.cartItem.id.delete(cur.id); }
  else if (cur) ctx.db.cartItem.id.update({ ...cur, qty, storeId: row.storeId });
  else cartAdd(ctx, row.storeId, sku, qty);
  logBag(ctx, row.storeId, 'SET ' + sku + ' ' + qty, p ? 'fixed: ' + p.name + ' × ' + qty : 'fixed: ' + sku + ' × ' + qty);
}
function doRelabel(ctx: Ctx, fromIn: string, toIn: string) {
  const from = fromIn.trim().slice(0, 40), to = toIn.trim().slice(0, 40);
  const row = shopperRow(ctx);
  if (!row.storeId) throw new SenderError('Walk into a Bag It store first.');
  const p = findProduct(ctx, row.storeId, to);
  if (!p) throw new SenderError('That item isn’t sold here.');
  if (from === to) return;
  if (!cartTake(ctx, from, 1)) throw new SenderError('That item isn’t in your bag.');
  cartAdd(ctx, row.storeId, to, 1);
  const fp = findProduct(ctx, row.storeId, from);
  logBag(ctx, row.storeId, 'RELABEL ' + from + ' -> ' + to, 'fixed: ' + (fp ? fp.name : from) + ' → ' + p.name);
}

export const shopSessionState = spacetimedb.procedure(SessionState, (ctx) => ctx.withTx((tx) => sessionState(tx)));
export const shopHwEvent = spacetimedb.procedure(
  { sessionId: t.string(), seq: t.u64(), action: t.string(), label: t.string(), confidence: t.u32() }, HwEventResult,
  (ctx, a) => ctx.withTx((tx) => doHwEvent(tx, a.sessionId, a.seq, a.action, a.label, a.confidence)));
export const shopSetBudget = spacetimedb.procedure({ cents: t.u32() }, SessionState, (ctx, { cents }) => ctx.withTx((tx) => {
  const key = budgetKey(tx, tx.sender);
  if (cents === 0) tx.db.shopperBudget.key.delete(key);
  else {
    const row = { key, cents: Math.min(cents, MAX_BUDGET_CENTS), updatedAt: tx.timestamp };
    if (tx.db.shopperBudget.key.find(key)) tx.db.shopperBudget.key.update(row); else tx.db.shopperBudget.insert(row);
  }
  return sessionState(tx);
}));
export const shopSetQty = spacetimedb.procedure({ sku: t.string(), qty: t.u32() }, SessionState,
  (ctx, a) => ctx.withTx((tx) => { doSetQty(tx, a.sku, a.qty); return sessionState(tx); }));
export const shopRelabel = spacetimedb.procedure({ fromSku: t.string(), toSku: t.string() }, SessionState,
  (ctx, a) => ctx.withTx((tx) => { doRelabel(tx, a.fromSku, a.toSku); return sessionState(tx); }));

// ── Recommendations: an OpenAI-compatible chat completions endpoint when configured, otherwise rules ──
// Admin config (private config table): ai_url (base URL or full .../chat/completions), ai_key, ai_model.
export const setAiConfig = spacetimedb.reducer({ url: t.string(), apiKey: t.string(), model: t.string() }, (ctx, a) => {
  requireAdmin(ctx);
  const url = a.url.trim();
  if (!url) { for (const k of ['ai_url', 'ai_key', 'ai_model']) setCfg(ctx, k, ''); return; }
  if (!/^https:\/\/[^\s]+$/.test(url) || url.length > 300) throw new SenderError('Use an https URL.');
  if (!a.model.trim()) throw new SenderError('Name the model, for example gpt-4o-mini.');
  setCfg(ctx, 'ai_url', url); setCfg(ctx, 'ai_key', a.apiKey.trim().slice(0, 500)); setCfg(ctx, 'ai_model', a.model.trim().slice(0, 100));
});

// Goes-with pairs over the standard catalog SKUs (stores using other SKUs simply get cheapest-first suggestions).
const GOES_WITH: Record<string, string[]> = {
  '1001': ['1006', '1016'], '1002': ['1012', '1008'], '1003': ['1015', '1011'], '1004': ['1007', '1015'], '1005': ['1009', '1008'],
  '1006': ['1001', '1003'], '1007': ['1008', '1004'], '1008': ['1015', '1007'], '1009': ['1016', '1005'], '1010': ['1012', '1017'],
  '1011': ['1009', '1003'], '1012': ['1002', '1010'], '1013': ['1014', '1015'], '1014': ['1013', '1015'], '1015': ['1008', '1003'],
  '1016': ['1009', '1001'], '1017': ['1006', '1010'], '1018': ['1004', '1019'], '1019': ['1013', '1015'],
};
type Cat = { sku: string; name: string; priceCents: number; unit: string };
type RecCtx = { storeId: string; storeName: string; catalog: Cat[]; cart: Array<{ sku: string; name: string; qty: number }>;
  budget: number; remaining: number; ai: { url: string; key: string; model: string } | null };
function recContext(ctx: Ctx): RecCtx {
  const s = sessionState(ctx);
  const storeId = s.cart.storeId;
  const catalog = storeId ? [...ctx.db.product.by_store_sku.filter(storeId)].map((p) => ({ sku: p.sku, name: p.name, priceCents: p.priceCents, unit: p.unit })) : [];
  const url = cfg(ctx, 'ai_url'), model = cfg(ctx, 'ai_model');
  return {
    storeId, storeName: s.cart.storeName, catalog,
    cart: s.cart.lines.slice().reverse().map((l) => ({ sku: l.sku, name: l.name || l.sku, qty: l.qty })),   // newest first
    budget: s.budgetCents, remaining: Number(s.remainingCents), ai: url && model ? { url, key: cfg(ctx, 'ai_key'), model } : null,
  };
}
function fits(r: RecCtx, price: number) { return !r.budget || price <= r.remaining; }
function ruleRecs(r: RecCtx) {
  if (!r.storeId) return { source: 'rules', note: 'Walk into a Bag It store to get suggestions.', items: [] as any[] };
  if (r.budget && r.remaining <= 0) return { source: 'rules', note: 'You’ve reached your budget, so there are no suggestions.', items: [] as any[] };
  const inCart = new Set(r.cart.map((c) => c.sku));
  const bySku = new Map(r.catalog.map((p) => [p.sku, p]));
  const out: Array<{ sku: string; name: string; priceCents: number; reason: string }> = [];
  const take = (p: Cat | undefined, reason: string) => {
    if (!p || out.length >= 3 || inCart.has(p.sku) || out.some((o) => o.sku === p.sku) || !fits(r, p.priceCents)) return;
    out.push({ sku: p.sku, name: p.name, priceCents: p.priceCents, reason });
  };
  for (const c of r.cart) for (const g of GOES_WITH[c.sku] || []) if (bySku.has(c.sku)) take(bySku.get(g), 'Goes with your ' + c.name.toLowerCase());
  const rest = r.catalog.slice().sort((a, b) => a.priceCents - b.priceCents || (a.sku < b.sku ? -1 : 1));
  for (const p of rest) take(p, r.budget ? 'Fits your remaining ' + money(r.remaining) : 'One of the lower prices at ' + r.storeName);
  const note = out.length ? (r.budget ? 'Ideas that fit the ' + money(r.remaining) + ' left in your budget.' : 'Ideas from ' + r.storeName + '.')
    : r.budget ? 'Nothing else here fits your budget.' : 'Nothing else to suggest here right now.';
  return { source: 'rules', note, items: out };
}
function aiUrl(u: string) { return /\/chat\/completions\/?$/.test(u) ? u : u.replace(/\/+$/, '') + '/chat/completions'; }
function aiChat(ctx: any, ai: { url: string; key: string; model: string }, system: string, user: string): any {
  const headers: Record<string, string> = { 'content-type': 'application/json' };
  if (ai.key) headers.authorization = 'Bearer ' + ai.key;
  const r = ctx.http.fetch(aiUrl(ai.url), {
    method: 'POST', headers, timeout: TimeDuration.fromMillis(8000),
    body: JSON.stringify({ model: ai.model, temperature: 0.3, max_tokens: 400, response_format: { type: 'json_object' },
      messages: [{ role: 'system', content: system }, { role: 'user', content: user }] }),
  });
  if (!r.ok) throw new Error('ai http ' + r.status);
  const j = r.json();
  const txt = String(j?.choices?.[0]?.message?.content ?? '').trim().replace(/^```(?:json)?\s*/i, '').replace(/```\s*$/, '');
  return JSON.parse(txt);
}
function aiRecs(ctx: any, r: RecCtx) {
  const system = 'You suggest up to 3 grocery items a shopper might add. Reply with strict JSON only: '
    + '{"note": string, "items": [{"sku": string, "reason": string}]}. Use only SKUs from the catalog, never items already '
    + 'in the cart, and when a budget is given never an item priced above remainingCents. Reasons and note: plain, '
    + 'sentence case, under 60 characters, no emoji, no exclamation marks.';
  const user = JSON.stringify({ store: r.storeName, catalog: r.catalog, cart: r.cart, budgetCents: r.budget || null,
    remainingCents: r.budget ? r.remaining : null });
  const j = aiChat(ctx, r.ai!, system, user);
  const inCart = new Set(r.cart.map((c) => c.sku));
  const bySku = new Map(r.catalog.map((p) => [p.sku, p]));
  const items: Array<{ sku: string; name: string; priceCents: number; reason: string }> = [];
  for (const it of Array.isArray(j?.items) ? j.items : []) {
    const p = bySku.get(String(it?.sku ?? ''));
    if (!p || inCart.has(p.sku) || items.some((x) => x.sku === p.sku) || !fits(r, p.priceCents)) continue;
    const reason = String(it?.reason ?? '').replace(/[\r\n]+/g, ' ').trim().slice(0, 80) || 'Suggested for your trip';
    items.push({ sku: p.sku, name: p.name, priceCents: p.priceCents, reason });
    if (items.length >= 3) break;
  }
  if (!items.length) throw new Error('ai gave no valid items');
  const note = String(j?.note ?? '').replace(/[\r\n]+/g, ' ').trim().slice(0, 120) || 'Suggestions for your trip.';
  return { source: 'ai', note, items };
}
export const shopRecommend = spacetimedb.procedure(Recommendations, (ctx) => {
  const r = ctx.withTx((tx) => recContext(tx));
  const rules = ruleRecs(r);
  if (!r.ai || !r.storeId || (r.budget && r.remaining <= 0)) return rules;
  try { return aiRecs(ctx, r); } catch (_e) { return rules; }    // any AI failure falls back to the rules
});

// ── Trip summary ──
function canSeeOrder(ctx: Ctx, orderId: bigint) {
  const ow = ctx.db.orderOwner.orderId.find(orderId);
  if (!ow) return false;
  const p = principalOf(ctx, ctx.sender);
  return p ? ow.accountId === p : (ow.accountId === 0n && ow.identity.toHexString() === ctx.sender.toHexString());
}
const plur = (n: number, w: string) => n + ' ' + w + (n === 1 ? '' : 's');
// The phone's latest finished visit that wrote no order (empty bag, or only items the store doesn't sell), if it is
// newer than the order a summary would otherwise show and belongs to the current login's time on this phone.
function emptyVisitAfter(ctx: Ctx, order: { paidAt: { microsSinceUnixEpoch: bigint } } | null) {
  let last = null as null | SessionRow;
  for (const s of ctx.db.shopSession.identity.filter(ctx.sender)) if (s.status === 'completed' && (!last || s.id > last.id)) last = s;
  if (!last || last.orderId !== 0n) return null;
  if (order && order.paidAt.microsSinceUnixEpoch >= last.endedMicros) return null;
  const l = loginOf(ctx);
  if (l && l.signedInAt.microsSinceUnixEpoch > last.endedMicros) return null;
  return last;
}
function summaryOf(ctx: Ctx, orderId: bigint) {
  const order = orderId ? (canSeeOrder(ctx, orderId) ? ctx.db.shopOrder.id.find(orderId) : null) : latestOwnOrder(ctx);
  if (orderId && !order) throw new SenderError('We couldn’t find that trip.');
  const empty = orderId ? null : emptyVisitAfter(ctx, order);
  if (empty) {
    const st = ctx.db.store.id.find(empty.storeId);
    const mins = Math.max(1, Math.round(Number(empty.endedMicros - empty.startedAt.microsSinceUnixEpoch) / 60_000_000));
    return { receipt: NO_RECEIPT, sessionId: empty.sid, startedMicros: empty.startedAt.microsSinceUnixEpoch, endedMicros: empty.endedMicros,
      eventCount: empty.eventCount, budgetCents: empty.budgetCents, underBudgetCents: BigInt(empty.budgetCents),
      text: 'You left ' + (st ? st.name : 'the store') + ' with nothing to pay for. Your trip took ' + plur(mins, 'minute') + '.' };
  }
  if (!order) return { receipt: NO_RECEIPT, sessionId: '', startedMicros: 0n, endedMicros: 0n, eventCount: 0, budgetCents: 0, underBudgetCents: 0n, text: 'No trips yet.' };
  let sess = null as null | SessionRow;
  for (const s of ctx.db.shopSession.orderId.filter(order.id)) { sess = s; break; }
  const budget = sess ? sess.budgetCents : 0;
  const started = sess ? sess.startedAt.microsSinceUnixEpoch : 0n;
  const ended = sess && sess.endedMicros ? sess.endedMicros : order.paidAt.microsSinceUnixEpoch;
  const parts: string[] = [];
  const paid = order.status === 'paid';
  parts.push((paid ? 'You spent ' : 'Your total was ') + money(order.totalCents) + ' on ' + plur(order.itemCount, 'item') + ' at ' + order.storeName + '.');
  if (started > 0n && ended > started) {
    const mins = Math.max(1, Math.round(Number(ended - started) / 60_000_000));
    parts.push('Your trip took ' + plur(mins, 'minute') + '.');
  }
  if (budget) {
    const d = budget - order.totalCents;
    parts.push(d > 0 ? 'That’s ' + money(d) + ' under your ' + money(budget) + ' budget.' : d < 0 ? 'That’s ' + money(-d) + ' over your ' + money(budget) + ' budget.' : 'That’s right on your ' + money(budget) + ' budget.');
  }
  if (order.unpricedCount) parts.push(plur(order.unpricedCount, 'item') + (order.unpricedCount === 1 ? ' wasn’t' : ' weren’t') + ' sold here and ' + (order.unpricedCount === 1 ? 'wasn’t' : 'weren’t') + ' charged.');
  if (!paid) parts.push('No card was on file, so nothing was charged. Show exit code ' + order.code + ' to store staff.');
  return {
    receipt: receiptOf(ctx, order), sessionId: sess ? sess.sid : '', startedMicros: started, endedMicros: ended,
    eventCount: sess ? sess.eventCount : 0, budgetCents: budget, underBudgetCents: budget ? BigInt(budget - order.totalCents) : 0n,
    text: parts.join(' '),
  };
}
export const shopSummary = spacetimedb.procedure({ orderId: t.u64() }, TripSummary, (ctx, { orderId }) => ctx.withTx((tx) => summaryOf(tx, orderId)));

// The current store's catalog for the apps' 'Change item' picker (contract v1.1). reason = the camera label(s) mapped to
// the product ('' if none). Empty outside a store.
export const shopStoreProducts = spacetimedb.procedure(t.array(Recommendation), (ctx) => ctx.withTx((tx) => {
  const row = tx.db.shopper.identity.find(tx.sender);
  const storeId = row ? row.storeId : '';
  if (!storeId) return [];
  const labels = new Map<string, string[]>();
  for (const l of tx.db.productLabel.by_store_label.filter(storeId)) labels.set(l.sku, [...(labels.get(l.sku) || []), l.label]);
  return [...tx.db.product.by_store_sku.filter(storeId)]
    .map((p) => ({ sku: p.sku, name: p.name, priceCents: p.priceCents, reason: (labels.get(p.sku) || []).sort().join(', ') }))
    .sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
}));

// The cart lost its session (rebooted, or stopped it after 30 minutes) while the phone is still on the visit: the cart
// would restart the same sid at seq 1 and the backend would answer 'duplicate'. The phone calls this instead of
// re-STARTing the old sid when STATUS shows the cart is not ACTIVE on it and lastSeq > 0: the same visit and cart get a
// new session id and a fresh sequence space; events still tagged with the old id answer no_session.
export const shopSessionRekey = spacetimedb.procedure(SessionState, (ctx) => ctx.withTx((tx) => {
  const s = activeSessionOf(tx, tx.sender);
  if (!s) return sessionState(tx);
  let n = 1, sid = '';
  do { sid = 's' + s.id.toString(36) + 'r' + (n++).toString(36); } while (tx.db.shopSession.sid.find(sid));
  if (sid.length > 12) throw new SenderError('Session can’t be renewed.');
  for (const e of [...tx.db.hwEventSeen.sessionId.filter(s.id)]) tx.db.hwEventSeen.key.delete(e.key);
  tx.db.shopSession.id.update({ ...s, sid, lastSeq: 0n });
  logBag(tx, s.storeId, 'REKEY ' + s.sid + ' -> ' + sid, 'cart restarted, new session');
  return sessionState(tx);
}));

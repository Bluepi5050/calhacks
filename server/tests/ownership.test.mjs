// Live regression test for module v5.2 (order ownership + safe account switching).
// Runs against the live database with brand-new identities only: it opens its own throwaway store far out at sea,
// creates test accounts, and removes the store, its test orders and the accounts at the end. Real stores and
// real orders are never touched.
// Run: STDB_CLIENT=/path/to/stdb-client.mjs node ownership.test.mjs   (the generated TypeScript client bundle)
const clientPath = process.env.STDB_CLIENT || '../../../console/dist/stdb-client-v5.mjs';
const { DbConnection } = await import(clientPath);
const URI = 'https://maincloud.spacetimedb.com', DB = process.env.STDB_DB || 'smartcart-5dt35';
const conn = () => new Promise((res, rej) => DbConnection.builder().withUri(URI).withDatabaseName(DB)
  .onConnect((cn, id) => res(cn)).onConnectError((x, e) => rej(e)).build());
const sub = (c, q) => new Promise((res, rej) => c.subscriptionBuilder().onApplied(res).onError((x, e) => rej(e)).subscribe(q));
let fails = 0, passes = 0;
const ok = (cond, tag, extra = '') => { if (cond) passes++; else fails++; console.log((cond ? 'ok   ' : 'FAIL ') + tag + (extra ? '  ' + extra : '')); };
const rejects = async (tag, p, frag) => {
  try { await p; ok(false, tag, '(no error)'); }
  catch (e) { const m = String(e.message || e); ok(m.includes(frag), tag, '-> ' + m.replace(/^.*fatal error: /, '').slice(0, 110)); }
};
const codes = (rs) => rs.map((r) => r.code);
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const stamp = Date.now(), rnd = Math.random().toString(36).slice(2, 7);
const mail = (k) => `test+own-${k}-${stamp}@bagitwith.us`;
const PW = 'correct horse ' + rnd;

// A throwaway store in the open Pacific (no real store anywhere near).
const T = await conn();
const L = { lat: 0.2 + Math.random() * 0.6, lng: -150 - Math.random() };
await T.reducers.pinStore({ name: 'Ownership Test Market (automated)', lat: L.lat, lng: L.lng });
await sub(T, ['SELECT * FROM my_stores']);
const storeId = [...T.db.myStores.iter()].map((s) => s.storeId)[0];
console.log('test store', storeId, L.lat.toFixed(4), L.lng.toFixed(4));
const inStore = { lat: L.lat, lng: L.lng, accuracyM: 8 };
const out1 = { lat: L.lat + 300 / 111320, lng: L.lng, accuracyM: 8 }, out2 = { lat: L.lat + 330 / 111320, lng: L.lng, accuracyM: 8 };
let tag = 0;
const enter = async (P) => { const s = await P.shopLocate(inStore); if (s.storeId !== storeId) throw new Error('did not enter test store: ' + s.storeId); return s; };
const add = (P, sku) => P.shopBagLine({ line: '+' + sku + '@T' + (++tag) });
const leave = async (P) => { await P.shopLocate(out1); return P.shopLocate(out2); };
const trip = async (P, skus) => { await enter(P); for (const k of skus) await add(P, k); const s = await leave(P); return s.lastReceipt; };

const phones = [];
const phone = async () => { const c = await conn(); phones.push(c); return c.procedures; };
const accounts = [];   // [P, email] for cleanup
try {
  // ── 1. Two accounts on the same phone X ──────────────────────────────────────────
  console.log('\n# two accounts, one phone');
  const X = await phone(), Y = await phone(), Z = await phone();
  await X.accountSignUp({ name: 'Alice Test', email: mail('a'), password: PW }); accounts.push(mail('a'));
  let a = await X.cardAdd({ brand: 'Visa', last4: '4242', expMonth: 8, expYear: 2029, nickname: 'Alice', makeDefault: true });
  await Y.accountSignUp({ name: 'Bob Test', email: mail('b'), password: PW }); accounts.push(mail('b'));
  const bState = await Y.cardAdd({ brand: 'Mastercard', last4: '4444', expMonth: 1, expYear: 2031, nickname: 'Bob', makeDefault: true });
  const R1 = await trip(X, ['1005', '1008']);
  // public rows: no card label, no nickname
  await sub(T, ["SELECT * FROM shopper WHERE store_id = '" + storeId + "'"]);
  ok(R1.status === 'paid' && R1.payment === 'Visa •••• 4242', 'A trip 1 charged to A', R1.code + ' ' + R1.payment);
  // mid-trip: no account change of any kind
  await enter(X); await add(X, '1001');
  await rejects('switch to B mid-trip refused', X.accountSignIn({ email: mail('b'), password: PW }), 'middle of a trip');
  await rejects('sign out mid-trip refused', X.accountSignOut({}), 'middle of a trip');
  await rejects('sign up mid-trip refused', X.accountSignUp({ name: 'Carol', email: mail('c'), password: PW }), 'middle of a trip');
  let st = await X.shopState({});
  ok(st.payment === 'Alice Visa •••• 4242' && st.itemCount === 1, 'still A, bag intact after refusals');
  await new Promise((r) => setTimeout(r, 800));
  const pubRows = [...T.db.shopper.iter()].map((r) => r.payment);
  ok(pubRows.length > 0 && pubRows.every((p) => p === 'Card on file'), 'public shopper.payment shows no card label', JSON.stringify(pubRows));
  const R2 = (await leave(X)).lastReceipt;
  ok(R2.status === 'paid' && R2.payment === 'Visa •••• 4242' && R2.orderId > R1.orderId, 'A trip 2 charged to A', R2.code);
  // out of a store with a leftover item and a bag name: switching clears both
  await X.shopSetBag({ name: "Alice's bag" });
  await X.shopBagLine({ line: '+1003@LEFT' + rnd });
  let b = await X.accountSignIn({ email: mail('b'), password: PW });
  ok(b.signedIn && b.email === mail('b'), 'switch to B out of a store allowed');
  st = await X.shopState({});
  ok(st.lines.length === 0, 'A leftover item gone after switch', 'lines=' + st.lines.length);
  ok(st.bag === '', 'A bag name gone after switch', JSON.stringify(st.bag));
  ok(st.payment === 'Bob Mastercard •••• 4444', 'payment is B card', st.payment);
  ok(st.lastReceipt.orderId === 0n, 'lastReceipt does not show A receipt', 'orderId=' + st.lastReceipt.orderId);
  ok((await X.shopTrips({ limit: 50 })).length === 0, 'B on X sees none of A trips');
  ok((await Y.shopTrips({ limit: 50 })).length === 0, 'B on Y sees none of A trips');
  const R3 = await trip(X, ['1010']);
  ok(R3.status === 'paid' && R3.payment === 'Mastercard •••• 4444', 'B trip on X charged to B', R3.code);
  ok(same(codes(await X.shopTrips({ limit: 50 })), [R3.code]), 'B trips = [R3] on X');
  ok(same(codes(await Y.shopTrips({ limit: 50 })), [R3.code]), 'B trips = [R3] on Y (other phone)');
  // my_orders view follows the same rule
  const Xc = phones[0];
  await sub(Xc, ['SELECT * FROM my_orders']);
  ok(same([...Xc.db.myOrders.iter()].map((o) => o.code), [R3.code]), 'my_orders on X = [R3] only');
  // A keeps full history from another phone although X is now B
  a = await Z.accountSignIn({ email: mail('a'), password: PW });
  ok(same(codes(await Z.shopTrips({ limit: 50 })), [R2.code, R1.code]), 'A history kept after X switched away', codes(await Z.shopTrips({ limit: 50 })).join(','));
  // guest on X: nothing of A or B
  await X.accountSignOut({});
  st = await X.shopState({});
  ok(st.payment === '' && st.lastReceipt.orderId === 0n, 'guest on X: no payment, no receipt');
  ok((await X.shopTrips({ limit: 50 })).length === 0, 'guest on X: no account trips');
  // anonymous demo still works
  await X.shopSetPayment({ label: 'Demo Visa •••• 4242' });
  const R4 = await trip(X, ['1002']);
  ok(R4.status === 'paid' && R4.payment === 'Demo Visa •••• 4242', 'guest demo walk-out charged demo label', R4.code);
  ok(same(codes(await X.shopTrips({ limit: 50 })), [R4.code]), 'guest trips = [R4]');
  // back to A on X: A's own trips and A's own last receipt, not the guest's
  await X.accountSignIn({ email: mail('a'), password: PW });
  st = await X.shopState({});
  ok(same(codes(await X.shopTrips({ limit: 50 })), [R2.code, R1.code]), 'A on X again: [R2, R1], no guest/B trips');
  ok(st.lastReceipt.code === R2.code, 'A lastReceipt on X = R2', st.lastReceipt.code);
  ok(st.payment === 'Alice Visa •••• 4242', 'A payment restored', st.payment);

  // ── 2. Sign-out and re-login across phones ───────────────────────────────────────
  console.log('\n# sign-out / re-login, two phones');
  const P1 = await phone(), P2 = await phone();
  await P1.accountSignUp({ name: 'Dana Test', email: mail('d'), password: PW }); accounts.push(mail('d'));
  await P1.cardAdd({ brand: 'Visa', last4: '1881', expMonth: 3, expYear: 2030, nickname: '', makeDefault: true });
  await P2.accountSignIn({ email: mail('d'), password: PW });
  const T1 = await trip(P1, ['1006']), T2 = await trip(P2, ['1007']);
  ok(T1.payment === 'Visa •••• 1881' && T2.payment === 'Visa •••• 1881', 'both phones charged D card');
  ok(same(codes(await P2.shopTrips({ limit: 50 })), [T2.code, T1.code]), 'D trips from both phones');
  await P1.accountSignOut({});
  ok(same(codes(await P2.shopTrips({ limit: 50 })), [T2.code, T1.code]), 'P1 signed out: D still has T1 (history not lost)');
  ok((await P1.shopTrips({ limit: 50 })).length === 0 && (await P1.shopState({})).lastReceipt.orderId === 0n, 'P1 as guest: no D trips or receipt');
  await P1.accountSignIn({ email: mail('d'), password: PW });
  ok(same(codes(await P1.shopTrips({ limit: 50 })), [T2.code, T1.code]), 'P1 re-login: full D history');
  ok((await P1.shopState({})).lastReceipt.code === T1.code, 'P1 re-login: lastReceipt = T1');
  await P1.accountSignOut({});
  await P1.accountSignUp({ name: 'Eve Test', email: mail('e'), password: PW }); accounts.push(mail('e'));
  ok((await P1.shopTrips({ limit: 50 })).length === 0 && (await P1.shopState({})).lastReceipt.orderId === 0n, 'E on P1: none of D');
  // deletion refused while a linked phone is mid-trip, allowed once the bag is empty
  await enter(P2); await add(P2, '1004');
  await rejects('delete D while P2 mid-trip refused', P1.accountSignIn({ email: mail('d'), password: PW }).then(() => P1.accountDelete({ password: PW })), 'middle of a trip');
  // (P1 sign-in above switched P1 from E to D; P2 is the mid-trip phone)
  await P2.shopRemove({ sku: '1004' });
  const del = await P1.accountDelete({ password: PW }); accounts.splice(accounts.indexOf(mail('d')), 1);
  ok(!del.signedIn, 'D deleted once no phone is mid-trip');
  const p2 = await P2.shopState({});
  ok(p2.payment === '' && p2.lastReceipt.orderId === 0n && (await P2.shopTrips({ limit: 50 })).length === 0, 'P2 after D deleted: no payment, receipt or trips');
} catch (e) {
  fails++; console.log('FAIL (exception)', String(e && (e.message || e)));
} finally {
  // cleanup: test accounts, then the test store with its test orders
  for (const em of [...accounts]) {
    try { const c = await conn(); await c.procedures.accountSignIn({ email: em, password: PW }); await c.procedures.accountDelete({ password: PW }); c.disconnect(); }
    catch (e) { console.log('cleanup account', em, String(e.message || e).slice(0, 80)); }
  }
  try { await T.reducers.resetDemo({ storeId }); await T.reducers.deleteStore({ id: storeId }); console.log('cleanup: test store removed'); }
  catch (e) { console.log('cleanup store failed', String(e.message || e)); }
  for (const c of phones) c.disconnect(); T.disconnect();
  console.log(`\n${passes} passed, ${fails} failed`);
  process.exit(fails ? 1 : 0);
}

// Live test for module v6: SmartCart hardware sessions (docs/SMARTCART-BLE.md sections 4-5).
// Uses brand-new identities and its own throwaway store far out at sea; removes the store, its orders and the test
// account at the end. Real stores and real orders are never touched.
// Run: STDB_CLIENT=/path/to/stdb-client.mjs node hw_session.test.mjs   (the generated TypeScript client bundle, v6 bindings)
const clientPath = process.env.STDB_CLIENT || '../../../console/dist/stdb-client-v6.mjs';
const { DbConnection } = await import(clientPath);
const URI = 'https://maincloud.spacetimedb.com', DB = process.env.STDB_DB || 'smartcart-5dt35';
const conn = () => new Promise((res, rej) => DbConnection.builder().withUri(URI).withDatabaseName(DB)
  .onConnect((cn) => res(cn)).onConnectError((x, e) => rej(e)).build());
const sub = (c, q) => new Promise((res, rej) => c.subscriptionBuilder().onApplied(res).onError((x, e) => rej(e)).subscribe(q));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let fails = 0, passes = 0;
const ok = (cond, tag, extra = '') => { if (cond) passes++; else fails++; console.log((cond ? 'ok   ' : 'FAIL ') + tag + (extra ? '  ' + extra : '')); };
const rejects = async (tag, p, frag) => {
  try { await p; ok(false, tag, '(no error)'); }
  catch (e) { const m = String(e.message || e); ok(m.includes(frag), tag, '-> ' + m.replace(/^.*fatal error: /, '').slice(0, 110)); }
};
const line = (st, sku) => st.cart.lines.find((l) => l.sku === sku);
const qty = (st, sku) => (line(st, sku) ? line(st, sku).qty : 0);
const stamp = Date.now(), rnd = Math.random().toString(36).slice(2, 7);
const PW = 'correct horse ' + rnd, EMAIL = `test+hw-${stamp}@bagitwith.us`;

const T = await conn();
const L = { lat: 0.2 + Math.random() * 0.6, lng: -150 - Math.random() };
await T.reducers.pinStore({ name: 'Hardware Test Market (automated)', lat: L.lat, lng: L.lng });
await sub(T, ['SELECT * FROM my_stores']);
const storeId = [...T.db.myStores.iter()].map((s) => s.storeId)[0];
console.log('test store', storeId);
const inStore = { lat: L.lat, lng: L.lng, accuracyM: 8 };
const out1 = { lat: L.lat + 300 / 111320, lng: L.lng, accuracyM: 8 }, out2 = { lat: L.lat + 330 / 111320, lng: L.lng, accuracyM: 8 };
const conns = [];
const phone = async () => { const c = await conn(); conns.push(c); return c.procedures; };
let accountMade = false;
const ev = (P, sid, seq, action, label, confidence = 93) => P.shopHwEvent({ sessionId: sid, seq: BigInt(seq), action, label, confidence });
try {
  await sub(T, ["SELECT * FROM product_label WHERE store_id = '" + storeId + "'", "SELECT * FROM product WHERE store_id = '" + storeId + "'",
    "SELECT * FROM bag_event WHERE store_id = '" + storeId + "'"]);
  const labels = Object.fromEntries([...T.db.productLabel.iter()].map((l) => [l.label, l.sku]));
  ok(['apple', 'banana', 'orange', 'bottle', 'carrot', 'broccoli'].every((k) => labels[k]), 'new store seeded with 6 labels', JSON.stringify(labels));
  const prod = Object.fromEntries([...T.db.product.iter()].map((p) => [p.sku, p]));
  const APPLE = labels.apple, ORANGE = labels.orange, BANANA = labels.banana;
  ok(prod[ORANGE] && prod[ORANGE].name === 'Oranges', 'Oranges added to the catalog', prod[ORANGE] && prod[ORANGE].priceCents);

  console.log('\n# session lifecycle');
  const P = await phone(), Q = await phone();
  let s = await P.shopSessionState({});
  ok(s.sessionId === '' && !s.active, 'outside a store: no session');
  let r = await ev(P, 's9zzzz', 1, 'A', 'apple');
  ok(r.status === 'no_session' && r.state.cart.lines.length === 0, 'event without a session -> no_session', r.status);
  await P.shopLocate(inStore);
  s = await P.shopSessionState({});
  const sid = s.sessionId;
  ok(/^s[a-z0-9]{1,11}$/.test(sid) && s.active && s.storeId === storeId && s.lastSeq === 0n, 'entering the store opens a session', sid);
  await P.shopLocate(inStore);
  ok((await P.shopSessionState({})).sessionId === sid, 'another fix in the store keeps the same session');

  console.log('\n# events');
  r = await ev(P, sid, 1, 'A', 'apple');
  ok(r.status === 'accepted' && qty(r.state, APPLE) === 1 && line(r.state, APPLE).priceCents === prod[APPLE].priceCents && r.state.spentCents === prod[APPLE].priceCents,
    'A apple accepted at the store price', r.message);
  r = await ev(P, sid, 1, 'A', 'apple');
  ok(r.status === 'duplicate' && qty(r.state, APPLE) === 1, 'same seq again -> duplicate, cart unchanged');
  r = await ev(P, sid, 2, 'R', 'apple', 89);
  ok(r.status === 'accepted' && qty(r.state, APPLE) === 0 && r.state.spentCents === 0, 'R apple removes it', r.message);
  r = await ev(P, sid, 3, 'R', 'apple');
  ok(r.status === 'invalid_remove' && r.state.cart.lines.length === 0, 'R apple at qty 0 -> invalid_remove', r.message);
  r = await ev(P, sid, 4, 'A', 'cell phone', 71);
  const unk = line(r.state, 'cell-phone');
  ok(r.status === 'unknown_product' && unk && !unk.sold && unk.priceCents === 0 && r.state.spentCents === 0, 'unmapped label -> unpriced line', r.message);
  r = await ev(P, sid, 5, 'a', 'orange');
  r = await ev(P, sid, 6, 'A', '  Banana ');
  ok(r.status === 'accepted' && qty(r.state, ORANGE) === 1 && qty(r.state, BANANA) === 1, 'labels normalized (case, spaces, lowercase action)');
  ok(r.state.lastSeq === 6n, 'lastSeq = 6', String(r.state.lastSeq));
  r = await ev(P, sid, 7, 'X', 'apple');
  ok(r.status === 'rejected' && r.state.lastSeq === 6n, 'malformed action -> rejected (terminal, ackable), nothing counted', r.message);
  r = await ev(P, sid, 0, 'A', 'apple');
  ok(r.status === 'rejected' && qty(r.state, APPLE) === 0, 'seq 0 -> rejected');
  r = await ev(P, sid, 12, 'A', '   ');
  ok(r.status === 'rejected', 'empty label -> rejected');

  console.log('\n# budget, corrections, picker, recommendations');
  s = await P.shopSetBudget({ cents: 1000 });
  const spent = prod[ORANGE].priceCents + prod[BANANA].priceCents;
  ok(s.budgetCents === 1000 && s.spentCents === spent && s.remainingCents === BigInt(1000 - spent), 'budget set, remaining = budget - spent', String(s.remainingCents));
  s = await P.shopSetQty({ sku: ORANGE, qty: 3 });
  ok(qty(s, ORANGE) === 3 && s.spentCents === 3 * prod[ORANGE].priceCents + prod[BANANA].priceCents, 'set_qty 3 oranges');
  s = await P.shopRelabel({ fromSku: ORANGE, toSku: APPLE });
  ok(qty(s, ORANGE) === 2 && qty(s, APPLE) === 1, 'relabel moves one unit orange -> apple');
  s = await P.shopSetQty({ sku: 'cell-phone', qty: 0 });
  ok(!line(s, 'cell-phone'), 'set_qty 0 removes the unpriced line');
  await P.shopSetQty({ sku: BANANA, qty: 4294967295 });
  s = await P.shopSetQty({ sku: BANANA, qty: 1 });
  ok(qty(s, BANANA) === 1, 'huge set_qty clamps to 999 (line kept), then back to 1');
  await rejects('relabel to a SKU the store lacks', P.shopRelabel({ fromSku: ORANGE, toSku: 'nope' }), 'isn’t sold here');
  const cat = await P.shopStoreProducts({});
  ok(cat.length === Object.keys(prod).length && cat.find((p) => p.sku === APPLE).reason === 'apple' && cat.some((p) => p.reason === ''), 'shop_store_products = catalog with labels', cat.length + ' products');
  ok((await Q.shopStoreProducts({})).length === 0, 'shop_store_products empty outside a store');
  s = await P.shopSessionState({});
  const rec = await P.shopRecommend({});
  const inCart = new Set(s.cart.lines.map((l) => l.sku));
  ok(rec.source === 'rules' && rec.items.length >= 1 && rec.items.length <= 3 && rec.items.every((i) => i.priceCents <= Number(s.remainingCents) && !inCart.has(i.sku) && prod[i.sku]),
    'recommendations fit the remaining budget, not in cart', rec.items.map((i) => i.name + ' ' + i.priceCents + ' (' + i.reason + ')').join('; '));
  s = await P.shopSetBudget({ cents: 1 });
  const rec2 = await P.shopRecommend({});
  ok(rec2.items.length === 0 && s.remainingCents < 0n, 'over budget -> no suggestions', rec2.note);
  s = await P.shopSetBudget({ cents: 1000 });

  console.log('\n# other phones, labels');
  r = await ev(Q, sid, 8, 'A', 'apple');
  ok(r.status === 'no_session' && r.state.cart.lines.length === 0, "another phone using P's sid -> no_session");
  ok(qty(await P.shopSessionState({}), APPLE) === 1, "P's cart unchanged by Q");
  await rejects('set_product_label by a non-staff phone refused', conns[1].reducers.setProductLabel({ storeId, label: 'cell phone', sku: APPLE }), 'Only staff');
  await rejects('set_product_label to a missing SKU refused', T.reducers.setProductLabel({ storeId, label: 'cell phone', sku: 'nope' }), 'no product');
  await T.reducers.setProductLabel({ storeId, label: 'Cell  Phone', sku: '1004' });
  r = await ev(P, sid, 9, 'A', 'cell phone');
  ok(r.status === 'accepted' && qty(r.state, '1004') === 1, 'staff mapping applies immediately', r.message);
  await T.reducers.setProductLabel({ storeId, label: 'cell phone', sku: '' });
  await sleep(500);
  ok(![...T.db.productLabel.iter()].some((l) => l.label === 'cell phone'), "'' sku deletes the mapping");

  console.log('\n# walk-out');
  await P.shopSetPayment({ label: 'Test Visa •••• 0000' });
  const before = await P.shopSessionState({});
  await P.shopLocate(out1);
  const left = await P.shopLocate(out2);
  const R1 = left.lastReceipt;
  ok(R1.orderId > 0n && R1.status === 'paid' && R1.totalCents === before.spentCents, 'walk-out charged the session cart', R1.code + ' ' + R1.totalCents);
  s = await P.shopSessionState({});
  ok(s.sessionId === '' && !s.active && s.cart.lines.length === 0, 'session completed: no active session, bag empty');
  r = await ev(P, sid, 10, 'A', 'apple');
  ok(r.status === 'stale_session' && r.state.cart.lines.length === 0, 'event after walk-out -> stale_session, cart unchanged');
  r = await ev(P, sid, 1, 'A', 'apple');
  ok(r.status === 'stale_session', 'already-processed seq after walk-out -> stale_session');
  const sum = await P.shopSummary({ orderId: 0n });
  ok(sum.receipt.orderId === R1.orderId && sum.sessionId === sid && sum.eventCount === 7 && sum.budgetCents === 1000
    && sum.underBudgetCents === BigInt(1000 - R1.totalCents) && sum.endedMicros >= sum.startedMicros && sum.startedMicros > 0n,
    'summary: receipt, session, 7 processed events (1-6, 9), budget', 'events=' + sum.eventCount);
  ok(/under your \$10\.00 budget/.test(sum.text) || /over your \$10\.00 budget/.test(sum.text), 'summary text', sum.text);
  ok((await P.shopSummary({ orderId: R1.orderId })).receipt.code === R1.code, 'summary by order id');
  await rejects("another phone can't read P's summary", Q.shopSummary({ orderId: R1.orderId }), 'couldn’t find');
  ok((await Q.shopSummary({ orderId: 0n })).receipt.orderId === 0n, 'summary(0) with no trips = empty');
  await sleep(600);
  const feed = [...T.db.bagEvent.iter()].map((e) => e.line + ' => ' + e.result);
  ok(feed.some((f) => f.startsWith('A apple 93% #1 => + ')) && feed.some((f) => f.includes('repeat, ignored')) && feed.some((f) => f.includes('remove, but not in bag'))
    && feed.some((f) => f.includes('trip ended, ignored')) && feed.some((f) => f.startsWith('WALKOUT')), 'bag feed shows every kind of event', feed.length + ' rows');

  console.log('\n# next visit, budget carry-over, account budget');
  await P.shopLocate(inStore);
  s = await P.shopSessionState({});
  ok(s.active && s.sessionId !== sid && s.budgetCents === 1000, 'new visit: new session, budget carried over', s.sessionId);
  r = await ev(P, sid, 11, 'A', 'apple');
  ok(r.status === 'stale_session' && r.state.cart.lines.length === 0, 'old session id on the new visit -> stale_session');
  const sid2 = s.sessionId;
  r = await ev(P, sid2, 1, 'A', 'apple');
  r = await ev(P, sid2, 2, 'A', 'apple');
  s = await P.shopSessionRekey({});
  const sid3 = s.sessionId;
  ok(s.active && sid3 !== sid2 && /^[a-z0-9]{1,12}$/.test(sid3) && s.lastSeq === 0n && qty(s, APPLE) === 2, 'rekey: same visit and cart, new sid, fresh seq', sid2 + ' -> ' + sid3);
  r = await ev(P, sid3, 1, 'R', 'apple');
  ok(r.status === 'accepted' && qty(r.state, APPLE) === 1, 'seq 1 under the new sid is not a duplicate');
  r = await ev(P, sid2, 3, 'R', 'apple');
  ok(r.status === 'no_session' && qty(r.state, APPLE) === 1, 'old sid after rekey -> no_session');
  await ev(P, sid3, 2, 'R', 'apple');
  await P.shopLocate(out1); await P.shopLocate(out2);
  ok(!(await P.shopSessionState({})).active, 'empty walk-out ends the session too');
  const es = await P.shopSummary({ orderId: 0n });
  ok(es.receipt.orderId === 0n && es.sessionId === sid3 && es.eventCount === 4 && /nothing to pay for/.test(es.text), 'empty visit: summary of that visit, no order', es.text);
  ok((await P.shopSummary({ orderId: R1.orderId })).receipt.code === R1.code, 'earlier trip still available by id');
  ok((await P.shopSetBudget({ cents: 0 })).budgetCents === 0, 'budget 0 clears');
  const A = await phone(), B = await phone();
  await A.accountSignUp({ name: 'Hw Test', email: EMAIL, password: PW }); accountMade = true;
  await A.shopSetBudget({ cents: 2500 });
  await B.accountSignIn({ email: EMAIL, password: PW });
  ok((await B.shopSessionState({})).budgetCents === 2500, 'signed-in budget follows the account to another phone');
  await B.accountSignOut({});
  ok((await B.shopSessionState({})).budgetCents === 0, 'signed out: the account budget no longer applies');

  console.log('\n# store removal abandons sessions');
  await Q.shopLocate(inStore);
  const qs = await Q.shopSessionState({});
  ok(qs.active && qs.sessionId !== sid2, 'Q in the store has its own session');
  await T.reducers.resetDemo({ storeId });
  await T.reducers.deleteStore({ id: storeId });
  const qa = await Q.shopSessionState({});
  r = await ev(Q, qs.sessionId, 1, 'A', 'apple');
  ok(!qa.active && r.status === 'stale_session', 'after the store is deleted the session is abandoned', r.status);
} catch (e) {
  fails++; console.log('FAIL (exception)', String(e && (e.stack || e.message || e)));
} finally {
  if (accountMade) {
    try { const c = await conn(); await c.procedures.accountSignIn({ email: EMAIL, password: PW }); await c.procedures.accountDelete({ password: PW }); c.disconnect(); }
    catch (e) { console.log('cleanup account', String(e.message || e).slice(0, 80)); }
  }
  try { await T.reducers.resetDemo({ storeId }); } catch (e) {}
  try { await T.reducers.deleteStore({ id: storeId }); } catch (e) {}
  console.log('cleanup: test store removed');
  for (const c of conns) c.disconnect(); T.disconnect();
  console.log(`\n${passes} passed, ${fails} failed`);
  process.exit(fails ? 1 : 0);
}

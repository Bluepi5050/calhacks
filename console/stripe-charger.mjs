// Stripe charger: every walk-out the database records as paid becomes a real Stripe charge (TEST MODE ONLY).
//
// It subscribes to shop_order on the SmartBag database and, for each new order with status "paid", creates and
// confirms a Stripe PaymentIntent for the order total with Stripe's test card for the order's card brand
// (pm_card_visa for Visa). Each charge carries the order id, exit code and store in its metadata and uses the
// idempotency key bagit-order-<id>, so an order is never charged twice, even if this script restarts.
// Orders that existed before the first run are never charged (state in .stripe-charger.json).
//
// The app only stores a card's brand and last four (no card data is collected), which is why the charge uses
// Stripe's test PaymentMethods. Live keys are refused.
//
// Usage (from console/, after build.sh or the esbuild step that writes dist/stdb-client.mjs):
//   STRIPE_SECRET_KEY=sk_test_... node stripe-charger.mjs        charge new paid orders
//   node stripe-charger.mjs --dry-run                             print what would be charged, no key needed
import { readFileSync, writeFileSync } from 'node:fs';
import { DbConnection } from './dist/stdb-client.mjs';

const HOST = process.env.STDB_HOST ?? 'https://maincloud.spacetimedb.com';
const DB = process.env.STDB_DB ?? 'smartcart-5dt35';
const STATE = new URL('./.stripe-charger.json', import.meta.url);
const dryRun = process.argv.includes('--dry-run');
const key = process.env.STRIPE_SECRET_KEY ?? '';

if (!dryRun) {
  if (!key) { console.error('Set STRIPE_SECRET_KEY to a Stripe test secret key (sk_test_...), or use --dry-run.'); process.exit(1); }
  if (!key.startsWith('sk_test_') && !key.startsWith('rk_test_')) { console.error('Refusing a key that is not a test-mode key.'); process.exit(1); }
}

const log = (m) => console.log(new Date().toISOString().slice(11, 19), m);
const money = (c) => '$' + (c / 100).toFixed(2);

let state = { after: null, charged: {} };
try { state = JSON.parse(readFileSync(STATE, 'utf8')); } catch { /* first run */ }
const save = () => writeFileSync(STATE, JSON.stringify(state, null, 1));

/** Stripe's test PaymentMethod for the brand in the order's payment label ("Demo Visa •••• 4242"). */
function testCard(label) {
  const l = label.toLowerCase();
  if (l.includes('mastercard')) return 'pm_card_mastercard';
  if (l.includes('amex') || l.includes('american express')) return 'pm_card_amex';
  if (l.includes('discover')) return 'pm_card_discover';
  return 'pm_card_visa';
}

const inFlight = new Set();   // the SDK reports existing rows as inserts too: never send one order twice at once

async function charge(o) {
  const id = String(o.id);
  if (state.charged[id] || inFlight.has(id)) return;
  inFlight.add(id);
  try { await chargeOnce(o, id); } finally { inFlight.delete(id); }
}

async function chargeOnce(o, id) {
  const pm = testCard(o.payment);
  const what = `order ${id} ${o.code} at ${o.storeName}: ${money(o.totalCents)} (${o.payment}) -> ${pm}`;
  if (dryRun) { log(`would charge ${what}`); return; }
  const body = new URLSearchParams({
    // Current Stripe API versions take payment methods from the Dashboard (automatic_payment_methods); a card needs
    // no redirect, so confirm right here.
    amount: String(o.totalCents), currency: 'usd', payment_method: pm, confirm: 'true',
    'automatic_payment_methods[enabled]': 'true', 'automatic_payment_methods[allow_redirects]': 'never',
    description: `Bag It walk-out ${o.code} at ${o.storeName}`,
    statement_descriptor_suffix: 'BAG IT',
    'metadata[order_id]': id, 'metadata[exit_code]': o.code, 'metadata[store_id]': o.storeId,
    'metadata[store]': o.storeName, 'metadata[items]': String(o.itemCount), 'metadata[card_label]': o.payment,
  });
  try {
    const r = await fetch('https://api.stripe.com/v1/payment_intents', {
      method: 'POST', body,
      // -v2: the first version's keys may hold a stored error for different parameters, which Stripe would replay.
      headers: { Authorization: `Bearer ${key}`, 'Idempotency-Key': `bagit-order-${id}-v2` },
    });
    const pi = await r.json();
    if (!r.ok) { log(`FAILED ${what}: ${pi.error?.message ?? r.status}`); return; }
    state.charged[id] = { pi: pi.id, charge: pi.latest_charge, status: pi.status, cents: o.totalCents, code: o.code };
    save();
    log(`charged ${what}: ${pi.status} ${pi.id} ${pi.latest_charge ?? ''}`);
  } catch (e) {
    log(`FAILED ${what}: ${e.message} (will retry when the order is seen again)`);
  }
}

const conn = await new Promise((res, rej) => DbConnection.builder().withUri(HOST).withDatabaseName(DB)
  .onConnect((c) => res(c)).onConnectError((_ctx, e) => rej(e))
  .onDisconnect(() => { log('disconnected from the database: restart me'); process.exit(1); })
  .build());

const eligible = (o) => o.status === 'paid' && o.totalCents > 0 && (state.after === null || BigInt(o.id) > BigInt(state.after));

conn.db.shopOrder.onInsert((_ctx, o) => { if (eligible(o)) charge(o); });
conn.subscriptionBuilder()
  .onApplied(() => {
    const ids = [...conn.db.shopOrder.iter()].map((o) => BigInt(o.id));
    if (state.after === null) {                       // first run: history is never charged
      state.after = String(ids.reduce((a, b) => (b > a ? b : a), 0n));
      save();
      log(`first run: ${ids.length} existing orders skipped (up to id ${state.after})`);
    }
    for (const o of conn.db.shopOrder.iter()) if (eligible(o)) charge(o);   // paid while this was stopped
    log(`watching ${DB} for walk-outs${dryRun ? ' (dry run)' : ' (Stripe test mode)'}`);
  })
  .onError((_ctx, e) => { log(`subscription failed: ${e?.message ?? e}`); process.exit(1); })
  .subscribe(['SELECT * FROM shop_order']);

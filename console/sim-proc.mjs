
import { DbConnection } from './dist/stdb-client.mjs';
const c = await new Promise((res, rej) => DbConnection.builder().withUri('https://maincloud.spacetimedb.com').withDatabaseName('smartcart-5dt35')
  .onConnect((cn) => res(cn)).onConnectError((ctx, e) => rej(e)).build());
const P = c.procedures;
const show = (tag, s) => console.log(tag, JSON.stringify({ store: s.storeName, items: s.itemCount, total: s.totalCents, leaving: s.leaving, receipt: s.lastReceipt.orderId ? { code: s.lastReceipt.code, total: s.lastReceipt.totalCents, status: s.lastReceipt.status } : null }, (k, v) => typeof v === 'bigint' ? String(v) : v));
show('pay', await P.shopSetPayment({ label: 'Demo Visa •••• 4242' }));
show('bag', await P.shopSetBag({ name: 'Regression tote' }));
show('in', await P.shopLocate({ lat: 37.7897, lng: -122.3972, accuracyM: 8 }));
show('+1005', await P.shopBagLine({ line: '+1005@R1' }));
show('+1005 dup', await P.shopBagLine({ line: '+1005@R1' }));
show('+1008', await P.shopBagLine({ line: '+1008@R2' }));
show('out1', await P.shopLocate({ lat: 37.7897 + 270 / 111320, lng: -122.3972, accuracyM: 8 }));
show('out2', await P.shopLocate({ lat: 37.7897 + 300 / 111320, lng: -122.3972, accuracyM: 8 }));
show('state', await P.shopState({}));
c.disconnect(); process.exit(0);

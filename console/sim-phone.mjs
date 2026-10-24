
import { DbConnection } from './dist/stdb-client.mjs';
const [,, label='SmartBag-Demo', pay='Demo Visa •••• 4242', itemsArg='+1005@A1,+1008@A2,+1003@A3', gap='1500', leave='1'] = process.argv;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const c = await new Promise((res, rej) => DbConnection.builder().withUri('https://maincloud.spacetimedb.com').withDatabaseName('smartcart-5dt35')
  .onConnect((cn) => res(cn)).onConnectError((ctx, e) => rej(e)).build());
const G = +gap;
const log = (m) => console.log(new Date().toISOString().slice(11, 23), m);
await c.reducers.setPayment({ label: pay }); await c.reducers.setBag({ name: label });
await c.reducers.updateLocation({ lat: 37.7897, lng: -122.3972, accuracyM: 8 }); log('entered');
for (const it of itemsArg.split(',').filter(Boolean)) { await sleep(G); await c.reducers.bagLine({ line: it }); log('bag ' + it); }
if (leave === '1') {
  await sleep(G * 2); await c.reducers.updateLocation({ lat: 37.7897 + 270 / 111320, lng: -122.3972, accuracyM: 8 }); log('fix 1 outside');
  await sleep(G * 2.5); await c.reducers.updateLocation({ lat: 37.7897 + 300 / 111320, lng: -122.3972, accuracyM: 8 }); log('fix 2 outside: walked out');
}
await sleep(500); c.disconnect(); process.exit(0);

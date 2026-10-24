
import { DbConnection } from './dist/stdb-client.mjs';
const CONNS = +process.argv[2] || 64, PER = +process.argv[3] || 20;
const lat = []; let bad = 0;
function connect() {
  return new Promise((res, rej) => {
    DbConnection.builder().withUri('https://maincloud.spacetimedb.com').withDatabaseName('smartcart-5dt35')
      .onConnect((c) => res(c)).onConnectError((ctx, e) => rej(e)).build();
  });
}
const conns = await Promise.all(Array.from({ length: CONNS }, connect));
await Promise.all(conns.map((c, i) => c.reducers.updateLocation({ lat: 37.7749 + (Math.floor(i / 100) - 50) * 0.006, lng: -122.4194 + ((i % 100) - 50) * 0.006, accuracyM: 15 })));
const t0 = performance.now();
await Promise.all(conns.map(async (c, i) => {
  for (let j = 0; j < PER; j++) {
    const s = performance.now();
    try { await c.reducers.bagLine({ line: '+' + (1001 + (j % 16)) + '@W' + i + 'x' + j }); } catch (e) { bad++; }
    lat.push(performance.now() - s);
  }
}));
const wall = (performance.now() - t0) / 1000;
lat.sort((a, b) => a - b);
const p = (q) => Math.round(lat[Math.min(lat.length - 1, Math.floor(lat.length * q))]);
console.log(JSON.stringify({ conns: CONNS, calls: lat.length, wall_s: +wall.toFixed(1), calls_per_s: Math.round(lat.length / wall), p50_ms: p(.5), p95_ms: p(.95), p99_ms: p(.99), bad }));
conns.forEach((c) => c.disconnect());
process.exit(0);

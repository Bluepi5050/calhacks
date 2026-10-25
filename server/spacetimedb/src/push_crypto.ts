// Minimal crypto for Firebase Cloud Messaging from inside the module (no crypto library in the module runtime):
// SHA-256, base64, PKCS#8 RSA key parsing and RS256 (RSASSA-PKCS1-v1_5) JWT signing for Google's OAuth service-account flow.

const K = new Uint32Array([0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2]);
export function sha256(msg: Uint8Array): Uint8Array {
  const h = new Uint32Array([0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19]);
  const l = msg.length, nBlocks = ((l + 9 + 63) >> 6);
  const buf = new Uint8Array(nBlocks * 64); buf.set(msg); buf[l] = 0x80;
  const bits = l * 8; const dv = new DataView(buf.buffer);
  dv.setUint32(buf.length - 4, bits >>> 0); dv.setUint32(buf.length - 8, Math.floor(bits / 0x100000000));
  const w = new Uint32Array(64);
  for (let b = 0; b < nBlocks; b++) {
    for (let i = 0; i < 16; i++) w[i] = dv.getUint32(b * 64 + i * 4);
    for (let i = 16; i < 64; i++) {
      const x = w[i - 15], y = w[i - 2];
      const s0 = ((x >>> 7) | (x << 25)) ^ ((x >>> 18) | (x << 14)) ^ (x >>> 3);
      const s1 = ((y >>> 17) | (y << 15)) ^ ((y >>> 19) | (y << 13)) ^ (y >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
    }
    let a = h[0], bb = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
    for (let i = 0; i < 64; i++) {
      const S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
      const t1 = (hh + S1 + ((e & f) ^ (~e & g)) + K[i] + w[i]) >>> 0;
      const S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
      const t2 = (S0 + ((a & bb) ^ (a & c) ^ (bb & c))) >>> 0;
      hh = g; g = f; f = e; e = (d + t1) >>> 0; d = c; c = bb; bb = a; a = (t1 + t2) >>> 0;
    }
    h[0] = (h[0] + a) >>> 0; h[1] = (h[1] + bb) >>> 0; h[2] = (h[2] + c) >>> 0; h[3] = (h[3] + d) >>> 0;
    h[4] = (h[4] + e) >>> 0; h[5] = (h[5] + f) >>> 0; h[6] = (h[6] + g) >>> 0; h[7] = (h[7] + hh) >>> 0;
  }
  const out = new Uint8Array(32); const ov = new DataView(out.buffer);
  for (let i = 0; i < 8; i++) ov.setUint32(i * 4, h[i]);
  return out;
}

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
export function b64decode(s: string): Uint8Array {
  const clean = s.replace(/[^A-Za-z0-9+/]/g, '');
  const out: number[] = [];
  let buf = 0, n = 0;
  for (const ch of clean) { buf = (buf << 6) | B64.indexOf(ch); n += 6; if (n >= 8) { n -= 8; out.push((buf >> n) & 0xff); } }
  return new Uint8Array(out);
}
export function b64url(bytes: Uint8Array): string {
  let s = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const a = bytes[i], b = bytes[i + 1], c = bytes[i + 2];
    s += B64[a >> 2] + B64[((a & 3) << 4) | ((b ?? 0) >> 4)];
    if (b !== undefined) s += B64[((b & 15) << 2) | ((c ?? 0) >> 6)];
    if (c !== undefined) s += B64[c & 63];
  }
  return s.replace(/\+/g, '-').replace(/\//g, '_');
}
const enc = (x: string) => new TextEncoder().encode(x);

// DER reader for the few structures we need.
function readTlv(d: Uint8Array, p: number): { tag: number; start: number; end: number } {
  const tag = d[p++];
  let len = d[p++];
  if (len & 0x80) { const nb = len & 0x7f; len = 0; for (let i = 0; i < nb; i++) len = len * 256 + d[p++]; }
  return { tag, start: p, end: p + len };
}
function children(d: Uint8Array, start: number, end: number) {
  const out: Array<{ tag: number; start: number; end: number }> = [];
  for (let p = start; p < end;) { const t = readTlv(d, p); out.push(t); p = t.end; }
  return out;
}
function bigOf(d: Uint8Array, t: { start: number; end: number }): bigint {
  let x = 0n; for (let i = t.start; i < t.end; i++) x = (x << 8n) | BigInt(d[i]); return x;
}
export type RsaKey = { n: bigint; d: bigint; p: bigint; q: bigint; dp: bigint; dq: bigint; qi: bigint; k: number };
export function parsePkcs8(pem: string): RsaKey {
  const der = b64decode(pem.replace(/-----[^-]+-----/g, ''));
  const top = readTlv(der, 0);
  let seq = children(der, top.start, top.end);
  let rsaDer = der, rsa = top;
  if (seq.length >= 3 && seq[1].tag === 0x30 && seq[2].tag === 0x04) {      // PKCS#8 wrapper -> inner PKCS#1
    rsaDer = der.slice(seq[2].start, seq[2].end); rsa = readTlv(rsaDer, 0);
  }
  const f = children(rsaDer, rsa.start, rsa.end).map((t) => bigOf(rsaDer, t));
  const n = f[1];
  return { n, d: f[3], p: f[4], q: f[5], dp: f[6], dq: f[7], qi: f[8], k: Math.ceil(n.toString(16).length / 2) };
}
function modPow(b: bigint, e: bigint, m: bigint): bigint {
  let r = 1n; b %= m;
  while (e > 0n) { if (e & 1n) r = (r * b) % m; b = (b * b) % m; e >>= 1n; }
  return r;
}
const DIGEST_INFO = [0x30,0x31,0x30,0x0d,0x06,0x09,0x60,0x86,0x48,0x01,0x65,0x03,0x04,0x02,0x01,0x05,0x00,0x04,0x20];
export function rs256(key: RsaKey, data: Uint8Array): Uint8Array {
  const h = sha256(data);
  const t = DIGEST_INFO.length + 32;
  const em = new Uint8Array(key.k); em[0] = 0; em[1] = 1;
  for (let i = 2; i < key.k - t - 1; i++) em[i] = 0xff;
  em[key.k - t - 1] = 0; em.set(DIGEST_INFO, key.k - t); em.set(h, key.k - 32);
  let m = 0n; for (const b of em) m = (m << 8n) | BigInt(b);
  const m1 = modPow(m, key.dp, key.p), m2 = modPow(m, key.dq, key.q);
  let hh = ((m1 - m2) * key.qi) % key.p; if (hh < 0n) hh += key.p;
  let s = m2 + hh * key.q;
  const out = new Uint8Array(key.k);
  for (let i = key.k - 1; i >= 0; i--) { out[i] = Number(s & 0xffn); s >>= 8n; }
  return out;
}
export function googleJwt(clientEmail: string, privateKeyPem: string, tokenUri: string, nowSec: number): string {
  const head = b64url(enc(JSON.stringify({ alg: 'RS256', typ: 'JWT' })));
  const claims = b64url(enc(JSON.stringify({
    iss: clientEmail, scope: 'https://www.googleapis.com/auth/firebase.messaging', aud: tokenUri, iat: nowSec, exp: nowSec + 3600,
  })));
  const sig = rs256(parsePkcs8(privateKeyPem), enc(head + '.' + claims));
  return head + '.' + claims + '.' + b64url(sig);
}

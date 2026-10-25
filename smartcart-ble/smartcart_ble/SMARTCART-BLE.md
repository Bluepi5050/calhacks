# SmartCart ↔ Bag It: Bluetooth protocol and backend contract (v1.2, 2026-10-24)

v1.2: a cart that lost the session (restart, power loss, stale stop) is detected by the phone and the visit gets a new
session id (`shop_session_rekey`, section 3 "Lost session"); the cart continues numbering a sid it used before; the phone
treats GATT service-changed / CONTROL write failure as a disconnect; `ERR|BUSY` means wait for the reconnect; status
`rejected` for unreadable events; `shop_summary(0)` covers a visit with nothing to pay; recovery after `stale_session` /
`no_session`; the cart name may be in the scan response only.
v1.1: OK|START is sent before resent events (fixes resend never delivering); added shop_store_products.

Shared contract between the Jetson (SmartCart hardware, Scott's teammate), the Bag It phone apps (Android first, iOS) and the
SpacetimeDB backend (smartcart-5dt35). Both sides implement exactly this; change it only by editing this file in both places.

Principle: the Jetson is the source of physical detection. The phone owns location, Bluetooth and the session lifecycle.
The backend owns the authoritative cart, prices, budget, recommendations and the trip summary. Do not modify the Jetson's
working TensorRT scanner / crossing logic; the Jetson side only adds a BLE transport around its existing ADD/REMOVE events.

## 1. GATT layout

Roles: Jetson = BLE peripheral + GATT server. Phone = central + GATT client.

| Item | UUID | Properties | Direction |
|---|---|---|---|
| SmartCart service | `7ccd0001-d416-4afc-a97d-f7c98e1afca9` | primary, advertised | |
| CONTROL | `7ccd0002-d416-4afc-a97d-f7c98e1afca9` | write (with response) | phone → Jetson |
| EVENTS | `7ccd0003-d416-4afc-a97d-f7c98e1afca9` | notify | Jetson → phone |
| STATUS | `7ccd0004-d416-4afc-a97d-f7c98e1afca9` | read, notify | Jetson → phone |

Advertising: local name `SmartCart-XXXX` (XXXX = last two bytes of the adapter MAC, uppercase hex), service UUID in the
advertisement. The 128-bit UUID leaves little room in 31 bytes, so the name may be only in the scan response (or absent).
Phones find carts by service UUID, never by name, and confirm which cart it is from STATUS `device_name`.

MTU: the phone requests MTU 247 right after connecting (Android `requestMtu(247)`; iOS negotiates automatically) and must
get at least 64 before subscribing. Every message is at most 60 bytes, so 64 is always enough.

## 2. Encoding

UTF-8 text, fields separated by `|`, one message per GATT write or notification, no newline, no `|` inside fields.
Field limits: session id `[a-z0-9]{1,12}`, store id `[a-z0-9-]{1,32}`, label = the COCO class exactly as configured in
data/detectable_products.json (currently apple, banana, orange, bottle, carrot, broccoli; lowercase, may contain spaces, max 20 chars), sequence = decimal 1..999999, confidence =
integer percent 0..100.

### Phone → Jetson (CONTROL)

| Message | Meaning |
|---|---|
| `START|<sid>|<store_id>` | Start session `sid`. If `sid` is already the active session: resume (keep the sequence counter, resend every unacknowledged event in order). If a different session (or none) is active: drop it (and its buffer) and start `sid`; its sequence continues after the last seq this cart ever sent for `sid` (persisted across restarts), 1 if the cart never used `sid`. |
| `STOP|<sid>` | End session `sid`: stop publishing, clear its buffer, go IDLE. A STOP for a session that isn't active still answers OK (idempotent). |
| `ACK|<sid>|<seq>` | The backend has processed event `seq` of `sid` (any terminal status). The Jetson removes it from the resend buffer; an ACK for a session it doesn't hold is ignored. |
| `SYNC` | Ask the Jetson to notify STATUS now. |

### Jetson → Phone (EVENTS, notify)

`<seq>|<A or R>|<label>|<confidence>`, e.g. `17|A|apple|93`, `18|R|apple|89`.
Sent only while a session is ACTIVE, immediately when the scanner confirms a crossing (no batching). `seq` increases by 1
per event within a session (starting at 1, or after the persisted last seq, see START). Gaps can happen (events lost in a
cart restart or pushed out of a full buffer); the backend accepts them. Events always belong to the session reported by the most recent STATUS/OK.

### Jetson → Phone (STATUS, read + notify)

| Message | Meaning |
|---|---|
| `S|<sid or ->|<IDLE or ACTIVE>|<last_seq>|<unacked>|<device_name>` | Current state, e.g. `S|s1k9|ACTIVE|17|0|SmartCart-3F2A`, `S|-|IDLE|0|0|SmartCart-3F2A`. This is also the value returned by a read. |
| `OK|START|<sid>` | START handled. Sent FIRST; on a resume the unacknowledged events are resent on EVENTS only after this OK. |
| `OK|STOP|<sid>` | STOP handled. |
| `ERR|<code>|<detail>` | `BAD_CMD` (unparseable), `BAD_SID`, `BUSY` (internal, e.g. `cart shutting down`). Detail is human text, max 30 chars. ERR does not name the command it answers: the phone keeps waiting for `OK|START`, sends `SYNC`, and retries START after 5 s. On `BUSY` the phone waits for the reconnect instead of treating it as a failure. |

`last_seq` in STATUS is the last seq the cart assigned in that session (it can be ahead of what reached the phone, and right
after a continuation START it already holds the persisted value).

## 3. Reliability rules

- Jetson keeps a buffer of the last 64 unacknowledged events of the active session and resends them on START-resume, in order, after notifying `OK|START|<sid>` (so the phone has a confirmed session before they arrive).
- Phone de-duplicates by (sid, seq) and forwards each event once to the backend; the backend de-duplicates again by
  event id `<sid>:<seq>`, so a resent event never changes the cart twice.
- The phone writes `ACK` only after the backend answered (any terminal status). If the backend is unreachable the phone
  keeps the events in order and retries; it never invents events and never acks unprocessed ones.
- Disconnect: phone shows "Bag disconnected", reconnects automatically, then: subscribe STATUS + EVENTS, read STATUS, reconcile:
  - phone has an active backend session `sid` → write `START|sid|store` (resume if the Jetson still has it, otherwise start).
  - phone has no active session but the Jetson is ACTIVE → write `STOP|<jetson sid>`.
  - A GATT "service changed" indication, or a failed CONTROL write, is treated as a disconnect (reconnect, then reconcile).
  - The cart, at startup and after re-registering with bluetoothd, disconnects any device already connected, and it
    respawns its Bluetooth process if that dies, so a cart restart always looks like a disconnect to the phone.
- Lost session (cart restart, power loss, stale stop), checked on every reconcile and whenever STATUS is notified while the
  phone holds an active backend session `sid`:
  - `known` = max(`SessionState.lastSeq` from `shop_session_state`, highest seq the phone received from the cart for `sid`).
  - lost = STATUS is not `S|sid|ACTIVE|...`, or it is `S|sid|ACTIVE|<last_seq>|...` with `last_seq < known`.
  - lost and `known > 0`: (1) forward every queued event of `sid` to the backend and get a terminal answer for each (ACKs to
    the cart for `sid` may be skipped), (2) call `shop_session_rekey()` → new `sessionId` (same visit and cart, `lastSeq` 0),
    persist it, (3) write `START|<new sid>|<store>` and wait for `OK|START`. Events still tagged with the old sid answer
    `no_session`.
  - otherwise: `START|sid|store` as above (resume, or a fresh start with nothing to collide).
  - The cart's numbering continuation (START) is the safety net for a phone that does not rekey; with rekey the new sid
    starts at 1. Events the cart lost in a restart are gone either way; the shopper corrects the bag in the app.
- Stale sessions: if no phone has been connected for 30 minutes while ACTIVE, the Jetson stops the session itself
  (connected = BLE link up and the device wrote CONTROL during this connection; the timer starts at the last disconnect).
- Events that arrive while the phone has no confirmed active session (no `OK|START|sid` since the last START) are logged
  and ignored, not acked.
- Physical limits stay on the Jetson: no event without a confirmed crossing; never guess removals.

## 4. Session lifecycle (phone + backend)

1. Connected ≠ shopping. The phone may be connected to the cart anywhere.
2. Entering a store: `shop_locate` puts the phone in a store (geofence: inside the store radius, GPS accuracy ≤ 75 m).
   The backend opens a session in the same transaction. The phone calls `shop_session_state` to get `sessionId`,
   then writes `START|sessionId|storeId`.
3. Shopping: each EVENTS notification → `shop_hw_event(sessionId, seq, action, label, confidence)` → cart updated →
   phone ACKs.
4. Leaving: two fixes farther than radius + 40 m + accuracy (hysteresis, two confirmations). The backend completes the
   session in the walk-out transaction: prices the bag, writes the order (simulated payment as today), freezes the session.
   `shop_locate` returns the receipt; the phone writes `STOP|sessionId` (if connected) and shows the trip summary.
5. Later events for a completed session are rejected by the backend (`stale_session`) and acked so the Jetson drops them.
6. After the walk-out the phone calls `shop_summary(0)` whether or not a receipt arrived (an empty visit writes no order).
7. Recovery: on `stale_session` or `no_session` while the phone believes it is in a store (e.g. the backend abandoned the
   session), ack, then `shop_locate` → `shop_session_state` → `START|<new sid>|<store>`.

## 5. Backend API (SpacetimeDB procedures, request/response over the phone's WebSocket; additive, old procedures unchanged)

Types (BSATN field order is the order below):
- `SessionState { sessionId: string ('' = none), storeId: string, storeName: string, active: bool, lastSeq: u64,
  budgetCents: u32 (0 = no budget), spentCents: u32, remainingCents: i64, cart: CartState }`
- `HwEventResult { status: string, message: string, state: SessionState }`, status ∈ `accepted | duplicate |
  unknown_product | invalid_remove | stale_session | no_session | rejected` (all terminal: the phone acks every one).
  `rejected` = unreadable event (action not A/R, seq outside 1..999999, empty label); cart unchanged, logged.
- `Recommendation { sku: string, name: string, priceCents: u32, reason: string }`
- `Recommendations { source: string ('ai' | 'rules'), note: string, items: Array<Recommendation> }`
- `TripSummary { receipt: Receipt, sessionId: string, startedMicros: i64, endedMicros: i64, eventCount: u32,
  budgetCents: u32, underBudgetCents: i64, text: string }`

Procedures:
- `shop_session_state() -> SessionState`
- `shop_hw_event(sessionId: string, seq: u64, action: string, label: string, confidence: u32) -> HwEventResult`
  - action `A` adds one of the store product mapped from `label`; `R` removes one.
  - idempotent by `<sessionId>:<seq>`: a repeat returns `duplicate` with the current state.
  - label not mapped in this store: recorded as an unpriced line (shown as "not sold here, won't be charged") with status
    `unknown_product`; never priced arbitrarily.
  - `R` when the quantity is 0 → `invalid_remove` (cart unchanged, flagged in the store's bag feed).
  - session not active / not this phone's → `stale_session` or `no_session` (cart unchanged).
  - every event is logged to the public bag feed (bag_event) so the store console shows it live.
- `shop_set_budget(cents: u32) -> SessionState` (per phone/account, carries over to later trips; 0 clears).
- `shop_set_qty(sku: string, qty: u32) -> SessionState` (manual correction; 0 removes the line).
- `shop_relabel(fromSku: string, toSku: string) -> SessionState` (fix a misdetection: move one unit).
- `shop_recommend() -> Recommendations` (AI via an LLM call from the procedure when configured, otherwise rules:
  in-stock items that fit the remaining budget; never suggests what's already over budget).
- `shop_summary(orderId: u64) -> TripSummary` (0 = this phone's latest trip; if its latest visit ended with nothing to pay, that visit with `receipt.orderId` 0 and the text "You left <store> with nothing to pay for.").
- `shop_session_rekey() -> SessionState` (section 3 "Lost session": the active visit gets a new `sessionId`, `lastSeq` 0, same cart; no active session = unchanged state).
- `shop_store_products() -> Array<Recommendation>` (the phone's current store catalog for the 'Change item' picker: sku, name, priceCents, reason = mapped COCO label or ''; empty when not in a store).
- Label mapping: public table `product_label { storeId, label, sku }`, staff reducer `set_product_label(storeId, label, sku)`
  ('' sku deletes). Seed for every existing store: apple, banana, orange, bottle, carrot, broccoli (the labels in the teammate's data/detectable_products.json), adding missing products.

## 6. Acceptance test (from the hardware handoff)

Phone connects to the cart outside the store (no session) → enters the store radius (session, START) → apple moved
outside→inside: one `A apple` → app shows it at the store's price → stationary apple: nothing → apple moved back: one
`R apple` → quantity and total drop → phone leaves the radius: session completed, STOP sent → further detections do not
change the ended session → trip summary shown.

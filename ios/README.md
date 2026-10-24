# Bag It for iPhone (SmartBag)

Native SwiftUI customer app for SmartBag (Cal Hacks 13.0), shown on the home screen as **Bag It** (the brand of bagitwith.us). Drop groceries in the bag and just walk out:
the SpacetimeDB module `smartcart-5dt35` finds the store from the phone's location, prices the bag, and
charges it the moment the phone leaves. There is no Pay button, no store picker, no catalog.

Same backend and wire protocol as the Android app: ONE WebSocket per phone speaking SpacetimeDB's v2
binary protocol (`v2.bsatn.spacetimedb`, `compression=None`), request/response procedure calls only
(`shop_state`, `shop_locate`, `shop_bag_line`, `shop_remove`, `shop_set_bag`; 1.2 adds the account procedures of module v5:
`account_state`, `account_sign_up`, `account_sign_in`, `account_sign_out`, `account_set_name`, `account_delete`,
`card_add`, `card_remove`, `card_set_default`, `bag_pair`, `bag_rename`, `bag_unpair`, `shop_trips`), no subscriptions.
No third-party dependencies: URLSessionWebSocketTask, CoreBluetooth, CoreLocation, UserNotifications, CoreImage.

## Run it on your iPhone (free Apple ID works)

1. On a Mac with Xcode 15 or newer, open `SmartBag.xcodeproj`.
2. Xcode > Settings > Accounts: add your Apple ID if it isn't there.
3. Select the SmartBag target > Signing & Capabilities > Team: pick your (Personal Team) account.
   If Xcode says the bundle identifier is unavailable, change `space.mana.smartbag` to something unique
   such as `space.mana.smartbag.scott`.
4. Plug in the iPhone (iOS 16+), select it as the run destination, press Run.
   - First time only: on the phone enable Settings > Privacy & Security > Developer Mode (it restarts).
   - If iOS says "Untrusted Developer": Settings > General > VPN & Device Management > your Apple ID > Trust.
5. Free-team signing lasts 7 days; after that, press Run again from Xcode to re-sign.

In the app (1.2): Create account (name, email, password; or "I already have an account"), then setup: one screen per permission
(Allow Bluetooth, Allow Location "While Using App" with Precise on, accept "Change to Always Allow" later; Allow Notifications or Not now),
Pair your bag (tap your bag in "Bags nearby", check that its light blinks, name it; or "No bag with you? Use a demo bag"),
Add a card ("Use demo card" fills 4242 4242 4242 4242, 12/29, 123). Then the tabs: Shop · Trips · Wallet · Account.
Logging in on another phone asks only for what's missing (permissions on that phone, a bag or a card if the account has none).

Demo without hardware (hidden): hold the bag chip (top right) for 1 s to drop a random item;
hold the store name for 1.5 s to simulate walking out (both are also VoiceOver custom actions) (two accurate fixes 300 m north, real GPS paused for 60 s),
which charges the bag, shows the receipt with its exit-pass QR, sends `PAID <code>` to the bag and posts a notification.

## How it works

| File | Role |
|---|---|
| `SmartBag/Core/SpacetimeClient.swift` | Pure Foundation. BSATN codec, CartState/Receipt models, frame encode/decode (CallProcedure tag 4, ProcedureResult tag 7), identity + socket tokens, one persistent socket with request-id matching, 20 s call timeout, pings, reconnect on next call, one retry only when a frame never left the phone. Transport is pluggable (`WebSocketTransport`). |
| `SmartBag/Core/BagProtocol.swift` | Bag line parser (`1005`, `+1005@UID`, `-1005`, `DEL 1005`, JSON) and the acks the phone writes back. |
| `SmartBag/Model/ShopModel.swift` | App-wide session: serial op queue (bag lines reach the database strictly in order), store state, receipt detection (`lastReceipt.orderId` above the persisted last-seen id), demo aids. |
| `SmartBag/Model/BagLink.swift` | CoreBluetooth central: scans for NUS `6e400001-…`, notifies on TX `…0003`, writes acks to RX `…0002`, reassembles lines across packets, auto-reconnects, state restoration. |
| `SmartBag/Model/PhoneLocation.swift` | Continuous best-accuracy updates that keep running in the background; asks While Using, then Always; temporary full accuracy if Precise is off. |
| `SmartBag/Model/Notify.swift` | "Paid $X at Store" local notification (also shown in the foreground). |
| `SmartBag/Model/KeychainTokenStore.swift` | SpacetimeDB identity token in the Keychain (AfterFirstUnlock, so it works while locked). |
| `SmartBag/Core/Cards.swift` | Pure Foundation. Card brand from prefix, Luhn, lengths, MM/YY, CVC, typing aids. Produces a `CardDraft` (brand, last four, expiry): the only card data that leaves the phone. |
| `SmartBag/Model/AccountModel.swift` | Account on this phone (Foundation only, runs on Linux): sign up / log in / out / delete, wallet, bags, trips, setup steps, cached AccountState (never the password). Tells ShopModel which bags are paired and when the card changes. |
| `SmartBag/Views/*` | 1.1 design (spec: Firebase `apps/smart-bag/app/mobile-design/ios`). Theme (Bag It tokens, slot mark, wordmark, pills), Welcome + one priming screen per permission, Shop (hero by store state, newest-first bag list, walk-out dock whose bag mark mirrors the bag's light), Receipt (peak + end, exit pass QR `SMARTBAG-EXIT:code:id:total`). |
| `project.yml` | XcodeGen spec; `SmartBag.xcodeproj` was generated from it. |
| `tools/verify-linux.sh`, `Tests/` | Linux/macOS verification (below). |

Reporting cadence: inside a store a fix is sent when the phone moved more than 10 m or every 8 s; outside every 20 s.
The database counts the walk-out after two accurate (≤ 75 m) fixes beyond radius + 40 m + accuracy.

## Verification

`tools/verify-linux.sh` (Swift 5.9+; any OS) compiles the non-UI code with `swiftc` and runs:
1. offline codec checks, including the CallProcedure bytes compared with an independent Python `struct.pack` encoding;
2. a live trip with the real client over the real `URLSessionWebSocketTask` transport against `smartcart-5dt35`
   (enter Bayside Grocer at 15 m, `+1005@K1` twice (deduped) and `+1008@K2`, then fixes 260 m and 290 m north at 8 m accuracy:
   expects the store to clear and a paid receipt of $11.18, plus concurrent calls, server errors and reconnects);
3. `ShopModel.swift` itself (verbatim, minus Apple imports) driven against stubs of CoreLocation/BLE through a full trip:
   ordered bag lines and acks, Remove, demo walk-out, receipt, `PAID` to the bag, notification, no duplicate receipt.

On Linux, `URLSessionWebSocketTask` needs a libcurl built with WebSockets (Ubuntu 24.04's curl 8.5 is not); the script
uses `/tmp/curl-ws/lib` when present (`./configure --with-openssl --enable-websockets` on curl 8.10).

SwiftUI, CoreBluetooth, CoreLocation, UserNotifications and Security cannot be compiled on Linux, so the views, BagLink,
PhoneLocation, Notify and the Keychain store were reviewed but not compiled. Expect Xcode to be the first compiler they meet.

## Caveats

- iOS is stricter than Android about background work. Location keeps the app running while updates are active
  (blue location pill), and that is what lets the walk-out fire with the phone locked. If iOS terminates the app
  (memory pressure, or the user swipes it away), only "Always" location (significant-change relaunch) or a Bluetooth
  event from the bag (state restoration) brings it back, and the first fixes after a relaunch can be coarse.
  Keep SmartBag open in the background during a demo, and don't swipe it away.
- Approximate location (Precise off) can't identify the store; the app shows a hint and asks for temporary full accuracy.
- Not yet run on a physical iPhone or bag hardware.

## 1.2: accounts, wallet, pairing, tabs

- Signed out: Welcome ('The bag that checks you out.') -> Create account / Log in. No forgot-password (no email service).
  Field errors under the field, the module's own error text in red above the button (the "fatal error" wrapper is stripped).
- Setup after sign-up / log-in (3-segment step bar): permissions (only if this phone hasn't granted them), Pair your bag
  (skippable: "Set up later"), Add a card (skippable: "Add later"; with no card a walk-out is "payment due").
- Tabs: Shop (the approved 1.1 screen; "Paying with <card>" opens the card picker; no card -> "Add card"; no bag paired -> the
  chip and dock say "Pair your bag"), Trips (every receipt of the account, by day), Wallet (default card, other cards,
  pay with / remove with inline confirmation), Account (bags with Connected / Not nearby, rename / unpair, Pair a bag,
  permissions with their state, Sign out, Delete account, version).
- Pairing (firmware v2): every bag advertises SmartBag-XXXX; the phone lists nearby bags with signal words, connects to the
  tapped one and writes `HELLO`; the bag blinks three times and answers `BAG <name> fw2`. Lines starting with `BAG ` are
  control lines and never reach `shop_bag_line`. After pairing, Bluetooth connects only to the account's bags (any bag
  while none is paired). The demo bag (`DEMO`) counts as connected without Bluetooth; the hidden demo gestures still work.
- The first-run demo payment is gone: payment always comes from the wallet (the module copies the default card's label
  onto this phone). Card number and CVC are checked on the phone and cleared after submit; only brand, last four, expiry
  and nickname are sent. Payments are simulated.
- Cached for a fast signed-in launch: AccountState (name, email, card brands/last four, bags) in UserDefaults; the
  identity token stays in the Keychain as before. Signing out clears the cache, stops the shop and hides the previous
  shopper's Last trip on this phone.
- Verification: `tools/verify-linux.sh` now also runs account codec tests (including bytes recorded from the live module
  in `Tests/Fixtures`; refresh with `SMARTBAG_CAPTURE=1`), card checks, pairing helpers, a live account flow
  (sign up, demo card, pair, walk-out charged to the card, trips, second card as default, second phone, sign out,
  delete) and drives the real `AccountModel.swift` with `ShopModel.swift` through sign-up -> setup -> trip -> relaunch ->
  sign-out -> delete. Renders of every new screen (HTML mirror, light + dark, large text for sign-up and wallet):
  `python3 design/render_reference.py OUT --1.2`, PNGs in Mana Storage `smartbag/ios-ui/1.2/`.

## 1.1 design (Bag It)

- Name on the home screen and in copy: **Bag It**. `SmartBag` remains only in machine ids (bundle id, BLE name, repo paths).
- Visual system: zinc neutrals, ink pills, one coral (#E8654A) that means only the bag's light (the slot in the mark, the item flash, the leaving line); green/red only for real status. Four Dynamic Type sizes (34/22/17/13), two weights, SF Mono for money and codes. Light and dark.
- The bag mark in the dock is a live copy of the bag's LED: it blinks when an item is accepted and breathes while you're leaving. On the receipt its slot lights at the moment of the charge (the bag gets `PAID` at the same time), with the success haptic.
- `design/render_reference.py` renders an HTML mirror of each key screen (NOT a build) for design review; screenshots in Mana Storage `smartbag/ios-ui/`.
- 1.1 has not been compiled by Xcode yet: SwiftUI can't be built on Linux. Every Swift file passes `swiftc -parse`, and `tools/verify-linux.sh` (core client + ShopModel live trip) passes. Expect possible small compile fixes on the first Xcode build.

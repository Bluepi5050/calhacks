# Bag It for Android (SmartBag 3.6)

The app is called **Bag It** on the phone (launcher, notifications, in-app wordmark); `SmartBag` stays only in machine ids (package `space.mana.smartbag`, BLE name `SmartBag-01`, database). Native customer app (Kotlin, Jetpack Compose, minSdk 26). Customer-only: no store picker, catalog or admin controls.

- **`Api.kt`**: a hand-written client for SpacetimeDB's v2 binary WebSocket protocol (BSATN). One persistent socket; each action is a `CallProcedure` that returns the shopper's freshly priced bag. No HTTP per action, no subscriptions.
- **`BagLink.kt`**: BLE central for the bag (Nordic UART Service), auto-connect, MTU 185, ordered ack queue.
- **`ShopModel.kt`**: app-wide session; bag lines and fixes go to the database strictly in order.
- **`WatchService.kt`**: foreground service (location + connected device) so walking out is detected with the phone in a pocket.
- **`UI.kt`** (3.1 redesign): the Bag It design system (zinc neutrals, one coral accent that only means the bag's light / money moving, ink pill buttons, hairlines, 4 type sizes / 2 weights, tabular figures) and every screen: Welcome with per-permission recovery (incl. Approximate location), one state-driven shop screen (finding store, location off, offline, not in a store + nearest store + last trip, bag problems, empty, filling, not sold here, leaving), and the receipt. No Pay button: a coral line on the bottom bar is half drawn while you leave and completes on the receipt when the database charges, then the slot of the bag mark lights with a confirm haptic. Dark theme, TalkBack labels and announcements, 200% font layouts, reduced motion.

- **3.6:** after a walk-out the bag gets `PAID <code>` or, for payment due, `DUE <code>` (bag firmware v3: three slow blinks), sent only while it is connected. The shopper copy matches the canonical list (`mobile-design/copy`). MTU: wait 600 ms after connecting, use an MTU Android already reported, otherwise ask, and ask again after discovery. Android 14+ may continue on its own 517 exchange, and any 20-byte notification then counts as cut. Older Android reconnects instead of guessing. A STATUS without its device name is rejected.
- **3.5: contract v1.2.** Lost session (cart restart, power loss, stale stop): `known` = max(backend `lastSeq`, highest seq received); lost = STATUS not `S|sid|ACTIVE` or behind `known`, checked on every reconcile and STATUS; then drain the old sid's queue, `shop_session_rekey`, persist and `START` the new sid. `ERR|BUSY` waits for the reconnect; GATT service-changed is a disconnect; `shop_summary(0)` after every walk-out; `stale_session`/`no_session` in a store → `shop_locate` → `shop_session_state` → `START`.
- **3.4: SmartCart (Jetson cart).** Implements `docs/SMARTCART-BLE.md` v1.1. `SmartCart.kt`: the message codec (`CartProtocol`), the phone's state machine (`CartSync`: reconcile on every connect/STATUS, events only after `OK|START|<sid>`, one backend call per (sid, seq) in order through a persisted queue, ACK only after the backend answered, re-ACK of resent events, STOP on walk-out, 5 s watchdog), a one-at-a-time GATT queue and `MockCart`, a stand-in that answers like the Jetson. `CartLink.kt`: the Android GATT central (scan by service UUID, MTU 247 needing at least 64, STATUS then EVENTS subscriptions one at a time, STATUS read, CONTROL writes with response; any GATT failure reconnects with backoff). It runs alongside the bag's NUS link: HELLO/BAG pairing is unchanged and carts appear in "Bags nearby" as "Cart XXXX", confirmed by the name in STATUS. In the app: a budget (dock line, card picker, Account; red only when over), tap a bag row for − / + and "Change item", up to 3 quiet suggestions, the trip summary on the receipt (and after a visit with nothing to pay), "Reconnecting…", and a hidden Hardware test screen (long-press the version in Account: STATUS, START/STOP/SYNC, log, simulated cart).
- **3.3: notifications.** Local, no keys needed (`Notify.kt`): receipt when a walk-out is charged on this phone ("Paid $11.18 at Bayside Grocer"; payment due says nothing was charged and to show the exit code to store staff, because there is no in-app settling), store arrival once per visit, and a bag alert after 20 s disconnected in a store with items. Channels: Trips and receipts (high), Store arrivals, Bag alerts (high), Account security (high), Shopping session (low). Never while Bag It is on screen. Tapping a receipt notification opens that receipt. Account › Notifications: one switch per category, notification permission status, "Location all the time" status, and a test notification.
- **Background (`Geofences.kt`):** with "Allow all the time", Bag It registers geofences around the 20 nearest stores (`shop_nearby_stores`); entering or leaving one starts the shopping session, so the walk-out can be charged with the app closed. Re-registered after reboot and app updates. Honest limits: Android can delay geofences, and after a force-stop nothing runs until the app is opened again.
- **Server push (optional):** for trips charged on your other phones and new sign-ins. Firebase Cloud Messaging is compiled in only when `app/google-services.json` exists (never committed; see `.gitignore`); without it the build works and the app relies on local notifications. Receipts are de-duplicated by exit code between local and server notifications.
- **3.2: accounts and pages.** Signed out: Welcome, Create account, Log in (`AuthUI.kt`). After sign-up, setup steps with a step bar: permissions, **Pair your bag** (`PairUI.kt`: live list of nearby bags with signal words, the bag blinks when the phone says `HELLO` and the screen's mark blinks with it, name it; or use a demo bag), **Add a card** (`WalletUI.kt`). Then four tabs (`TabsUI.kt`): Shop (the approved screen; the dock's card line opens a card picker, no card / no bag states), Trips (receipts by day), Wallet (several cards, default, remove), Account (bags, permissions, sign out, delete account). The walk-out receipt still completes the coral line in place, then settles to the 3.1 bar height.
- **Card data:** the card number and security code are checked on the phone (`Cards.kt`: brand, Luhn, length, expiry, CVC) and never sent or saved; only brand, last four digits, expiry and nickname go to the database (`card_add`). Passwords go only over the TLS socket and are never stored; the phone keeps its identity token plus a cached account state. Card and login screens use `FLAG_SECURE`; app backup is off.
- **Bag pairing protocol:** lines from the bag starting with `BAG ` (e.g. `BAG SmartBag-3F2A fw2`, the reply to `HELLO`) are control lines and never reach `shop_bag_line`. Auto-connect only accepts the account's paired bags (any bag when none are paired).

Hidden demo aids: long-press the bag status to drop in a random item; long-press the store name to simulate walking out.

## Build
Open `android/` in Android Studio, or headless:
```bash
tools/setup.sh   # JDK 17, Android SDK 34, Gradle 8.7 (Linux)
tools/build.sh   # -> android/app/build/outputs/apk/release/app-release.apk
```
Point at another database with `-PstdbHost=https://… -PstdbDb=…`.

## Screenshots
`app/src/test/.../ScreenshotTest.kt` renders every key state with Robolectric native graphics + Roborazzi (no emulator) at 411/360 dp, font scale 1.0/2.0, light and dark:
```bash
./gradlew testDebugUnitTest --tests '*ScreenshotTest*' -PshotsDir=/tmp/shots
```

## Test
- `WalkOutTest.kt` runs the real `Api` client against the live database through a full trip (enter, dedupe a tag, walk out, check the charge).
- `AccountFlowTest.kt` (live): sign up a fresh test account, add the demo card, pair a bag, walk out of Bayside Grocer charged to the card, Trips, second card + default switch, sign out, sign in, delete. Every AccountState / Receipt[] reply is decoded and re-encoded byte for byte.
- `CardsTest.kt` (offline): card validation, labels, error cleaning, control lines, setup steps, codecs.

`./gradlew testDebugUnitTest`

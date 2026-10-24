# SmartBag for Android

Native customer app (Kotlin, Jetpack Compose, minSdk 26). Customer-only: no store picker, catalog or admin controls.

- **`Api.kt`**: a hand-written client for SpacetimeDB's v2 binary WebSocket protocol (BSATN). One persistent socket; each action is a `CallProcedure` that returns the shopper's freshly priced bag. No HTTP per action, no subscriptions.
- **`BagLink.kt`**: BLE central for the bag (Nordic UART Service), auto-connect, MTU 185, ordered ack queue.
- **`ShopModel.kt`**: app-wide session; bag lines and fixes go to the database strictly in order.
- **`WatchService.kt`**: foreground service (location + connected device) so walking out is detected with the phone in a pocket.
- **UI**: no Pay button. "Just walk out" bar, "Leaving…" state, then a receipt with a QR code and a notification once the database has charged the bag.

Hidden demo aids: long-press the bag status to drop in a random item; long-press the store name to simulate walking out.

## Build
Open `android/` in Android Studio, or headless:
```bash
tools/setup.sh   # JDK 17, Android SDK 34, Gradle 8.7 (Linux)
tools/build.sh   # -> android/app/build/outputs/apk/release/app-release.apk
```
Point at another database with `-PstdbHost=https://… -PstdbDb=…`.

## Test
`app/src/test/.../WalkOutTest.kt` runs the real `Api` client against the live database through a full trip (enter, dedupe a tag, walk out, check the charge): `./gradlew testReleaseUnitTest`.

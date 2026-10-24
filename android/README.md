# Bag It for Android (SmartBag 3.1)

The app is called **Bag It** on the phone (launcher, notifications, in-app wordmark); `SmartBag` stays only in machine ids (package `space.mana.smartbag`, BLE name `SmartBag-01`, database). Native customer app (Kotlin, Jetpack Compose, minSdk 26). Customer-only: no store picker, catalog or admin controls.

- **`Api.kt`**: a hand-written client for SpacetimeDB's v2 binary WebSocket protocol (BSATN). One persistent socket; each action is a `CallProcedure` that returns the shopper's freshly priced bag. No HTTP per action, no subscriptions.
- **`BagLink.kt`**: BLE central for the bag (Nordic UART Service), auto-connect, MTU 185, ordered ack queue.
- **`ShopModel.kt`**: app-wide session; bag lines and fixes go to the database strictly in order.
- **`WatchService.kt`**: foreground service (location + connected device) so walking out is detected with the phone in a pocket.
- **`UI.kt`** (3.1 redesign): the Bag It design system (zinc neutrals, one coral accent that only means the bag's light / money moving, ink pill buttons, hairlines, 4 type sizes / 2 weights, tabular figures) and every screen: Welcome with per-permission recovery (incl. Approximate location), one state-driven shop screen (finding store, location off, offline, not in a store + nearest store + last trip, bag problems, empty, filling, not sold here, leaving), and the receipt. No Pay button: a coral line on the bottom bar is half drawn while you leave and completes on the receipt when the database charges, then the slot of the bag mark lights with a confirm haptic. Dark theme, TalkBack labels and announcements, 200% font layouts, reduced motion.

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
`app/src/test/.../WalkOutTest.kt` runs the real `Api` client against the live database through a full trip (enter, dedupe a tag, walk out, check the charge): `./gradlew testReleaseUnitTest`.

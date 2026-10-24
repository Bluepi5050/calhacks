# bagitwith.us

Static site for Bag It. No build step, no cookies, no analytics, no third-party requests, system fonts only.

```
index.html          the page (inline CSS + SVG, small script: video play/pause, Android download button)
media/screens/      phone screens for the Get the app preview, iPhone and Android (see Screenshots below)
media/steps/        demo step stills cut from the recorded trip
tools/              make-screens.py (screen gallery page list) and make-demo-steps.py (not deployed)
brand/              logo, mark, favicon, touch icon, OG image, brand.md
media/              product film + app demo recordings (see media/README.md)
downloads/          bag-it.apk (Android app; stable name, replace the file to update)
DESIGN-SYSTEM.md    design system and decisions
_headers            headers for Cloudflare Pages / Netlify (APK type, video caching)
```

Deploy: Netlify (connect this folder or branch) or Cloudflare Pages (upload the folder), add the domain bagitwith.us, then enter the DNS records the host shows at the registrar. The host must support HTTP range requests for MP4 (both do).
The QR code encodes https://bagitwith.us/downloads/bag-it.apk. The name is stable, so ship new versions by replacing the file and the version line.

Content rules: only true statements. Payments are simulated during early access; product images are illustrative; no prices, dates, store partners, testimonials or ratings until they are real.

## Media
- `media/bagit-concept*` (16:9 and 4:5 below 600 px): 13.25 s product film edited with ffmpeg from AI-generated stills (one reference image keeps the bag consistent); captioned; "Product images are illustrative." on the page.
- `media/bagit-live-trip*` (16:9 from a 1920×1080 recording, 720×1160 portrait): 14.7 s recording of one real trip (receipt FXEH7X, $11.77) on the live service from the shopper's phone; a browser page mirroring the app's screens drove it with simulated location and items; demo payment.
- Sources (stills, recording page, full 31 s film): /workspace/smartbag/film (mirrored to /cloudspace/smartbag/film).

## Deploy bundle
Publish only the files in the ZIP built from this folder: index.html, 404.html, _headers, _redirects, brand/{favicon.svg,favicon-32.png,apple-touch-icon.png,og-image.jpg,logo.svg,mark.svg}, media/* (including media/screens/*.webp), downloads/*.apk. `_redirects` hides the internal docs if the whole folder is deployed.

## Page structure
Everything is visible while scrolling top to bottom; nothing is paged, scroll-linked or collapsed: hero (product film) → How it works (3 beats) → Demo (5 trip steps side by side, then the recorded video) → Get the app (screen gallery + Android | iPhone selector + panel) → FAQ (questions and answers all open) → footer. The header is sticky with a solid background and hairline; anchored jumps land below it via `scroll-padding-top` on `html` (the only scroll offset).

## Demo steps
The step row in #demo shows five stills cut from the recorded trip (`media/steps/trip-*.webp`, phone screen only, 420 px wide, lazy-loaded). `tools/make-demo-steps.py` lists the frame times and the crop; labels and one-line descriptions are in index.html. If the recording changes, re-time `STEPS` and rerun.

## Screenshots (swap point)
"Get the app" shows every app screen at once in a gallery (one row of up to 6 on wide screens, rows of 3 on tablets, 2 columns on phones; more pages wrap into balanced rows), with the Android | iPhone selector below it. The selector swaps which platform's gallery is shown (heading "Android app" / "iPhone app") and the panel under it (Android: download + QR; iPhone: not available yet). Default platform: iPhone for iOS user agents and links to `#get-iphone`, otherwise Android. Without JavaScript: the Android gallery and both panels.

- **One page list:** `PAGES` at the top of `tools/make-screens.py` (id, caption label, platform-neutral alt text, Android render, iPhone render, optional dark render per platform). Run `python3 tools/make-screens.py`. It writes `media/screens/<android|ios>-<id>[-dark].webp` (560 px wide, about 20 KB), regenerates both galleries in index.html (between the `<!-- GALLERY -->` markers; first row of the Android gallery eager, everything else lazy, width/height set) and deletes stale files. Order of the list = order on the page.
- **Adding pages:** append entries with a render for BOTH platforms. A page that exists on only one platform can't be in the gallery. Current list (9): Welcome, Create account, Log in, Pair your bag, Shopping, Receipt, Trips, Wallet, Account (5 + 4 on wide screens, 3 × 3 on tablets, 2 columns on phones).
- **Dark mode:** both platforms have a dark render for every page, so the gallery follows the visitor's theme. Give a platform dark renders for every page or none, so the gallery never mixes.
- Android renders have a placeholder status-bar clock in the top-left corner; the tool blanks that strip (`BLANK_TOP`) because the frame's rounded corner would cut it.
- Current sources: Android 3.2 renders (`/cloudspace/smartbag/android-ui/3.2/411-light|411-dark`); iPhone 1.2 reference renders (`/cloudspace/smartbag/ios-ui/1.2/`, HTML mirror, not a compiled build) plus the 1.1 receipt (`ios-ui/final/13-receipt-paid`, `16-receipt-dark`; no 1.2 receipt render). The iPhone gallery heading says the screens are previews of the app in development; change that once screens come from a real build. The Log in screens show the wrong-password message on both platforms (Android has no clean Log in render).

## iPhone availability
There is no App Store or TestFlight build: the iPhone panel says "The iPhone app isn’t available to download yet. It’s coming to the App Store." with no badge or link. When the app is live, replace the `<p class="plain">` in the `#get-iphone` panel with the App Store link, and update the hero fine print, the meta description, the FAQ "Which phones does it work with?" and the iPhone script branch at the bottom of index.html.

## Accounts, Wallet and pairing (live in Android 3.2 and iPhone 1.2 source)
The page describes them: setup line under Get the app, FAQ "Do I need an account?", "How do I connect my bag?", "Will I be charged?" (Wallet, simulated payments, only type/last four/expiry kept) and the location/privacy answer (trips and approximate location are public test data; email, password and saved cards private). Push notifications are still being built: don't mention receipt notifications until they ship.

## Android download
`downloads/bag-it.apk` is Android 3.2 (versionCode 5, minSdk 26 = Android 8.0, 6.8 MB). To update: replace the file (same name; the QR stays valid) and the version line in the Android panel.

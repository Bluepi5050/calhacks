# bagitwith.us

Static site for Bag It. No build step, no cookies, no analytics, no third-party requests, system fonts only.

```
index.html          the page (inline CSS + SVG, small script: video play/pause, Android download button)
media/screens/      phone screens for the Get the app preview, iPhone and Android (see Screenshots below)
tools/              make-screens.py: the page list; rebuilds media/screens and the PAGES array (not deployed)
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

## Screenshots (swap point)
"Get the app" is one phone preview plus a platform selector. Page tabs above the phone (and the arrows, swipe, arrow keys) browse the app's screens; the Android | iPhone selector below picks the platform, which drives both the phone (same page, other platform) and the panel under it (Android: download + QR; iPhone: not available yet). Default platform: iPhone for iOS user agents and for links to `#get-iphone`, otherwise Android. Without JavaScript the page shows the first screen (Android) and both panels.

- **One page list:** `PAGES` at the top of `tools/make-screens.py` (id, tab label, platform-neutral alt text, Android render, iPhone render, optional dark render per platform) plus `START` (first page shown). Run `python3 tools/make-screens.py`. It writes `media/screens/<android|ios>-<id>[-dark].webp` (560 px wide, about 20 KB), regenerates the `PAGES` data array in index.html (between `/* PAGES (generated` and `/* /PAGES */`) and the no-JavaScript first screen (between `<!-- FIRST SCREEN -->` markers), and deletes stale files. Order of the list = order of the tabs.
- **Adding pages** (Sign up, Shop, Trips, Wallet, Account, Pair your bag when Android 3.2 / iPhone 1.2 land): append entries with a render for BOTH platforms. A page that exists on only one platform can't be in the tour.
- **Dark mode:** Android has a full dark set, so its screens follow the visitor's theme. iPhone dark renders exist for only two pages, so the iPhone tour stays light in both themes rather than switching mid-tour; add `ios` dark renders when a full set exists.
- Android renders have a placeholder status-bar clock in the top-left corner; the tool blanks that strip (`BLANK_TOP`) because the phone frame's rounded corner would cut it.
- Current sources: iPhone 1.1 reference renders (`/cloudspace/smartbag/ios-ui/final/`, HTML mirror, so the page says "The iPhone screens are previews of the app in development." whenever iPhone is selected; remove that once screens come from a real build) and Android 3.1 renders (`/cloudspace/smartbag/android-ui/final/411-light|411-dark`).
- If the screens change size, keep `WIDTH`/`HEIGHT` in the tool and the `aspect-ratio` of `.screen` in index.html in step.

## iPhone availability
There is no App Store or TestFlight build: the iPhone panel says "The iPhone app isn’t available to download yet. It’s coming to the App Store." with no badge or link. When the app is live, replace the `<p class="plain">` in the `#get-iphone` panel with the App Store link, and update the hero fine print, the meta description, the FAQ "Which phones does it work with?" and the iPhone script branch at the bottom of index.html.

## When accounts, Wallet and pairing ship (prepared, not on the page yet)
These features are not in the released apps yet, so the page does not mention them. When they ship:
1. The FAQ "Why does the app need my location?" says "There's no account: the app uses an anonymous identity." Rewrite it.
2. The Android install note ("open the app near your bag and tap Connect bag") and "The app can't find my bag." should follow the new pairing screen's wording.
3. "Will I be charged?" says no card is ever entered. If the Wallet lets people save cards, say how they're used during early access (still simulated?).
4. Optional setup line, ready to paste under the Get the app intro (`.head`), only once all three steps are real in both apps:
   `<p class="label" style="margin-top:20px">Set up in a minute</p><ol class="steps">…</ol>` with three items: "Create your account", "Pair your bag", "Add a card". The `.steps` style from How it works can be reused.
5. Add the new screens to `PAGES` (above).

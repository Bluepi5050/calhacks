# bagitwith.us

Static site for Bag It. No build step, no cookies, no analytics, no third-party requests, system fonts only.

```
index.html          the page (inline CSS + SVG, small script: video play/pause, Android download button)
media/screens/      phone screenshots, iPhone and Android (see Screenshots below)
tools/              make-screens.py: rebuilds media/screens from app renders (not deployed)
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
"Get the app" shows the iPhone and Android apps as equals, each with the same two screens (Shopping, Receipt) so they can be compared. Light and dark versions switch with the visitor's theme.

- Files: `media/screens/{android,ios}-{shop,receipt}[-dark].webp`, 480 px wide (about 2x display size), about 20 KB each, lazy-loaded.
- Source list: `SCREENS` at the top of `tools/make-screens.py` (one source PNG per slot). To swap: point the slots at the new renders and run `python3 tools/make-screens.py`. File names stay the same, so index.html needs no change unless:
  - a screen's aspect ratio changes: update `width`/`height` on its `<img>`;
  - a screen shows something else: update its `alt` text and caption.
- In index.html every screenshot sits between `<!-- SCREENS (swap point) -->` and `<!-- /SCREENS -->` (one block per platform). To show a different state, change both platforms' blocks together.
- Current sources: iPhone 1.1 reference renders (`/cloudspace/smartbag/ios-ui/final/11-filling, 13-receipt-paid, 15-filling-dark, 16-receipt-dark`), Android 3.1 renders (`/cloudspace/smartbag/android-ui/final/411-{light,dark}/09-filling, 11-receipt-paid`). The iPhone screens are previews, so the page says "The iPhone screens are previews of the app in development." under the platforms. Remove that sentence once the iPhone screens come from a real build.

## iPhone availability
There is no App Store or TestFlight build: the page says "Coming soon to the App Store" with no badge or link. When the app is live, replace the `<p class="soon">` in `#get-iphone` with the App Store link, and update the hero fine print, the meta description, the FAQ "Which phones does it work with?" and the iPhone script branch at the bottom of index.html.

## When accounts, Wallet and pairing ship (prepared, not on the page yet)
These features are not in the released apps yet, so the page does not mention them. When they ship:
1. The FAQ "Why does the app need my location?" says "There's no account: the app uses an anonymous identity." Rewrite it.
2. The Android install note ("open the app near your bag and tap Connect bag") and "The app can't find my bag." should follow the new pairing screen's wording.
3. "Will I be charged?" says no card is ever entered. If the Wallet lets people save cards, say how they're used during early access (still simulated?).
4. Optional setup line, ready to paste under the Get the app intro (`.head`), only once all three steps are real in both apps:
   `<p class="label" style="margin-top:20px">Set up in a minute</p><ol class="steps">…</ol>` with three items: "Create your account", "Pair your bag", "Add a card". The `.steps` style from How it works can be reused.
5. Swap the screenshots (above) for the new screens.

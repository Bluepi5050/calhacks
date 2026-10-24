# bagitwith.us

Static site for Bag It. No build step, no cookies, no analytics, no third-party requests, system fonts only.

```
index.html          the page (inline CSS + SVG, small script: video play/pause, Android download button)
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
Publish only the files in the ZIP built from this folder: index.html, 404.html, _headers, _redirects, brand/{favicon.svg,favicon-32.png,apple-touch-icon.png,og-image.jpg,logo.svg,mark.svg}, media/*, downloads/*.apk. `_redirects` hides the internal docs if the whole folder is deployed.

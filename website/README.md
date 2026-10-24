# Bag It website (bagitwith.us)

Static, dependency-free product site for **Bag It**, the smart bag (Cal Hacks 13.0 prototype). Customer-only: product, how it works, prototype specs, app download, FAQ.

```
index.html                             the whole site (inline CSS + SVG, ~10 lines of JS: header hairline, Android/iPhone button labels)
brand/                                 logo, mark, favicon, apple-touch icon, OG image, brand.md, explorations/
images/                                drop real product photos here (see images/README.md)
downloads/SmartBag-3.0.apk             Android app 3.0
downloads/SmartBag-iOS-source-1.0.zip  iPhone source (build with Xcode; no App Store/TestFlight release)
downloads/SmartBag-source-3.0.zip      Android source (not linked from the page; kept for direct links)
_headers                               optional headers for Cloudflare Pages / Netlify (APK MIME type)
```

No build step, no cookies, no analytics, no third-party requests, system fonts only. Links are relative.
The QR code encodes `https://bagitwith.us/downloads/SmartBag-3.0.apk`, so it works once the site is live on that domain.

## Real product photos
The hero is a hand-drawn SVG (`<svg class="bag">`). To swap in a photo, follow `images/README.md`: one element to replace, marked with a `PRODUCT IMAGE` comment in index.html.

## Deploy (pick one)
**Cloudflare Pages:** Workers & Pages > Create > Pages > *Upload assets*, drag this folder, deploy; then Custom domains > `bagitwith.us`.
**Netlify:** Add new site > *Deploy manually* (or connect the GitHub `website` branch, publish directory `website`), then Domain management > `bagitwith.us`.

## Custom domain at Porkbun
After adding `bagitwith.us` in the host's dashboard, enter the DNS records (or nameservers) that dashboard shows in Porkbun > Domain Management > bagitwith.us > DNS. HTTPS is issued automatically once DNS resolves.

## Content rules
Only true product facts. Payments are simulated, the prototype database is publicly readable (test data only), iPhone is a source build only. No size, weight, battery, price, shipping or customer claims until they are real. No operator codes, tokens or shopper data on this site.

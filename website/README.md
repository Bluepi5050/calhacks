# Bag It website (bagitwith.us)

Static, dependency-free public site for **Bag It: SmartBag device + app** (Cal Hacks 13.0 prototype).

```
index.html                          the whole site (inline CSS, inline SVG, ~20 lines of JS for the mobile menu)
downloads/SmartBag-3.0.apk          Android app 3.0
downloads/SmartBag-source-3.0.zip   Android source
downloads/SmartBag-iOS-source-1.0.zip  iPhone source (build with Xcode; no App Store/TestFlight release)
_headers                            optional headers for Cloudflare Pages / Netlify (APK MIME type, caching)
```

No build step, no cookies, no analytics, no third-party requests. Links are relative, so it works from any host or subfolder.

## Deploy (pick one)

**Cloudflare Pages:** Workers & Pages > Create > Pages > *Upload assets*, drag this folder (or the ZIP contents), deploy.
Then Custom domains > Set up a domain > `bagitwith.us` (and `www.bagitwith.us` if wanted).

**Netlify:** Add new site > *Deploy manually*, drag this folder. Then Domain management > Add a domain > `bagitwith.us`.

Note: Cloudflare Pages limits single files to 25 MiB and Netlify drag-and-drop deploys handle the APK (about 6.6 MB) fine.

## Custom domain at Porkbun

The domain is registered at Porkbun. After adding `bagitwith.us` in the hosting dashboard, that dashboard shows the
exact DNS records (or nameservers) it needs. Enter those values in Porkbun > Domain Management > bagitwith.us > DNS
(or change nameservers, if the host asks for that). Do not copy records from guides; use the ones your host displays.
HTTPS certificates are issued automatically by either host once DNS resolves.

## Updating

Edit `index.html`, replace files in `downloads/` when a new build ships (keep the file names referenced in
`index.html` in sync), commit, and re-upload.

Content rules: payments are simulated, the prototype database is publicly readable (test data only), and no operator
codes, tokens or shopper data belong on this site.

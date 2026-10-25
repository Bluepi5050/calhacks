# SmartBag store console

Live dashboard for store staff: who is shopping, what's in each bag (priced live), the bag event feed, walk-out orders, and catalog/store editing. Built with the official SpacetimeDB TypeScript SDK.

**Scales to thousands of stores:** the console subscribes to the store list plus the rows of *one* selected store (`product`, `shopper`, `cart_item`, `bag_event` by `store_id`; `shop_order`/`order_line` by `store_id` and UTC day). Switching stores swaps the subscription. It reconnects with backoff if the socket drops.

- `src/SmartBagConsole.jsx`: the console UI. It runs as a [Mana](https://mana.space) app (React component with Mana's injected `FS`, `useTheme` and `relPathAddress`); the SpacetimeDB parts are plain SDK code.
- `entry.ts` + `bindings/`: generated client bindings, bundled by `build.sh` into `dist/stdb-client.mjs`.
- `bench.mjs`: WebSocket reducer throughput benchmark (`node bench.mjs <connections> <calls-each>`).

## Sign-in and teams (v2, 2026-10-24)

- **Accounts:** SpacetimeAuth (OIDC authorization code + PKCE). The signed-in person's SpacetimeDB identity is derived from the token's issuer and subject, so it's the same on every device. Until SpacetimeAuth is enabled for the database, the owner's console runs as an admin and visitors get a persistent guest identity.
- **Roles enforced by the module:** owner (opened the store: delete, reset, team, join code), staff (catalog, settings, invite), admin. Email invites need the invitee to accept.
- **Live view:** the walk-out handoff. A leaving shopper's card turns into its receipt in the same slot, because the module empties the bag and writes the order in one transaction. The receipt shows the measured commit-to-screen time, corrected for clock offset. "Run a demo shopper" runs a full trip from a second connection in the browser.
- **Scripts:** `sim-phone.mjs` (shopper over reducers), `sim-proc.mjs` (shopper over the phone procedures), `test-roles.mjs` (two-identity permission test).

## Stripe charges (test mode)

`stripe-charger.mjs` turns every walk-out the database records as **paid** into a Stripe charge for the order total, in **test mode only** (live keys are refused). It watches `shop_order`, and for each new paid order creates and confirms a PaymentIntent with Stripe's test card for the order's card brand (`pm_card_visa` for Visa). The order id, exit code and store go in the charge's metadata, and the idempotency key `bagit-order-<id>-v2` stops double charges. Orders from before its first run are never charged; ones paid while it was stopped are charged when it starts. State: `.stripe-charger.json` (git-ignored).

```sh
npm install && npx esbuild entry.ts --bundle --format=esm --platform=browser --target=es2020 --minify --outfile=dist/stdb-client.mjs
node stripe-charger.mjs --dry-run                       # what it would charge, no key
STRIPE_SECRET_KEY=sk_test_... node stripe-charger.mjs   # real test-mode charges
```

The app only stores a card's brand and last four, so charges use Stripe's test cards, not the shopper's card. The receipt in the app doesn't show the Stripe charge yet; it's in the Stripe dashboard (Payments, test mode) and in this script's log.

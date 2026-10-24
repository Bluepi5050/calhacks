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

# SmartBag store console

Live dashboard for store staff: who is shopping, what's in each bag (priced live), the bag event feed, walk-out orders, and catalog/store editing. Built with the official SpacetimeDB TypeScript SDK.

**Scales to thousands of stores:** the console subscribes to the store list plus the rows of *one* selected store (`product`, `shopper`, `cart_item`, `bag_event` by `store_id`; `shop_order`/`order_line` by `store_id` and UTC day). Switching stores swaps the subscription. It reconnects with backoff if the socket drops.

- `src/SmartBagConsole.jsx`: the console UI. It runs as a [Mana](https://mana.space) app (React component with Mana's injected `FS`, `useTheme` and `relPathAddress`); the SpacetimeDB parts are plain SDK code.
- `entry.ts` + `bindings/`: generated client bindings, bundled by `build.sh` into `dist/stdb-client.mjs`.
- `bench.mjs`: WebSocket reducer throughput benchmark (`node bench.mjs <connections> <calls-each>`).

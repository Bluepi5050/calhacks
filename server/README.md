# SmartBag SpacetimeDB module

The entire SmartBag backend, as one SpacetimeDB module (TypeScript, SpacetimeDB 2.13). Live database: `smartcart-5dt35` on Maincloud.

## What runs in the database
- **Geofencing with a grid index.** Each store has a `cell` key (0.01° grid, btree index). A GPS fix examines only stores in the 3×3 cells around it, so lookup cost doesn't grow with the number of stores.
- **Bag protocol parsing.** `+1005`, `-1005`, `+1005@TAG` and JSON lines are parsed in the module; repeat reads of the same RFID tag are ignored.
- **Per-store pricing.** The bag is always priced with the catalog of the store the shopper is in.
- **Walk-out payment.** Store membership is sticky. Two accurate fixes (≤ 75 m accuracy) farther than radius + 40 m from the store, and the same transaction prices the bag, writes `shop_order` + `order_line`, charges the saved payment method (simulated) and empties the bag. There is no checkout call.
- **Multi-tenant stores.** `store_staff` and `store_invite` (private) give each store its own staff; global admins via `claim_operator`.
- **Scheduled cleanup** every 5 minutes trims the bag feed and idle shoppers.

## Phone API (procedures, each returns the shopper's priced `CartState`)
`shop_state()`, `shop_locate(lat, lng, accuracyM)`, `shop_bag_line(line)`, `shop_remove(sku)`, `shop_set_bag(name)`, `shop_set_payment(label)`. The same operations exist as reducers for SDK clients.

## Console API (reducers)
`pin_store`, `upsert_store`, `delete_store`, `upsert_product`, `delete_product`, `join_store`, `rotate_invite`, `claim_operator`, `reset_demo`; views `my_cart`, `my_shopper`, `my_orders`, `my_stores`.

## Publish
```bash
cd server/spacetimedb && npm install && cd ..
spacetime publish <database-name> --server maincloud --yes
# schema changes: add --delete-data=always (init re-seeds two demo stores and a new admin code)
```

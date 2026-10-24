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

## Accounts (v4, additive)

- Private tables: `account`, `member_name`, `staff_email_invite`, `activity`, `store_owner`. The public `profile` table is intentionally unused.
- `clientConnected` rejects SpacetimeAuth tokens issued for another client (the audience must be in config `auth_client_ids`, set with `set_auth_clients`).
- Reducers: `sign_in`, `set_display_name`, `invite_staff`/`revoke_staff_invite`/`accept_invite`/`decline_invite`, `remove_staff`. `delete_store`, `rotate_invite` and `reset_demo(storeId)` are owner-only.
- Procedure: `server_time`. Views (each scoped to the caller): `my_account`, `my_team`, `my_store_activity`, `my_store_people`, `my_invites`.
- Published with `--delete-data=never`. The shop_* phone API is unchanged.
- `tools/setup_auth.py` finishes the SpacetimeAuth setup once it has been enabled on the dashboard.


## Shopper accounts (v5)

Email + password accounts for the Bag It phone apps, stored in private tables (`shopper_account`, `shopper_login`, `payment_card`, `paired_bag`). Passwords are hashed with PBKDF2-HMAC-SHA256 (salted; 8 failed attempts lock the account for 15 minutes). A phone keeps its own SpacetimeDB identity; signing in links that identity to the account, so one account works on several phones and `shop_trips` returns the trips of every linked phone. Cards store only brand, last four digits, expiry and an optional nickname; the full number never reaches the database (payments are simulated). The default card's label is copied into each signed-in phone's `shopper.payment`, which is what a walk-out charges. Public `shopper` rows now keep only coarse (about 1 km) coordinates.

Procedures (all return `AccountState` unless noted): `account_state`, `account_sign_up(name, email, password)`, `account_sign_in(email, password)`, `account_sign_out`, `account_set_name(name)`, `account_delete(password)`, `card_add(brand, last4, exp_month, exp_year, nickname, make_default)`, `card_remove(id)`, `card_set_default(id)`, `bag_pair(bag_id, name)`, `bag_rename(id, name)`, `bag_unpair(id)`, `shop_trips(limit) -> Receipt[]` (newest first).

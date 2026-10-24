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

Email + password accounts for the Bag It phone apps, stored in private tables (`shopper_account`, `shopper_login`, `payment_card`, `paired_bag`). Passwords are hashed with PBKDF2-HMAC-SHA256 (salted; 8 failed attempts lock the account for 15 minutes). A phone keeps its own SpacetimeDB identity; signing in links that identity to the account, so one account works on several phones. Trip history follows durable order ownership (see v5.2), not which phones are linked right now. Cards store only brand, last four digits, expiry and an optional nickname; the full number never reaches the database (payments are simulated). A walk-out on a signed-in phone charges the account's default card, read from the wallet in the charging transaction (since v5.2 the public `shopper.payment` only says `Card on file`). Public `shopper` rows now keep only coarse (about 1 km) coordinates.

Procedures (all return `AccountState` unless noted): `account_state`, `account_sign_up(name, email, password)`, `account_sign_in(email, password)`, `account_sign_out`, `account_set_name(name)`, `account_delete(password)`, `card_add(brand, last4, exp_month, exp_year, nickname, make_default)`, `card_remove(id)`, `card_set_default(id)`, `bag_pair(bag_id, name)`, `bag_rename(id, name)`, `bag_unpair(id)`, `shop_trips(limit) -> Receipt[]` (newest first).


## Push notifications (v5.1)

The module sends push notifications itself: procedures make HTTPS calls to Firebase Cloud Messaging (HTTP v1), authenticated with a Google service-account JWT signed in `src/push_crypto.ts` (SHA-256 and RS256 in plain TypeScript; the module runtime has no crypto library). Phones register an FCM token with `push_register(platform, token, prefs)` after signing in. The module pushes what a phone cannot see itself: a trip charged on another phone of the same account, a new sign-in, a card added elsewhere. The phone that caused an event is excluded, because it shows its own local notification. Dead tokens are removed automatically. The OAuth access token is cached in the private `config` table. Sending is off until an admin calls `set_push_config(service_account_json)`. `push_test` reports the result (`sent 1/1`, `push not configured`, ...). `shop_nearby_stores(lat, lng, limit)` gives phones the store geofences they need to detect a walk-out in the background. Trip pushes: paid = `Paid $11.18 at Bayside Grocer` / `2 items with Visa •••• 4242. Exit code SRF6WQ.`; payment due = `Payment due at Bayside Grocer` / `$11.18 for 2 items. Show exit code SRF6WQ to store staff.` (there is no way to settle a past trip in the app, so the push points to store staff, matching the phones' local notifications).


## Order ownership and account switching (v5.2, additive)

Fixes a privacy bug: `shopper_login` maps a phone identity to its *current* account, and `shop_trips` used to return every order of every currently linked identity. Switching accounts on a shared phone showed the previous account's trips and `lastReceipt`, and signing a phone out removed its trips from the account's history.

- **Durable ownership.** Private table `order_owner(order_id, account_id, identity, at)` is written in the same transaction as the order, from the phone's login at that moment (`account_id` 0 = guest). It is never recomputed from the current login.
- **Trip history** (`shop_trips`): signed in = every order owned by the account, from any phone, including phones that later signed out or switched; guest = this phone's own guest orders. `CartState.lastReceipt` and the `my_orders` view only show an order written on this phone *and* owned by its current login.
- **Orders from before v5.2** have no ownership row. They are not guessed or reassigned: they stay with the store (`shop_order`, `order_line`, console unchanged) but appear in nobody's trip history or `lastReceipt`.
- **Guest trips stay with the phone, not the person.** A guest's trips before signing up are not moved to the new account (that would be a guess); a later guest on the same phone can see them.
- **The bag belongs to the login that started it.** Private table `cart_session(identity, account_id, started_at)` is written when an empty bag gets its first item. A walk-out charges only if that login is still the phone's login; otherwise the trip is recorded for the bag's owner as payment due and no other wallet is charged.
- **Account changes mid-trip are refused** (in a store with items in the bag): sign-in to another account, sign-up, sign-out, and `account_delete` while any of the account's phones is mid-trip. Errors: `You’re in the middle of a trip. Walk out or take everything out of your bag before you switch accounts.` / `... before you sign out.` / `One of your phones is in the middle of a trip. Finish it before you delete your account.` Otherwise every account change clears the previous login's out-of-store bag (never priced or charged), bag name and payment label. Phones should send `shop_set_bag` again after signing in.
- **Payment labels.** Signed-in walk-outs charge the wallet's default card directly. The public `shopper.payment` shows only `Card on file` (or `''`) for signed-in phones, and public orders carry `Brand •••• last4` without the card's nickname. `CartState.payment` (private to the phone) still returns the full label. Guests keep the demo label they set with `shop_set_payment`.
- **Console names.** `my_store_people` names a phone identity only if all its recent orders in that store are owned by its current login, so one person's name is never shown on another person's (or an unknown owner's) trip.
- `account_delete` removes the account's ownership rows; the receipts stay with the store.
- Still public by the console contract: `shop_order`, `order_line`, `cart_item`, `bag_event`, `shopper` (keyed by phone identity). The apps never show another account's trips, but someone reading those public tables directly can still see every order made on a phone identity.
- Published with `--delete-data=never`. Regression test: `tests/ownership.test.mjs` (live; uses its own throwaway store and new accounts, cleans up after itself).

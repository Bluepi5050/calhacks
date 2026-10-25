# SmartBag bag firmware (ESP32)

Arduino sketch (ESP32 core 2.x or 3.x). Advertises as `SmartBag-01` with the BLE Nordic UART Service and streams item reads to the phone.

| | UUID |
|---|---|
| Service | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |
| RX (phone → bag, write) | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` |
| TX (bag → phone, notify) | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |

**Bag → phone** (newline-terminated): `1005` or `+1005` add one · `-1005` remove one · `+1005@E28011` add, ignoring repeat reads of that tag · `{"op":"add","sku":"1005"}`.

**Phone → bag**: `OK +1005 4.69`, `OK -1005`, `ERR 9999 unknown`, `PAID 7KQ2XD` after a charged walk-out (one short blink, as on OK), `DUE 7KQ2XD` after a walk-out that ended payment due (v3: three slow blinks). Any other line is ignored.

For now items are typed into the Serial Monitor (115200 baud). Replace the Serial read in `loop()` with your RFID or barcode reader, sending the tag UID after `@`.


## Pairing (firmware v2)

Each bag advertises a unique Bluetooth name, `SmartBag-XXXX` (the last two bytes of its MAC address), so the Bag It app can tell bags apart on its pairing screen. When the phone writes `HELLO`, the status light blinks three times and the bag replies `BAG <name> fw2`; the app shows "Is the light on your bag blinking?" so you confirm the bag in your hand. Apps treat lines that start with `BAG ` as control lines, never as items. Bags flashed with v1 all advertise `SmartBag-01` and do not blink on `HELLO`; reflash to pair more than one bag.


## Walk-out signal (firmware v3)

When the phone walks out of the store, Bag It tells the bag how the trip ended:

| Line | Meaning | Light |
|---|---|---|
| `PAID <code>` | Charged to the shopper's card | one short blink (120 ms), unchanged |
| `DUE <code>` | Payment due: no card on file, nothing charged; staff check the exit code | three slow blinks, 600 ms on / 400 ms off |

The slow pattern runs in `loop()`, never inside the BLE callback. v1/v2 bags ignore `DUE` (they only react to `OK`, `PAID` and `HELLO`), so an older bag stays dark on a payment-due trip instead of misreporting it. The pairing reply is now `BAG <name> fw3`; apps read the name and ignore the version.

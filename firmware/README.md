# SmartBag bag firmware (ESP32)

Arduino sketch (ESP32 core 2.x or 3.x). Advertises as `SmartBag-01` with the BLE Nordic UART Service and streams item reads to the phone.

| | UUID |
|---|---|
| Service | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |
| RX (phone → bag, write) | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` |
| TX (bag → phone, notify) | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |

**Bag → phone** (newline-terminated): `1005` or `+1005` add one · `-1005` remove one · `+1005@E28011` add, ignoring repeat reads of that tag · `{"op":"add","sku":"1005"}`.

**Phone → bag**: `OK +1005 4.69`, `OK -1005`, `ERR 9999 unknown`, `PAID 7KQ2XD` after the shopper walks out (the LED blinks on OK/PAID).

For now items are typed into the Serial Monitor (115200 baud). Replace the Serial read in `loop()` with your RFID or barcode reader, sending the tag UID after `@`.

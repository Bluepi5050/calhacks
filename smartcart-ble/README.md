# smartcart-ble

The Jetson side of the SmartCart ↔ Bag It Bluetooth contract ([`smartcart_ble/SMARTCART-BLE.md`](smartcart_ble/SMARTCART-BLE.md), v1.1), as a self-contained Python 3.10 package with no pip dependencies (BlueZ over D-Bus via `python3-dbus` + `python3-gi`).

* **Wired into the scanner**: the same package lives at `hardware/smartcart_ble/` on branch [`ble-transport`](https://github.com/Bluepi5050/calhacks/tree/ble-transport) of Bluepi5050/calhacks, with `--transport ble | ble+console` in `hardware/main.py` and `hardware/main_tensorrt.py`. Start there; its [README](smartcart_ble/README.md) is written for the teammate (commands use `hardware.smartcart_ble`).
* **Standalone** (this folder): the package imports as `smartcart_ble`, so the same commands are

```bash
bash smartcart_ble/tools/check_adapter.sh
sudo python3 -m smartcart_ble.tools.fake_cart --demo
python3 -m smartcart_ble.tools.test_central --resume-test      # on a laptop, pip install bleak
```

```python
from smartcart_ble import SmartCartBLE
cart = SmartCartBLE().start()
cart.publish("ADD", "apple", 0.93)       # -> seq, or None when no shopping session is active
```

Tests: `python3 -m unittest discover -s tests` (the D-Bus tests need `python3-dbus`, `python3-gi`, `python3-dbusmock` and skip otherwise).

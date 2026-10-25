# SmartCart BLE (Jetson side)

The cart talks to the **Bag It** phone app over Bluetooth LE. The Jetson is a BLE *peripheral*: it advertises the SmartCart service, the phone connects, starts a shopping session when it walks into a store, and receives every confirmed ADD/REMOVE as a notification. Prices, the cart total and the walk-out charge all live in the backend; the Jetson only reports crossings.

The binding contract is [`SMARTCART-BLE.md`](SMARTCART-BLE.md) (v1.2). This package implements its Jetson side exactly. Your detector, tracker and crossing logic are untouched.

* Python 3.10 (system `/usr/bin/python3` on Jetson Linux R36), no pip packages.
* BlueZ over D-Bus: `bluez`, `python3-dbus`, `python3-gi` (all in the Jetson Linux image; otherwise `sudo apt install bluez python3-dbus python3-gi`).
* Needs permission to talk to BlueZ: run with `sudo`, or add your user to the `bluetooth` group (see Permissions).

## Try it on the Jetson (3 commands, from the repo root)

```bash
# 1. Is the Bluetooth adapter ready? (read-only check, prints OK / WARN / FAIL with the fix)
bash hardware/smartcart_ble/tools/check_adapter.sh

# 2. Fake cart, no camera/YOLO: advertises SmartCart-XXXX and plays a scripted trip whenever a session is active
sudo python3 -m hardware.smartcart_ble.tools.fake_cart --demo
#    (or interactive: drop --demo, then type  a apple / r apple / s / q)

# 3. The real scanner with Bluetooth (keeps the JSON lines in the terminal)
sudo python3 -m hardware.main_tensorrt --transport ble+console
```

Then open Bag It on the phone and walk into a store (or use the phone simulator below). The cart sends nothing until the phone has started a session.

`--transport` choices are now `both | console | mock | ble | ble+console` in both `hardware/main.py` and `hardware/main_tensorrt.py`. `ble` prints only the Bluetooth log lines; `ble+console` also prints every event as JSON like `console`.

## What changed in the scanner (the whole wiring)

```python
# hardware/scanner.py  make_transport()
if mode in ("ble", "ble+console"):
    from .smartcart_ble.transport_adapter import BleTransport
    return BleTransport(also=JsonLineTransport() if mode == "ble+console" else None), None

# hardware/scanner.py  run_scanner(), right after EventProcessor(transport)
register = getattr(transport, "add_session_start_listener", None)
if callable(register):
    register(event_processor.reset_counts)      # a NEW shopping session starts with an empty bag

# hardware/events.py  EventProcessor.process(), first lines
if getattr(self.transport, "backend_counts", False):     # BleTransport sets this
    return self._process_backend_counted(crossing)         # send first, count only what was sent
```

Plus `EventProcessor._process_backend_counted()` and `reset_counts()`, the `--transport` choices in `main.py` / `main_tensorrt.py`, and `transport.close()` when the scanner exits. The console/mock transports behave exactly as before. Nothing in the detector, tracker or crossing code changed.

### How sessions affect your bag counts

* **The backend owns the cart.** With `ble`, every confirmed crossing is offered to Bluetooth, including a REMOVE when the local count is 0 (e.g. the scanner restarted mid-trip and forgot the apple the backend still has; the backend answers `invalid_remove` if it really has none). The local counts follow only events that were actually sent; they are a display, never a gate.
* **No session (IDLE)**: the phone is not in a store. Nothing is sent and the counts don't change (the preview shows "Not sent (no shopping session)").
* **New session**: when the phone starts a new session (walks into a store), the counts are cleared, like the backend's new cart.
* **Resumed session** (phone reconnects to the same session): counts are kept, and every event the phone hadn't acknowledged is sent again after `OK|START`. The backend ignores duplicates.
* **The cart restarts but the phone keeps its session** (scanner restarted, 30-minute stale stop, bluetoothd restart): the phone sends `START` with the same session id, and the cart continues numbering after the last seq it used for that session instead of starting at 1 again. The backend de-duplicates by `<sid>:<seq>`, so reused numbers would be silently dropped. The last seq per session is kept in `~/.local/state/smartcart-ble/sessions.json` (with sudo: `/root/.local/state/...`; override with `SMARTCART_STATE_FILE` or `state_file=`).

## Test from a laptop without the app

`tools/test_central.py` plays the phone. It works on Linux, macOS and Windows with [bleak](https://github.com/hbldh/bleak):

```bash
pip install bleak
python3 -m hardware.smartcart_ble.tools.test_central                 # connect, START, print + ACK every event
python3 -m hardware.smartcart_ble.tools.test_central --resume-test   # automated: events, disconnect, reconnect,
                                                                     # resume (resent after OK), ACK, STOP, idempotent STOP
```

Run `fake_cart --demo` (or the real scanner) on the Jetson first. Interactive commands: `start [sid] [store]`, `stop`, `sync`, `status`, `ack on|off` (turn ACKs off to watch events pile up and get resent), `reconnect`, `q`.

The fake cart also has a no-Bluetooth mode for rehearsing the protocol anywhere: `python3 -m hardware.smartcart_ble.tools.fake_cart --no-ble`, then type `phone START|s1|bayside-grocer`, `a apple`, `phone ACK|s1|1`, `phone STOP|s1`.

## Using it from your own code

```python
from hardware.smartcart_ble import SmartCartBLE

cart = SmartCartBLE(on_session_start=lambda sid, store, resumed: print("session", sid, store, resumed),
                    on_session_stop=lambda sid, why: print("ended", sid, why))
cart.start()                              # raises SmartCartError with a hint if Bluetooth can't start
cart.publish("ADD", "apple", 0.93)        # -> seq (1, 2, ...) or None when no session is active
cart.publish("REMOVE", "apple", 89)       # confidence: 0..1 float or 0..100 int
cart.status                               # Status(sid, state, last_seq, unacked, device_name)
cart.stop()
```

`publish()` is thread-safe and never blocks on Bluetooth: D-Bus runs in one background thread (`smartcart-ble`). `BleTransport` (what the scanner uses) runs this in a child process instead (`SmartCartProcess`, same API), so GLib never shares a main loop with OpenCV's GTK preview; its log lines start with `smartcart-ble`. Labels are the COCO class names from `data/detectable_products.json`, lowercased, max 20 characters. Callbacks run on the Bluetooth thread, so keep them short.

## What the cart does (contract v1.2 in one screen)

| | |
|---|---|
| Service | `7ccd0001-d416-4afc-a97d-f7c98e1afca9`, advertised; name `SmartCart-XXXX` (last 2 bytes of the adapter address) |
| CONTROL `…0002` (write) | `START|sid|store`, `STOP|sid`, `ACK|sid|seq`, `SYNC` |
| EVENTS `…0003` (notify) | `seq|A or R|label|confidence`, e.g. `17|A|apple|93` |
| STATUS `…0004` (read, notify) | `S|sid or -|IDLE or ACTIVE|last_seq|unacked|name`, `OK|START|sid`, `OK|STOP|sid`, `ERR|code|detail` |

* START of the active session = resume: `OK|START` first, then every unacked event again, in order. START of another session drops the old one; its seq continues after the last seq this cart ever sent for that sid (persisted), 1 if it never used it.
* The last 64 unacked events are kept; ACK removes one. STOP of a session that isn't active still answers OK.
* No phone connected for 30 minutes while ACTIVE: the cart ends the session itself.
* Malformed writes are answered with `ERR|BAD_CMD|…` or `ERR|BAD_SID|…` on STATUS (the GATT write itself succeeds); during shutdown commands get `ERR|BUSY|cart shutting down`.
* A cart (re)start always looks like a disconnect to the phone: at startup and after re-registering with bluetoothd the cart disconnects every device that was already connected (pass `disconnect_on_start=False` to `SmartCartBLE` to keep, say, a Bluetooth keyboard during development), and if the Bluetooth child process dies it is restarted (1, 2, 5, 10, 30 s backoff).

## Permissions

BlueZ only accepts D-Bus calls from root and from the `bluetooth` group (`/etc/dbus-1/system.d/bluetooth.conf`).

* Simplest: `sudo python3 -m hardware.main_tensorrt --transport ble+console` (run from the repo root so `-m hardware…` resolves).
* Without sudo: `sudo usermod -aG bluetooth $USER`, log out and in, check with `id`. If the Jetson's policy file has no `group="bluetooth"` section, use sudo.
* At boot: `tools/smartcart-scanner.service` is an example systemd unit (edit the paths, then `sudo systemctl enable --now smartcart-scanner`).

No experimental BlueZ features are needed (GATT server and LE advertising are stable in 5.64). No pairing/bonding either: the characteristics are open, like the existing bag.

## Troubleshooting

**`no Bluetooth adapter found`**: `bluetoothctl list` must show a controller. Try `sudo rfkill unblock bluetooth`, `sudo systemctl restart bluetooth`, check `dmesg | grep -i blue` (Orin Nano dev kit: the M.2 Wi-Fi/BT card; USB dongles: firmware). `check_adapter.sh` walks through it. Pick a specific adapter with `SmartCartBLE(adapter="hci1")` or `fake_cart --adapter hci1`.

**`bluetoothd is not running`**: `sudo systemctl enable --now bluetooth`.

**`AccessDenied` / permission denied**: see Permissions.

**Advertising fails** (the log says `advertising failed …; retrying`):
* `NotPermitted` / `Failed`: LE off or the controller is busy. `sudo btmgmt le on`, `sudo btmgmt advertising on`, or restart bluetooth.
* `Maximum advertisements reached`: another program advertises (another `fake_cart`/scanner, or a stale registration). Run only one SmartCart process at a time; `sudo systemctl restart bluetooth` clears stale ones.
* `InvalidLength`: handled automatically. A 128-bit service UUID and the name don't both fit in 31 bytes of legacy advertising, so BlueZ normally carries the name in the scan response; if it refuses, the cart retries with the adapter name and finally with the UUID only. Phones find the cart by UUID, so this never stops discovery.
* It retries in the background, so a phone may need a few seconds after the problem clears.

**Phone doesn't see the cart**:
* Android needs Nearby devices (and on older versions Location) permission and Bluetooth on; Bag It scans by service UUID, not name.
* Only one phone can be connected at a time on most controllers: while one is connected the cart stops advertising. Disconnect the other phone (or `test_central`). After a disconnect the cart re-advertises within ~2 seconds.
* Use a generic BLE scanner (nRF Connect) to confirm `SmartCart-XXXX` with service `7ccd0001-…` is visible. If the name shows as the Jetson's hostname, the alias could not be set (run with sudo); it's cosmetic.
* If the phone was connected to an older build with different characteristics, toggle Bluetooth on the phone to clear its GATT cache.

**MTU**: every message is ≤ 60 bytes, so an ATT MTU of 64+ is required; the app requests 247. If a phone connects with the default 23 the log says `negotiated ATT MTU 23 (< 64)`, and notifications would be cut to 20 bytes. That's a phone-side bug (it must request the MTU before subscribing).

**`--preview` with `ble`**: works. dbus-python's GLib loop and OpenCV's GTK preview would block each other in one process (both use GLib's default main context), so the scanner runs Bluetooth in a child process (`python3 -m hardware.smartcart_ble.process`). If you embed `SmartCartBLE` directly in a GTK/OpenCV-preview program, use `SmartCartProcess` instead.

**bluetoothd restarts**: the cart notices, waits for the adapter to come back (retrying with backoff), powers it and registers again. The session stays in memory; if the scanner itself restarts it starts IDLE, the phone sends START again and numbering continues (see sessions above).

**The Bluetooth process died**: the scanner logs `Bluetooth process exited unexpectedly`, events are not sent (and not counted) while it is down, and it is restarted automatically with backoff. The new process disconnects the phone so it reconnects, sends START and numbering continues. If it keeps failing, the log says why (`Bluetooth restart failed: …`).

**Other Bluetooth devices drop when the cart starts**: by design (contract v1.2), every link that existed before the cart registered is disconnected once. Devices that connect afterwards are left alone.

**See the raw traffic**: `sudo btmon` shows advertising, connections, MTU exchange and every write/notification.

## Files

| Path | What |
|---|---|
| `protocol.py` | Every message: encode, decode, validate (pure) |
| `session.py` | Jetson state machine: IDLE/ACTIVE, seq, 64-event resend buffer, resume, STOP, ACK, stale timer, last seq per session (fake-clock tested) |
| `peripheral.py` | `SmartCartBLE`: BlueZ GATT server + LE advertisement over D-Bus, background GLib thread, connection/MTU tracking |
| `process.py` | `SmartCartProcess`: the same API with Bluetooth in a child process (used by `BleTransport`), respawned if it dies |
| `transport_adapter.py` | `BleTransport`: the scanner's `send(event)` transport |
| `tools/fake_cart.py` | The cart without YOLO (`--demo`, interactive, `--no-ble`) |
| `tools/test_central.py` | Phone simulator with bleak (`--resume-test`) |
| `tools/check_adapter.sh` | Adapter / BlueZ / permission check |
| `tools/smartcart-scanner.service` | Example systemd unit |
| `SMARTCART-BLE.md` | The contract (v1.1) |

Tests (`hardware/tests/test_smartcart_*.py`, `hardware/tests/test_ble_transport_wiring.py`): protocol and session (pure), BleTransport and the scanner hooks, the phone-side rules, the fake cart offline, and the full D-Bus server against a mocked BlueZ (python-dbusmock: GATT tree, advertisement, alias, read/notify/write with device + MTU options, resume order, errors, clean shutdown, numbering after a cart restart, the child-process cart, disconnecting pre-existing links at startup, respawning a killed Bluetooth child). The D-Bus tests skip when `python3-dbusmock` is missing (`sudo apt install python3-dbusmock` to run them).

## Verified and not verified

Verified (2026-10-24, x86 sandbox, Python 3.10 and 3.12): all unit tests; the real `SmartCartBLE` D-Bus objects (directly and through `SmartCartProcess`) in separate processes against a mocked BlueZ on a private system bus; the teammate's existing tests still pass. Contract v1.2 additions (2026-10-24): startup disconnect and child respawn, both tested against the mocked BlueZ. One independent protocol review; its P0/P1 findings are fixed (numbering after a cart restart, preview + Bluetooth in one process, local REMOVE gate, re-register after bluetoothd restarts, simulator write serialisation and phone-side rules).

Not verified (no Bluetooth radio was available): a real BlueZ 5.64 on the Jetson, the actual advertisement on air, a real phone connecting, MTU negotiation, notification delivery timing, controller behavior after disconnects. Run `check_adapter.sh`, then `fake_cart --demo` + `test_central --resume-test` from a laptop to cover these in five minutes.

"""SmartCart BLE peripheral against a mocked BlueZ (python-dbusmock) on a private system bus.

Runs the real fake_cart tool (SmartCartBLE + D-Bus objects) in a subprocess and plays BlueZ's part:
reads the exported GATT tree, StartNotify, ReadValue, WriteValue with device/mtu options, and
receives the notifications as PropertiesChanged signals, exactly as bluetoothd would.
Skipped when python3-dbus / python3-gi / python3-dbusmock are not installed.
No radio is involved: this cannot prove that a phone sees the advertisement (see README).
"""
import os
import subprocess
import tempfile
import sys
import threading
import time
import unittest
from pathlib import Path

# The fake_cart subprocesses must not take (or wait on) the real one-cart-per-machine lock.
os.environ.setdefault("SMARTCART_LOCK_FILE", os.path.join(tempfile.gettempdir(), "smartcart-ble-test.lock"))

try:
    import dbus
    import dbusmock
    from dbus.mainloop.glib import DBusGMainLoop
    from gi.repository import GLib
    DBusGMainLoop(set_as_default=True)
    HAVE_DBUS = True
except Exception:  # pragma: no cover
    HAVE_DBUS = False

try:
    from hardware.smartcart_ble import protocol as p
except ImportError:
    from smartcart_ble import protocol as p

PKG = p.__name__.rsplit(".", 1)[0]
ROOT = Path(p.__file__).resolve().parents[len(PKG.split("."))]
CHRC = "org.bluez.GattCharacteristic1"
APP = "/org/smartcart"


@unittest.skipUnless(HAVE_DBUS and sys.platform.startswith("linux"), "needs dbus, gi and dbusmock")
class PeripheralOverMockBluez(dbusmock.DBusTestCase if HAVE_DBUS else unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.start_system_bus()
        cls.bus = cls.get_dbus(system_bus=True)

    def setUp(self):
        self.bluez, self.bluez_obj = self.spawn_server_template("bluez5", {}, stdout=subprocess.DEVNULL)
        self.mock = dbus.Interface(self.bluez_obj, "org.bluez.Mock")
        self.signals = []
        self.cart = None
        self.lines = []
        self.state_file = os.path.join(tempfile.mkdtemp(), "sessions.json")
        self.bus.add_signal_receiver(self._on_props, dbus_interface="org.freedesktop.DBus.Properties",
                                     signal_name="PropertiesChanged", path_keyword="path", sender_keyword="sender")

    def tearDown(self):
        self.bus.remove_signal_receiver(self._on_props, dbus_interface="org.freedesktop.DBus.Properties",
                                        signal_name="PropertiesChanged")
        if self.cart and self.cart.poll() is None:
            try:
                self.cart.stdin.write("q\n")
                self.cart.stdin.flush()
                self.cart.wait(5)
            except Exception:
                self.cart.kill()
        self.bluez.terminate()
        self.bluez.wait()

    # -- helpers ----------------------------------------------------------------------------
    def _on_props(self, iface, changed, invalidated, path=None, sender=None):
        if iface == CHRC and "Value" in changed:
            self.signals.append((str(path), bytes(int(b) for b in changed["Value"]).decode()))

    def pump(self, until, timeout=8.0):
        end = time.monotonic() + timeout
        ctx = GLib.MainContext.default()
        while time.monotonic() < end:
            while ctx.iteration(False):
                pass
            if until():
                return True
            time.sleep(0.02)
        return False

    def add_adapter(self, gatt_calls=True, adv_code=""):
        path = self.mock.AddAdapter("hci0", "jetson")
        obj = self.bus.get_object("org.bluez", path)
        m = dbus.Interface(obj, dbusmock.MOCK_IFACE)
        if gatt_calls:
            m.AddMethod("org.bluez.GattManager1", "RegisterApplication", "oa{sv}", "", "")
            m.AddMethod("org.bluez.GattManager1", "UnregisterApplication", "o", "", "")
            m.AddMethod("org.bluez.LEAdvertisingManager1", "RegisterAdvertisement", "oa{sv}", "", adv_code)
            m.AddMethod("org.bluez.LEAdvertisingManager1", "UnregisterAdvertisement", "o", "", "")
        self.adapter_mock = m
        self.adapter_props = dbus.Interface(obj, "org.freedesktop.DBus.Properties")
        return path

    def calls(self, method):
        return [c[1] for c in self.adapter_mock.GetMethodCalls(method)]

    def spawn_cart(self, *extra):
        env = dict(os.environ, PYTHONPATH=str(ROOT) + os.pathsep + os.environ.get("PYTHONPATH", ""),
                   PYTHONUNBUFFERED="1", SMARTCART_STATE_FILE=self.state_file)
        self.cart = subprocess.Popen([sys.executable, "-m", PKG + ".tools.fake_cart", *extra],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                     text=True, env=env, cwd=str(ROOT))
        threading.Thread(target=lambda: [self.lines.append(l.rstrip()) for l in self.cart.stdout],
                         daemon=True).start()

    def type(self, text):
        self.cart.stdin.write(text + "\n")
        self.cart.stdin.flush()

    def cart_name(self):
        me = self.bus.get_unique_name()
        for name in self.bus.list_names():
            if not name.startswith(":") or name == me:
                continue
            try:
                objs = dbus.Interface(self.bus.get_object(name, APP, introspect=False),
                                      "org.freedesktop.DBus.ObjectManager"
                                      ).GetManagedObjects(timeout=1)
                if objs:
                    return name, objs
            except dbus.exceptions.DBusException:
                continue
        return None, None

    def chrc(self, owner, n):
        return dbus.Interface(self.bus.get_object(owner, f"{APP}/service0/char{n}"), CHRC)

    def notes(self, n):
        return [v for path, v in self.signals if path.endswith(f"char{n}")]

    # -- tests ----------------------------------------------------------------------------------
    def test_full_flow(self):
        self.add_adapter()
        address = str(self.adapter_props.Get("org.bluez.Adapter1", "Address"))
        expect_name = p.device_name_from_address(address)
        self.spawn_cart()
        self.assertTrue(self.pump(lambda: self.calls("RegisterApplication") and self.calls("RegisterAdvertisement")),
                        "\n".join(self.lines))
        self.assertTrue(self.pump(lambda: any("is advertising" in l for l in self.lines)), "\n".join(self.lines))
        owner, objs = self.cart_name()
        self.assertIsNotNone(owner)

        # GATT tree exactly per contract section 1
        self.assertEqual(str(self.calls("RegisterApplication")[0][0]), APP)
        svc = objs[dbus.ObjectPath(APP + "/service0")]["org.bluez.GattService1"]
        self.assertEqual(str(svc["UUID"]), p.SERVICE_UUID)
        self.assertTrue(bool(svc["Primary"]))
        chars = {str(v[CHRC]["UUID"]): [str(f) for f in v[CHRC]["Flags"]] for k, v in objs.items() if CHRC in v}
        self.assertEqual(chars, {p.CONTROL_UUID: ["write"], p.EVENTS_UUID: ["notify"],
                                 p.STATUS_UUID: ["read", "notify"]})
        for k, v in objs.items():
            if CHRC in v:
                self.assertEqual(str(v[CHRC]["Service"]), APP + "/service0")

        # advertisement + adapter alias
        adv_path = str(self.calls("RegisterAdvertisement")[0][0])
        adv = dbus.Interface(self.bus.get_object(owner, adv_path), "org.freedesktop.DBus.Properties"
                             ).GetAll("org.bluez.LEAdvertisement1")
        self.assertEqual(str(adv["Type"]), "peripheral")
        self.assertEqual([str(u) for u in adv["ServiceUUIDs"]], [p.SERVICE_UUID])
        self.assertEqual(str(adv["LocalName"]), expect_name)
        self.assertEqual(str(self.adapter_props.Get("org.bluez.Adapter1", "Alias")), expect_name)

        control, events, status = self.chrc(owner, 0), self.chrc(owner, 1), self.chrc(owner, 2)
        read = lambda: bytes(int(b) for b in status.ReadValue({"mtu": dbus.UInt16(247)})).decode()
        self.assertEqual(read(), f"S|-|IDLE|0|0|{expect_name}")
        self.assertEqual(bytes(int(b) for b in status.ReadValue({"offset": dbus.UInt16(4)})).decode(),
                         f"IDLE|0|0|{expect_name}")

        # events while IDLE are dropped
        self.type("a apple")
        self.assertTrue(self.pump(lambda: any("dropped: no active session" in l for l in self.lines)))

        # phone connects, subscribes, starts a session
        dev = self.mock.AddDevice("hci0", "11:22:33:44:55:66", "Pixel")
        dev_obj = self.bus.get_object("org.bluez", dev)
        dbus.Interface(dev_obj, "org.bluez.Device1").Connect()
        events.StartNotify()
        status.StartNotify()
        opts = {"device": dbus.ObjectPath(dev), "mtu": dbus.UInt16(247), "type": "request"}
        control.WriteValue(dbus.ByteArray(b"START|s1k9|bayside-grocer"), opts)
        self.assertTrue(self.pump(lambda: self.notes(2) == ["OK|START|s1k9"]), self.signals)

        self.type("a apple 93")
        self.type("r apple 89")
        self.assertTrue(self.pump(lambda: self.notes(1) == ["1|A|apple|93", "2|R|apple|89"]), self.signals)
        self.assertEqual(read(), f"S|s1k9|ACTIVE|2|2|{expect_name}")

        control.WriteValue(dbus.ByteArray(b"ACK|s1k9|1"), opts)
        control.WriteValue(dbus.ByteArray(b"SYNC"), opts)
        self.assertTrue(self.pump(lambda: self.notes(2)[-1:] == [f"S|s1k9|ACTIVE|2|1|{expect_name}"]))

        # disconnect, add while away (buffered), reconnect and resume: OK first, then resend in order
        dbus.Interface(dev_obj, "org.bluez.Device1").Disconnect()
        events.StopNotify()
        status.StopNotify()
        self.type("a banana")
        self.assertTrue(self.pump(lambda: any("no subscriber" in l and "3|A|banana|93" in l for l in self.lines)))
        dbus.Interface(dev_obj, "org.bluez.Device1").Connect()
        events.StartNotify()
        status.StartNotify()
        self.signals.clear()
        control.WriteValue(dbus.ByteArray(b"START|s1k9|bayside-grocer"), opts)
        self.assertTrue(self.pump(lambda: len(self.signals) >= 3), self.signals)
        self.assertEqual([v for _, v in self.signals],
                         ["OK|START|s1k9", "2|R|apple|89", "3|A|banana|93"])

        # malformed command -> ERR on STATUS, write still succeeds
        control.WriteValue(dbus.ByteArray(b"FOO|x"), opts)
        self.assertTrue(self.pump(lambda: self.notes(2)[-1:] == ["ERR|BAD_CMD|unknown command"]))
        with self.assertRaises(dbus.exceptions.DBusException) as cm:
            control.WriteValue(dbus.ByteArray(b"SYNC"), {"offset": dbus.UInt16(2)})
        self.assertEqual(cm.exception.get_dbus_name(), "org.bluez.Error.InvalidOffset")

        # small MTU is reported
        control.WriteValue(dbus.ByteArray(b"SYNC"), {"device": dbus.ObjectPath(dev), "mtu": dbus.UInt16(23)})
        self.assertTrue(self.pump(lambda: any("ATT MTU 23" in l for l in self.lines)), "\n".join(self.lines))

        # new session replaces, STOP ends it, STOP again still OK
        control.WriteValue(dbus.ByteArray(b"START|s2|bayside-grocer"), opts)
        control.WriteValue(dbus.ByteArray(b"STOP|s2"), opts)
        control.WriteValue(dbus.ByteArray(b"STOP|s2"), opts)
        self.assertTrue(self.pump(lambda: self.notes(2)[-3:] == ["OK|START|s2", "OK|STOP|s2", "OK|STOP|s2"]),
                        self.notes(2))
        self.assertEqual(read(), f"S|-|IDLE|0|0|{expect_name}")

        # clean shutdown unregisters from BlueZ
        self.type("q")
        self.assertEqual(self.cart.wait(10), 0)
        self.assertTrue(self.pump(lambda: self.calls("UnregisterApplication") and
                                  self.calls("UnregisterAdvertisement")))

        # cart restarts while the phone keeps session s1k9: numbering continues after 3
        self.lines.clear()
        self.spawn_cart()
        self.assertTrue(self.pump(lambda: any("is advertising" in l for l in self.lines)), "\n".join(self.lines))
        owner, _ = self.cart_name()
        control, events, status = self.chrc(owner, 0), self.chrc(owner, 1), self.chrc(owner, 2)
        events.StartNotify()
        status.StartNotify()
        self.signals.clear()
        control.WriteValue(dbus.ByteArray(b"START|s1k9|bayside-grocer"), opts)
        self.type("a apple")
        self.assertTrue(self.pump(lambda: self.notes(1) == ["4|A|apple|93"]), self.signals)

    def test_process_isolated_cart(self):
        """SmartCartProcess (what BleTransport uses): Bluetooth in a child, publish from this process."""
        try:
            from hardware.smartcart_ble.process import SmartCartProcess
        except ImportError:
            from smartcart_ble.process import SmartCartProcess
        self.add_adapter()
        os.environ["SMARTCART_STATE_FILE"] = self.state_file
        started = []
        cart = SmartCartProcess(on_session_start=lambda sid, store, resumed: started.append((sid, resumed)))
        try:
            cart.start()
            self.assertTrue(cart.name.startswith("SmartCart-"))
            self.assertIsNone(cart.publish("ADD", "apple", 0.9))          # IDLE
            owner, _ = self.cart_name()
            control, events = self.chrc(owner, 0), self.chrc(owner, 1)
            events.StartNotify()
            control.WriteValue(dbus.ByteArray(b"START|p1|bayside-grocer"), {"mtu": dbus.UInt16(247)})
            self.assertTrue(self.pump(lambda: cart.active and started == [("p1", False)]), started)
            self.assertEqual(cart.publish("ADD", "apple", 0.9), 1)
            self.assertEqual(cart.publish("REMOVE", "apple", 88), 2)
            self.assertTrue(self.pump(lambda: self.notes(1) == ["1|A|apple|90", "2|R|apple|88"]), self.signals)
            self.assertEqual(cart.status.last_seq, 2)
        finally:
            cart.stop()
            os.environ.pop("SMARTCART_STATE_FILE", None)
        self.assertTrue(self.pump(lambda: self.calls("UnregisterApplication")))
        self.assertIsNone(cart.publish("ADD", "apple", 1))

    def connected_device(self, address="11:22:33:44:55:77"):
        """A device BlueZ already reports as connected (link up before the cart registers)."""
        dev = self.mock.AddDevice("hci0", address, "Pixel")
        obj = self.bus.get_object("org.bluez", dev)
        dbus.Interface(obj, "org.bluez.Device1").Connect()
        dbus.Interface(obj, "org.freedesktop.DBus.Properties").Set("org.bluez.Device1", "Connected", True)
        return dev, dbus.Interface(obj, dbusmock.MOCK_IFACE)

    def test_startup_disconnects_existing_links(self):
        self.add_adapter()
        dev, dev_mock = self.connected_device()
        self.spawn_cart()
        self.assertTrue(self.pump(lambda: len(dev_mock.GetMethodCalls("Disconnect")) == 1), "\n".join(self.lines))
        self.assertTrue(self.pump(lambda: any("so the phone reconnects" in l for l in self.lines)))
        # a phone that connects after registration is not touched
        late = self.mock.AddDevice("hci0", "11:22:33:44:55:88", "Late")
        late_obj = self.bus.get_object("org.bluez", late)
        dbus.Interface(late_obj, "org.bluez.Device1").Connect()
        time.sleep(1.0)
        self.pump(lambda: False, timeout=0.5)
        self.assertEqual(dbus.Interface(late_obj, dbusmock.MOCK_IFACE).GetMethodCalls("Disconnect"), [])

    def test_startup_disconnect_can_be_turned_off(self):
        try:
            from hardware.smartcart_ble.peripheral import SmartCartBLE
        except ImportError:
            from smartcart_ble.peripheral import SmartCartBLE
        self.add_adapter()
        dev, dev_mock = self.connected_device()
        cart = SmartCartBLE(disconnect_on_start=False, state_file=None, object_path="/org/smartcart_t",
                            single_instance=False)
        cart.start()
        try:
            self.pump(lambda: cart.advertising, timeout=3)
            self.assertEqual(dev_mock.GetMethodCalls("Disconnect"), [])
        finally:
            cart.stop()

    def test_process_respawns_dead_child(self):
        try:
            from hardware.smartcart_ble.process import SmartCartProcess
        except ImportError:
            from smartcart_ble.process import SmartCartProcess
        import signal
        self.add_adapter()
        os.environ["SMARTCART_STATE_FILE"] = self.state_file
        SmartCartProcess.RESPAWN_S = (0.2,)
        stops = []
        cart = SmartCartProcess(on_session_stop=lambda sid, why: stops.append((sid, why)))
        try:
            cart.start()
            owner, _ = self.cart_name()
            control, events = self.chrc(owner, 0), self.chrc(owner, 1)
            events.StartNotify()
            dev, dev_mock = self.connected_device()
            control.WriteValue(dbus.ByteArray(b"START|r1|bayside-grocer"), {"device": dbus.ObjectPath(dev)})
            self.assertTrue(self.pump(lambda: cart.active))
            self.assertEqual(cart.publish("ADD", "apple", 0.9), 1)
            first = cart.pid
            os.kill(first, signal.SIGKILL)                              # the Bluetooth child crashes
            self.assertTrue(self.pump(lambda: not cart.active and ("r1", "crash") in stops), stops)
            self.assertIsNone(cart.publish("ADD", "apple", 0.9))      # down: not sent
            self.assertTrue(self.pump(lambda: cart.restarts == 1 and len(self.calls("RegisterApplication")) == 2,
                                      timeout=20))
            self.assertNotEqual(cart.pid, first)
            # the phone's link predates the new child: it gets disconnected, so the phone reconnects
            self.assertTrue(self.pump(lambda: len(dev_mock.GetMethodCalls("Disconnect")) == 1))
            owner, _ = self.cart_name()
            control, events = self.chrc(owner, 0), self.chrc(owner, 1)
            events.StartNotify()
            self.signals.clear()
            control.WriteValue(dbus.ByteArray(b"START|r1|bayside-grocer"), {"mtu": dbus.UInt16(247)})
            self.assertTrue(self.pump(lambda: cart.active))
            self.assertEqual(cart.publish("ADD", "apple", 0.9), 2)    # numbering continues after the crash
            self.assertTrue(self.pump(lambda: self.notes(1) == ["2|A|apple|90"]), self.signals)
        finally:
            SmartCartProcess.RESPAWN_S = (1, 2, 5, 10, 30)
            cart.stop()
            os.environ.pop("SMARTCART_STATE_FILE", None)
        time.sleep(0.5)
        self.assertEqual(cart.restarts, 1)                              # stop() does not respawn

    def test_process_isolated_cart_reports_errors(self):
        try:
            from hardware.smartcart_ble.process import SmartCartProcess
            from hardware.smartcart_ble.peripheral_errors import SmartCartError
        except ImportError:
            from smartcart_ble.process import SmartCartProcess
            from smartcart_ble.peripheral_errors import SmartCartError
        with self.assertRaises(SmartCartError) as cm:
            SmartCartProcess().start()
        self.assertIn("no Bluetooth adapter", str(cm.exception))

    def test_advertisement_falls_back_when_too_long(self):
        code = ("self.n = getattr(self, 'n', 0) + 1\n"
                "if self.n == 1:\n"
                "    raise dbus.exceptions.DBusException('too long', name='org.bluez.Error.InvalidLength')\n")
        self.add_adapter(adv_code=code)
        self.spawn_cart("--name", "SmartCart-TEST")
        self.assertTrue(self.pump(lambda: len(self.calls("RegisterAdvertisement")) >= 2), "\n".join(self.lines))
        self.assertTrue(self.pump(lambda: any("advertising SmartCart-TEST (service UUID + adapter name)" in l
                                              for l in self.lines)), "\n".join(self.lines))
        owner, _ = self.cart_name()
        adv = dbus.Interface(self.bus.get_object(owner, APP + "/advertisement0"), "org.freedesktop.DBus.Properties"
                             ).GetAll("org.bluez.LEAdvertisement1")
        self.assertNotIn("LocalName", adv)
        self.assertEqual([str(x) for x in adv["Includes"]], ["local-name"])

    def test_no_adapter_fails_clearly(self):
        self.spawn_cart()
        self.assertEqual(self.cart.wait(20), 2)
        time.sleep(0.2)
        self.assertTrue(any("no Bluetooth adapter found" in l for l in self.lines), "\n".join(self.lines))

    def test_gatt_refused_fails_clearly(self):
        self.add_adapter(gatt_calls=False)          # adapter without GattManager1 (old/disabled BlueZ)
        self.spawn_cart()
        try:
            self.cart.wait(25)
        except subprocess.TimeoutExpired:
            pass
        time.sleep(0.2)
        self.assertEqual(self.cart.returncode, 2)
        self.assertTrue(any("BlueZ refused the GATT service" in l for l in self.lines), "\n".join(self.lines))


if __name__ == "__main__":
    unittest.main()

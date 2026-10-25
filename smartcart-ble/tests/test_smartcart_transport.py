"""BleTransport: scanner event dicts -> SmartCart publish(), plus the session hooks. No Bluetooth."""
import enum
import unittest

try:
    from hardware.smartcart_ble.transport_adapter import BleTransport, extract_event
except ImportError:
    from smartcart_ble.transport_adapter import BleTransport, extract_event


class FakeCart:
    """Stands in for SmartCartBLE: records publishes, lets the test start sessions."""

    def __init__(self):
        self.active = False
        self.published = []
        self.started = self.stopped = False
        self._start = []

    def add_session_start_listener(self, fn):
        self._start.append(fn)

    def start(self):
        self.started = True

    def stop(self):
        self.stopped = True

    def begin(self, sid="s1", resumed=False):
        self.active = True
        for fn in self._start:
            fn(sid, "bayside-grocer", resumed)

    def publish(self, action, label, confidence):
        self.published.append((action, label, confidence))
        return len(self.published) if self.active else None


class Recorder:
    def __init__(self):
        self.events = []

    def send(self, event):
        self.events.append(event)


EVENT = {"event_id": "session1-1", "action": "ADD", "product_id": "apple", "confidence": 0.9312,
         "timestamp": "2026-10-24T18:30:00Z"}


class BleTransportTests(unittest.TestCase):
    def setUp(self):
        self.cart = FakeCart()

    def test_maps_scanner_dict(self):
        t = BleTransport(self.cart)
        self.assertTrue(self.cart.started)
        self.cart.begin()
        self.assertEqual(t.send(EVENT), 1)
        self.assertEqual(t.send(dict(EVENT, action="REMOVE", confidence=0.88)), 2)
        self.assertEqual(self.cart.published, [("A", "apple", 93), ("R", "apple", 88)])

    def test_declares_backend_counts(self):
        self.assertTrue(BleTransport(self.cart).backend_counts)

    def test_dropped_while_idle_returns_none(self):
        t = BleTransport(self.cart)
        self.assertIsNone(t.send(EVENT))
        self.assertFalse(t.accepts_events())
        self.cart.begin()
        self.assertTrue(t.accepts_events())

    def test_also_transport_and_label_map(self):
        rec = Recorder()
        t = BleTransport(self.cart, also=rec, label_map={"SKU-1003": "apple"})
        self.cart.begin()
        t.send(dict(EVENT, product_id="SKU-1003"))
        self.assertEqual(rec.events[0]["product_id"], "SKU-1003")
        self.assertEqual(self.cart.published[-1], ("A", "apple", 93))

    def test_bad_event_is_logged_not_raised(self):
        t = BleTransport(self.cart)
        self.cart.begin()
        with self.assertLogs("smartcart_ble", "ERROR"):
            self.assertIsNone(t.send({"action": "ADD"}))
        with self.assertLogs("smartcart_ble", "ERROR"):
            self.assertIsNone(t.send(dict(EVENT, product_id="a|b")))
        self.assertEqual(self.cart.published, [])

    def test_new_session_listener_skips_resume(self):
        t = BleTransport(self.cart)
        calls = []
        t.add_session_start_listener(lambda: calls.append(1))
        self.cart.begin("s1")
        self.cart.begin("s1", resumed=True)
        self.cart.begin("s2")
        self.assertEqual(calls, [1, 1])

    def test_listener_errors_do_not_escape(self):
        t = BleTransport(self.cart)
        t.add_session_start_listener(lambda: 1 / 0)
        with self.assertLogs("smartcart_ble", "ERROR"):
            self.cart.begin()

    def test_close_and_aliases(self):
        t = BleTransport(self.cart, start=False)
        self.assertFalse(self.cart.started)
        self.cart.begin()
        self.assertEqual(t.publish(EVENT), 1)
        self.assertEqual(t(EVENT), 2)
        t.close()
        self.assertTrue(self.cart.stopped)


class ExtractTests(unittest.TestCase):
    def test_shapes(self):
        class Action(enum.Enum):
            ADD = "ADD"

        class Crossing:
            action = Action.ADD
            product_id = "banana"
            confidence = 0.7
        self.assertEqual(extract_event(Crossing()), (Action.ADD, "banana", 0.7))
        self.assertEqual(extract_event({"type": "REMOVE", "label": "orange", "score": 55}), ("REMOVE", "orange", 55))
        self.assertEqual(extract_event("ADD apple 0.93"), ("ADD", "apple", "0.93"))
        self.assertEqual(extract_event("ADD cell phone"), ("ADD", "cell phone", None))
        with self.assertRaises(ValueError):
            extract_event("ADD")
        with self.assertRaises(ValueError):
            extract_event({"product_id": "apple"})


if __name__ == "__main__":
    unittest.main()

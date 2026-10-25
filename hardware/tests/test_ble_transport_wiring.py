"""BLE wiring: backend-counted EventProcessor, count reset on a new session, make_transport('ble'), CLI choices.
No Bluetooth: SmartCartBLE is replaced by a fake cart."""
import io
import unittest
from contextlib import redirect_stdout
from types import SimpleNamespace
from unittest import mock

from hardware.events import EventProcessor
from hardware.smartcart_ble import transport_adapter
from hardware.smartcart_ble.transport_adapter import BleTransport

try:
    import cv2  # noqa: F401  (scanner.py needs OpenCV)
    from hardware import scanner
except ImportError:  # pragma: no cover
    scanner = None


class FakeCart:
    def __init__(self):
        self.active = False
        self.published = []
        self._start = []

    def add_session_start_listener(self, fn):
        self._start.append(fn)

    def start(self):
        pass

    def stop(self):
        self.stopped = True

    def begin(self, sid, resumed=False):
        self.active = True
        for fn in self._start:
            fn(sid, "bayside-grocer", resumed)

    def end(self):
        self.active = False

    def publish(self, action, label, confidence):
        if not self.active:
            return None
        self.published.append((action, label, confidence))
        return len(self.published)


def crossing(action, product="apple", confidence=0.93):
    return SimpleNamespace(action=action, product_id=product, confidence=confidence, track_id=1)


class EventProcessorWithBle(unittest.TestCase):
    def setUp(self):
        self.cart = FakeCart()
        self.transport = BleTransport(self.cart)
        self.processor = EventProcessor(self.transport)
        if scanner is not None:
            scanner.connect_session_hooks(self.transport, self.processor)
        else:
            self.transport.add_session_start_listener(self.processor.reset_counts)

    def test_idle_events_are_not_counted_or_sent(self):
        self.assertIsNone(self.processor.process(crossing("ADD")))
        self.assertEqual(self.processor.counts["apple"], 0)
        self.assertIn("no shopping session", self.processor.last_status)
        self.assertEqual(self.cart.published, [])

    def test_session_events_flow_and_new_session_resets_counts(self):
        self.cart.begin("s1")
        self.assertEqual(self.processor.process(crossing("ADD"))["action"], "ADD")
        self.processor.process(crossing("ADD", "banana", 0.5))
        self.assertEqual(self.cart.published, [("A", "apple", 93), ("A", "banana", 50)])
        self.cart.begin("s1", resumed=True)            # reconnect, same session: counts kept
        self.assertEqual(self.processor.counts["apple"], 1)
        self.cart.begin("s2")                          # new store visit: bag starts empty
        self.assertEqual(sum(self.processor.counts.values()), 0)

    def test_remove_with_zero_local_count_is_still_sent(self):
        # e.g. the scanner restarted mid-trip: the backend still has the apple, so the REMOVE must reach it
        self.cart.begin("s1")
        self.assertIsNotNone(self.processor.process(crossing("REMOVE")))
        self.assertEqual(self.cart.published, [("R", "apple", 93)])
        self.assertEqual(self.processor.counts["apple"], 0)             # never negative

    def test_session_ending_between_check_and_send_leaves_counts(self):
        self.cart.begin("s1")
        self.cart.end()
        self.assertIsNone(self.processor.process(crossing("ADD")))
        self.assertEqual(self.processor.counts["apple"], 0)

    def test_remove_after_add_in_same_session(self):
        self.cart.begin("s1")
        self.processor.process(crossing("ADD"))
        self.processor.process(crossing("REMOVE", confidence=0.89))
        self.assertEqual(self.cart.published[-1], ("R", "apple", 89))
        self.assertEqual(self.processor.counts["apple"], 0)

    def test_other_transports_unchanged(self):
        from hardware.transport import MockTransport
        p = EventProcessor(MockTransport(report=False))
        self.assertIsNone(p.process(crossing("REMOVE")))                # local rejection kept
        self.assertIsNotNone(p.process(crossing("ADD")))
        self.assertEqual(p.counts["apple"], 1)


@unittest.skipIf(scanner is None, "needs OpenCV (cv2)")
class ScannerWiring(unittest.TestCase):
    def test_make_transport_ble_modes(self):
        with mock.patch.object(transport_adapter, "BleTransport") as fake:
            transport, receiver = scanner.make_transport("ble")
            self.assertIs(transport, fake.return_value)
            self.assertIsNone(receiver)
            self.assertIsNone(fake.call_args.kwargs["also"])
            scanner.make_transport("ble+console")
            self.assertEqual(type(fake.call_args.kwargs["also"]).__name__, "JsonLineTransport")

    def test_other_modes_untouched(self):
        transport, receiver = scanner.make_transport("mock")
        self.assertIsNotNone(receiver)
        scanner.connect_session_hooks(transport, EventProcessor(transport))   # no-op, no error
        scanner.close_transport(transport)                                    # no close(): no-op

    def test_close_transport(self):
        cart = FakeCart()
        scanner.close_transport(BleTransport(cart))
        self.assertTrue(cart.stopped)


class CliChoices(unittest.TestCase):
    def test_main_tensorrt_accepts_ble(self):
        try:
            from hardware import main_tensorrt
        except ImportError as exc:  # pragma: no cover
            self.skipTest(str(exc))
        with mock.patch.object(main_tensorrt, "run_scanner") as run, \
                mock.patch.object(main_tensorrt, "TensorRTDetector") as det:
            det.COCO_NAMES = ["apple", "banana", "orange", "bottle", "carrot", "broccoli"]
            self.assertEqual(main_tensorrt.main(["--transport", "ble+console"]), 0)
            self.assertEqual(run.call_args.kwargs["transport_mode"], "ble+console")
            main_tensorrt.main(["--transport", "ble"])
            self.assertEqual(run.call_args.kwargs["transport_mode"], "ble")

    def test_main_accepts_ble(self):
        try:
            from hardware import main
        except ImportError as exc:  # pragma: no cover
            self.skipTest(str(exc))
        args = main.parse_args(["--transport", "ble"])
        self.assertEqual(args.transport, "ble")


if __name__ == "__main__":
    unittest.main()

"""Phone-side rules in tools/test_central.py and the offline mode of tools/fake_cart.py."""
import os
import subprocess
import sys
import unittest
from pathlib import Path

try:
    from hardware.smartcart_ble import protocol as p
    from hardware.smartcart_ble.tools.test_central import CentralLogic
except ImportError:
    from smartcart_ble import protocol as p
    from smartcart_ble.tools.test_central import CentralLogic

PKG = p.__name__.rsplit(".", 1)[0]
ROOT = Path(p.__file__).resolve().parents[len(PKG.split("."))]


class CentralLogicTests(unittest.TestCase):
    def test_no_events_before_ok_then_ack(self):
        c = CentralLogic("s1", "bayside-grocer")
        self.assertEqual(c.start(), b"START|s1|bayside-grocer")
        ev, ack, _ = c.on_event(b"1|A|apple|93")
        self.assertIsNone(ev)
        self.assertIsNone(ack)
        c.on_status(b"OK|START|s1")
        ev, ack, _ = c.on_event(b"1|A|apple|93")
        self.assertEqual((ev.seq, ack), (1, b"ACK|s1|1"))
        ev, ack, note = c.on_event(b"1|A|apple|93")
        self.assertEqual((ack, note), (b"ACK|s1|1", "duplicate, acked again"))
        self.assertEqual(c.seen, [1])

    def test_ok_for_other_session_does_not_confirm(self):
        c = CentralLogic("s1", "x")
        c.start()
        c.on_status(b"OK|START|s2")
        self.assertFalse(c.confirmed)
        c.on_status(b"S|s1|ACTIVE|0|0|SmartCart-0001")
        self.assertFalse(c.confirmed)

    def test_status_clears_confirmation_and_seen(self):
        c = CentralLogic("s1", "x")
        c.start()
        c.on_status(b"OK|START|s1")
        c.on_event(b"5|A|apple|9")
        c.on_status(b"S|s1|ACTIVE|5|0|SmartCart-0001")
        self.assertTrue(c.confirmed)
        c.on_status(b"S|s1|ACTIVE|1|0|SmartCart-0001")   # cart numbering went back: forget seen seqs
        self.assertEqual(c.seen, [])
        c.on_status(b"S|-|IDLE|0|0|SmartCart-0001")
        self.assertFalse(c.confirmed)
        c.on_status(b"OK|START|s1")
        c.on_status(b"S|s9|ACTIVE|0|0|SmartCart-0001")
        self.assertFalse(c.confirmed)

    def test_disconnect_needs_new_ok(self):
        c = CentralLogic("s1", "x")
        c.start()
        c.on_status(b"OK|START|s1")
        c.on_disconnect()
        self.assertIsNone(c.on_event(b"2|A|apple|9")[0])

    def test_no_ack_mode(self):
        c = CentralLogic("s1", "x", auto_ack=False)
        c.start()
        c.on_status(b"OK|START|s1")
        c.on_event(b"1|A|apple|9")
        c.on_event(b"2|R|apple|9")
        self.assertEqual(c.ack_pending(), [b"ACK|s1|1", b"ACK|s1|2"])
        self.assertEqual(c.ack_pending(), [])

    def test_reconcile(self):
        idle = p.Status(None, "IDLE", 0, 0, "SmartCart-0001")
        foreign = p.Status("zz", "ACTIVE", 3, 1, "SmartCart-0001")
        c = CentralLogic("s1", "x")
        self.assertIsNone(c.reconcile(idle))
        self.assertEqual(c.reconcile(foreign), b"STOP|zz")
        c.start()
        self.assertEqual(c.reconcile(foreign), b"START|s1|x")
        c.stop()
        self.assertEqual(c.reconcile(foreign), b"STOP|zz")


class FakeCartOffline(unittest.TestCase):
    def test_scripted_session(self):
        script = "\n".join(["a apple", "phone START|s1|bayside-grocer", "a apple", "r apple 80", "s",
                            "phone ACK|s1|1", "phone START|s1|bayside-grocer", "phone STOP|s1", "s", "q", ""])
        env = dict(os.environ, PYTHONPATH=str(ROOT) + os.pathsep + os.environ.get("PYTHONPATH", ""))
        out = subprocess.run([sys.executable, "-m", PKG + ".tools.fake_cart", "--no-ble", "--name", "SmartCart-TEST"],
                             input=script, capture_output=True, text=True, timeout=30, env=env, cwd=str(ROOT)).stdout
        lines = [l for l in out.splitlines() if l.startswith(("EVENTS", "STATUS", "S|", "dropped", "seq"))]
        self.assertEqual(lines, [
            "dropped: no active session",
            "STATUS -> OK|START|s1",
            "EVENTS -> 1|A|apple|93", "seq 1",
            "EVENTS -> 2|R|apple|80", "seq 2",
            "S|s1|ACTIVE|2|2|SmartCart-TEST",
            "STATUS -> OK|START|s1", "EVENTS -> 2|R|apple|80",
            "STATUS -> OK|STOP|s1",
            "S|-|IDLE|0|0|SmartCart-TEST",
        ], out)


if __name__ == "__main__":
    unittest.main()

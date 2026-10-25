"""SmartCart BLE contract v1.2: message encoding/decoding (pure, no Bluetooth)."""
import enum
import unittest

try:
    from hardware.smartcart_ble import protocol as p
except ImportError:  # standalone checkout: smartcart_ble at the top level
    from smartcart_ble import protocol as p


class ControlParsing(unittest.TestCase):
    def test_start(self):
        self.assertEqual(p.parse_control(b"START|s1k9|bayside-grocer"), p.Start("s1k9", "bayside-grocer"))

    def test_stop_ack_sync(self):
        self.assertEqual(p.parse_control(b"STOP|s1k9"), p.Stop("s1k9"))
        self.assertEqual(p.parse_control(b"ACK|s1k9|17"), p.Ack("s1k9", 17))
        self.assertEqual(p.parse_control(b"ACK|s1k9|999999"), p.Ack("s1k9", 999999))
        self.assertEqual(p.parse_control(b"SYNC"), p.Sync())

    def test_accepts_str_list_and_trailing_newline(self):
        self.assertEqual(p.parse_control("SYNC"), p.Sync())
        self.assertEqual(p.parse_control(list(b"STOP|a1")), p.Stop("a1"))
        self.assertEqual(p.parse_control(b"STOP|a1\r\n"), p.Stop("a1"))
        self.assertEqual(p.parse_control(b"SYNC\x00"), p.Sync())

    def assertErr(self, data, code):
        with self.assertRaises(p.ProtocolError) as cm:
            p.parse_control(data)
        self.assertEqual(cm.exception.code, code, data)
        self.assertLessEqual(len(cm.exception.detail), p.MAX_ERR_DETAIL)

    def test_bad_commands(self):
        for data in (b"", b"HELLO", b"start|s1|x", b"START|s1", b"START|s1|x|y", b"STOP", b"STOP|s1|x",
                     b"ACK|s1", b"ACK|s1|0", b"ACK|s1|1000000", b"ACK|s1|-1", b"ACK|s1|1.5", b"ACK|s1|abc",
                     b"SYNC|x", b"START|s1|Bad_Store", b"START|s1|" + b"a" * 33, b"\xff\xfe",
                     b"START|s1|" + b"a" * 60, b" SYNC", b"|"):
            self.assertErr(data, p.ERR_BAD_CMD)

    def test_bad_sids(self):
        for data in (b"START|S1|x", b"START||x", b"START|abcdefghijklm|x", b"STOP|a-b", b"ACK|a b|1",
                     b"STOP|-", "STOP|é".encode()):
            self.assertErr(data, p.ERR_BAD_SID)

    def test_limits_exact(self):
        self.assertEqual(p.parse_control(b"START|abcdefghijkl|" + b"a" * 32).sid, "abcdefghijkl")
        self.assertEqual(len(b"START|abcdefghijkl|" + b"a" * 32), 51)

    def test_encode_control_roundtrip(self):
        for cmd in (p.Start("s1", "bayside-grocer"), p.Stop("s1"), p.Ack("s1", 42), p.Sync()):
            self.assertEqual(p.parse_control(p.encode_control(cmd)), cmd)
        with self.assertRaises(ValueError):
            p.encode_control(p.Ack("s1", 0))


class Events(unittest.TestCase):
    def test_encode(self):
        self.assertEqual(p.encode_event(p.Event(17, "A", "apple", 93)), b"17|A|apple|93")
        self.assertEqual(p.encode_event(p.Event(18, "R", "apple", 89)), b"18|R|apple|89")
        self.assertEqual(p.encode_event(p.Event(1, "A", "cell phone", 0)), b"1|A|cell phone|0")

    def test_roundtrip_and_max_len(self):
        ev = p.Event(999999, "R", "a" * 20, 100)
        raw = p.encode_event(ev)
        self.assertLessEqual(len(raw), p.MAX_MESSAGE_BYTES)
        self.assertEqual(p.decode_event(raw), ev)

    def test_encode_rejects(self):
        for ev in (p.Event(0, "A", "apple", 1), p.Event(1, "X", "apple", 1), p.Event(1, "A", "Apple", 1),
                   p.Event(1, "A", "a|b", 1), p.Event(1, "A", "a" * 21, 1), p.Event(1, "A", "apple", 101),
                   p.Event(1, "A", "apple", True), p.Event(1, "A", "", 5)):
            with self.assertRaises(ValueError, msg=ev):
                p.encode_event(ev)

    def test_decode_rejects(self):
        for raw in (b"1|A|apple", b"x|A|apple|9", b"1|a|apple|9", b"1|A|apple|101", b"1|A|Apple|9", b"0|A|apple|9"):
            with self.assertRaises(p.ProtocolError, msg=raw):
                p.decode_event(raw)


class StatusMessages(unittest.TestCase):
    def test_status(self):
        st = p.Status("s1k9", "ACTIVE", 17, 0, "SmartCart-3F2A")
        self.assertEqual(p.encode_status(st), b"S|s1k9|ACTIVE|17|0|SmartCart-3F2A")
        self.assertEqual(p.decode_status_message(p.encode_status(st)), st)
        idle = p.Status(None, "IDLE", 0, 0, "SmartCart-3F2A")
        self.assertEqual(p.encode_status(idle), b"S|-|IDLE|0|0|SmartCart-3F2A")
        self.assertEqual(p.decode_status_message(b"S|-|IDLE|0|0|SmartCart-3F2A"), idle)

    def test_status_worst_case_fits(self):
        st = p.Status("a" * 12, "ACTIVE", 999999, 64, "N" * 20)
        self.assertLessEqual(len(p.encode_status(st)), p.MAX_MESSAGE_BYTES)

    def test_status_invalid(self):
        for st in (p.Status("s1", "IDLE", 0, 0, "x"), p.Status(None, "ACTIVE", 0, 0, "x"),
                   p.Status("s1", "BUSY", 0, 0, "x"), p.Status("s1", "ACTIVE", 0, 100, "x"),
                   p.Status("s1", "ACTIVE", 0, 0, "a|b")):
            with self.assertRaises(ValueError, msg=st):
                p.encode_status(st)
        for raw in (b"S|s1|IDLE|0|0|x", b"S|-|ACTIVE|0|0|x", b"S|s1|ACTIVE|0|0", b"Q|x"):
            with self.assertRaises(p.ProtocolError, msg=raw):
                p.decode_status_message(raw)

    def test_ok_err(self):
        self.assertEqual(p.encode_ok("START", "s1"), b"OK|START|s1")
        self.assertEqual(p.decode_status_message(b"OK|STOP|s1"), p.Ok("STOP", "s1"))
        raw = p.encode_err("BAD_CMD", "x|y" + "z" * 50)
        self.assertTrue(raw.startswith(b"ERR|BAD_CMD|x y"))
        self.assertEqual(len(raw.split(b"|")[2]), 30)
        self.assertEqual(p.decode_status_message(b"ERR|BUSY|internal"), p.Err("BUSY", "internal"))
        with self.assertRaises(ValueError):
            p.encode_err("NOPE", "x")


class Normalisation(unittest.TestCase):
    def test_action(self):
        class Kind(enum.Enum):
            ADD = "ADD"
            REMOVE = "REMOVE"
        for a in ("ADD", "add", "A", " a ", "+", Kind.ADD):
            self.assertEqual(p.normalize_action(a), "A")
        for a in ("REMOVE", "remove", "R", "-", Kind.REMOVE):
            self.assertEqual(p.normalize_action(a), "R")
        for a in ("MOVE", None, 1, ""):
            with self.assertRaises(ValueError):
                p.normalize_action(a)

    def test_confidence(self):
        cases = [(0.93, 93), (0.934, 93), (0.935, 94), (1.0, 100), (0.0, 0), (93, 93), (100, 100), (1, 1),
                 (93.4, 93), (150, 100), (-3, 0), (None, 100), ("0.8", 80), ("87%", 87), (float("inf"), 100)]
        for raw, want in cases:
            self.assertEqual(p.normalize_confidence(raw), want, raw)
        for bad in (True, float("nan"), "x", [1]):
            with self.assertRaises(ValueError, msg=bad):
                p.normalize_confidence(bad)

    def test_numpy_like_float(self):
        class F(float):
            pass
        self.assertEqual(p.normalize_confidence(F(0.5)), 50)

    def test_label(self):
        self.assertEqual(p.normalize_label(" Apple "), "apple")
        self.assertEqual(p.normalize_label("Cell Phone"), "cell phone")
        for bad in ("", "  ", "a|b", "a" * 21, "a\nb", 5):
            with self.assertRaises(ValueError, msg=bad):
                p.normalize_label(bad)

    def test_device_name(self):
        self.assertEqual(p.device_name_from_address("AA:BB:CC:DD:3f:2a"), "SmartCart-3F2A")
        self.assertEqual(p.device_name_from_address("00:1A:7D:DA:71:13"), "SmartCart-7113")
        with self.assertRaises(ValueError):
            p.device_name_from_address("")
        self.assertTrue(p.valid_device_name("SmartCart-3F2A"))
        self.assertFalse(p.valid_device_name("SmartCart|3F2A"))
        self.assertFalse(p.valid_device_name("x" * 21))

    def test_uuids(self):
        self.assertEqual(p.SERVICE_UUID, "7ccd0001-d416-4afc-a97d-f7c98e1afca9")
        self.assertEqual(p.CONTROL_UUID, "7ccd0002-d416-4afc-a97d-f7c98e1afca9")
        self.assertEqual(p.EVENTS_UUID, "7ccd0003-d416-4afc-a97d-f7c98e1afca9")
        self.assertEqual(p.STATUS_UUID, "7ccd0004-d416-4afc-a97d-f7c98e1afca9")


if __name__ == "__main__":
    unittest.main()

"""SmartCart BLE contract v1.2: the Jetson session state machine with a fake clock."""
import unittest

try:
    from hardware.smartcart_ble import protocol as p
    from hardware.smartcart_ble import session as sm
except ImportError:  # standalone checkout: smartcart_ble at the top level
    from smartcart_ble import protocol as p
    from smartcart_ble import session as sm


NAME = "SmartCart-3F2A"


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def texts(fx, channel=None):
    return [o.payload.decode() for o in fx.out if channel is None or o.channel == channel]


class SessionTests(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.s = sm.CartSession(NAME, clock=self.clock)

    def start(self, sid="s1", store="bayside-grocer"):
        return self.s.handle_control(f"START|{sid}|{store}".encode())

    def test_idle_status_and_drop(self):
        self.assertEqual(self.s.status_bytes(), b"S|-|IDLE|0|0|SmartCart-3F2A")
        seq, fx = self.s.publish("ADD", "apple", 0.93)
        self.assertIsNone(seq)
        self.assertEqual(fx.out, [])
        self.assertEqual(self.s.last_seq, 0)

    def test_start_publish_ack_stop(self):
        fx = self.start()
        self.assertEqual(texts(fx), ["OK|START|s1"])
        self.assertEqual([(n.kind, n.sid, n.resumed) for n in fx.notices], [("start", "s1", False)])
        seq, fx = self.s.publish("ADD", "apple", 0.93)
        self.assertEqual((seq, texts(fx, sm.EVENTS)), (1, ["1|A|apple|93"]))
        seq, fx = self.s.publish("REMOVE", "apple", 89)
        self.assertEqual((seq, texts(fx)), (2, ["2|R|apple|89"]))
        self.assertEqual(self.s.status_bytes(), b"S|s1|ACTIVE|2|2|SmartCart-3F2A")
        self.assertEqual(self.s.handle_control(b"ACK|s1|1").out, [])
        self.assertEqual(self.s.status_bytes(), b"S|s1|ACTIVE|2|1|SmartCart-3F2A")
        fx = self.s.handle_control(b"STOP|s1")
        self.assertEqual(texts(fx), ["OK|STOP|s1"])
        self.assertEqual([(n.kind, n.reason) for n in fx.notices], [("stop", "phone")])
        self.assertEqual(self.s.status_bytes(), b"S|-|IDLE|0|0|SmartCart-3F2A")
        self.assertIsNone(self.s.publish("ADD", "apple", 1)[0])

    def test_resume_sends_ok_first_then_unacked_in_order(self):
        self.start()
        for label in ("apple", "banana", "bottle"):
            self.s.publish("ADD", label, 0.9)
        self.s.handle_control(b"ACK|s1|2")
        fx = self.start()
        self.assertEqual(texts(fx), ["OK|START|s1", "1|A|apple|90", "3|A|bottle|90"])
        self.assertEqual([o.channel for o in fx.out], [sm.STATUS, sm.EVENTS, sm.EVENTS])
        self.assertTrue(fx.notices[0].resumed)
        seq, _ = self.s.publish("ADD", "orange", 0.9)
        self.assertEqual(seq, 4)                       # counter kept across resume

    def test_resume_updates_store(self):
        self.start(store="a")
        self.start(store="b")
        self.assertEqual(self.s.store_id, "b")

    def test_new_session_replaces_old(self):
        self.start("s1")
        self.s.publish("ADD", "apple", 0.9)
        fx = self.start("s2")
        self.assertEqual(texts(fx), ["OK|START|s2"])
        self.assertEqual([(n.kind, n.sid, n.reason) for n in fx.notices[:1]], [("stop", "s1", "replaced")])
        self.assertEqual((fx.notices[1].kind, fx.notices[1].sid, fx.notices[1].resumed), ("start", "s2", False))
        self.assertEqual(self.s.status_bytes(), b"S|s2|ACTIVE|0|0|SmartCart-3F2A")
        self.assertEqual(self.s.publish("ADD", "apple", 0.9)[0], 1)

    def test_stop_idempotent_and_other_sid(self):
        self.assertEqual(texts(self.s.handle_control(b"STOP|zz")), ["OK|STOP|zz"])
        self.start("s1")
        fx = self.s.handle_control(b"STOP|zz")
        self.assertEqual(texts(fx), ["OK|STOP|zz"])
        self.assertEqual(fx.notices, [])
        self.assertTrue(self.s.active)                 # a STOP for another sid does not end s1
        self.s.handle_control(b"STOP|s1")
        self.assertEqual(texts(self.s.handle_control(b"STOP|s1")), ["OK|STOP|s1"])

    def test_ack_edge_cases(self):
        self.start("s1")
        self.s.publish("ADD", "apple", 0.9)
        for data in (b"ACK|other|1", b"ACK|s1|5", b"ACK|s1|1", b"ACK|s1|1"):
            self.assertEqual(self.s.handle_control(data).out, [])
        self.assertEqual(self.s.status().unacked, 0)
        self.s.handle_control(b"STOP|s1")
        self.assertEqual(self.s.handle_control(b"ACK|s1|1").out, [])

    def test_sync_and_errors(self):
        self.assertEqual(texts(self.s.handle_control(b"SYNC")), ["S|-|IDLE|0|0|SmartCart-3F2A"])
        self.assertEqual(texts(self.s.handle_control(b"HELLO")), ["ERR|BAD_CMD|unknown command"])
        self.assertEqual(texts(self.s.handle_control(b"START|BAD|x")), ["ERR|BAD_SID|bad session id"])
        self.assertEqual(texts(self.s.handle_control(b"\xff")), ["ERR|BAD_CMD|not UTF-8"])
        self.assertFalse(self.s.active)

    def test_buffer_keeps_last_64(self):
        self.start()
        for _ in range(70):
            self.s.publish("ADD", "apple", 0.5)
        self.assertEqual(self.s.status().unacked, 64)
        self.assertEqual([e.seq for e in self.s.unacked][:2], [7, 8])
        self.assertEqual(self.s.dropped_overflow, 6)
        fx = self.start()
        self.assertEqual(len(fx.out), 65)
        self.assertEqual(fx.out[1].payload, b"7|A|apple|50")

    def test_seq_limit(self):
        self.start()
        self.s.last_seq = 999998
        self.assertEqual(self.s.publish("ADD", "apple", 1)[0], 999999)
        self.assertIsNone(self.s.publish("ADD", "apple", 1)[0])

    def test_bad_publish_input_raises(self):
        self.start()
        with self.assertRaises(ValueError):
            self.s.publish("MOVE", "apple", 1)
        with self.assertRaises(ValueError):
            self.s.publish("ADD", "a|b", 1)
        self.assertEqual(self.s.last_seq, 0)

    def test_stale_after_30_min_without_phone(self):
        self.s.set_phone_present(True)
        self.start()
        self.s.set_phone_present(False)
        self.clock.t += 29 * 60
        self.assertEqual(self.s.tick().out, [])
        self.s.set_phone_present(True)             # reconnect resets the timer
        self.clock.t += 60 * 60
        self.assertEqual(self.s.tick().out, [])
        self.s.set_phone_present(False)
        self.clock.t += 30 * 60 - 1
        self.assertTrue(self.s.active and self.s.tick().out == [])
        self.clock.t += 1
        fx = self.s.tick()
        self.assertEqual(texts(fx), ["S|-|IDLE|0|0|SmartCart-3F2A"])
        self.assertEqual([(n.kind, n.reason) for n in fx.notices], [("stop", "stale")])
        self.assertFalse(self.s.active)

    def test_stale_counts_from_start_when_phone_unknown(self):
        self.clock.t += 3 * 3600                   # long idle before the START
        self.start()
        self.clock.t += 60
        self.assertEqual(self.s.tick().out, [])
        self.clock.t += 30 * 60
        self.assertFalse(self.s.active if self.s.tick() else True)

    def test_idle_never_stale(self):
        self.clock.t += 10 * 3600
        self.assertEqual(self.s.tick().out, [])

    def test_stop_local(self):
        self.assertEqual(self.s.stop_local().out, [])
        self.start()
        fx = self.s.stop_local("shutdown")
        self.assertEqual(texts(fx), ["S|-|IDLE|0|0|SmartCart-3F2A"])
        self.assertEqual(fx.notices[0].reason, "shutdown")

    def test_same_sid_after_loss_continues_numbering(self):
        self.start("s1")
        self.s.publish("ADD", "apple", 1)
        self.s.publish("ADD", "apple", 1)
        self.clock.t += 31 * 60
        self.s.tick()                                   # stale stop: cart forgets s1
        self.assertFalse(self.s.active)
        fx = self.start("s1")                           # phone still has s1
        self.assertFalse(fx.notices[0].resumed)
        self.assertEqual(self.s.status().unacked, 0)
        self.assertEqual(self.s.publish("ADD", "apple", 1)[0], 3)   # not 1: backend already has s1:1, s1:2
        self.start("s2")
        self.assertEqual(self.s.publish("ADD", "apple", 1)[0], 1)

    def test_seq_memory_survives_process_restart(self):
        import os
        import tempfile
        path = os.path.join(tempfile.mkdtemp(), "state", "sessions.json")
        a = sm.CartSession(NAME, memory=sm.SeqMemory(path))
        a.handle_control(b"START|s1|x")
        for _ in range(5):
            a.publish("ADD", "apple", 1)
        b = sm.CartSession(NAME, memory=sm.SeqMemory(path))      # new process
        b.handle_control(b"START|s1|x")
        self.assertEqual(b.publish("ADD", "apple", 1)[0], 6)

    def test_seq_memory_bad_file_and_limit(self):
        import os
        import tempfile
        path = os.path.join(tempfile.mkdtemp(), "sessions.json")
        with open(path, "w") as fh:
            fh.write("{not json")
        with self.assertLogs("smartcart_ble", "WARNING"):
            m = sm.SeqMemory(path)
        self.assertEqual(m.get("s1"), 0)
        for i in range(40):
            m.put("s%d" % i, i + 1)
        self.assertEqual(m.get("s0"), 0)                # oldest forgotten
        self.assertEqual(sm.SeqMemory(path).get("s39"), 40)

    def test_busy_while_stopping(self):
        self.start("s1")
        self.s.stopping = True
        self.assertEqual(texts(self.s.handle_control(b"START|s2|x")), ["ERR|BUSY|cart shutting down"])
        self.assertEqual(self.s.sid, "s1")

    def test_bad_construction(self):
        with self.assertRaises(ValueError):
            sm.CartSession("a|b")
        with self.assertRaises(ValueError):
            sm.CartSession(NAME, buffer_size=100)


if __name__ == "__main__":
    unittest.main()

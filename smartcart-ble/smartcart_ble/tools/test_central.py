"""Phone simulator for SmartCart (contract v1.2): test the Jetson without the Bag It app.

Runs on any laptop with Bluetooth (Linux/BlueZ, macOS, Windows): pip install bleak
    python3 -m hardware.smartcart_ble.tools.test_central                 # interactive
    python3 -m hardware.smartcart_ble.tools.test_central --resume-test   # automated reconnect/resume test
It scans by service UUID, connects, checks the MTU, subscribes STATUS + EVENTS, reads STATUS and
writes START, then prints and ACKs every event. No backend is involved: ACK means "printed".

Interactive commands: start [sid] [store] | stop | sync | status | ack on|off | reconnect | q
"""
from __future__ import annotations

import argparse
import asyncio
import random
import string
import sys
import time
from typing import List, Optional, Tuple

try:                                   # run as module of the package, or as a loose file
    from .. import protocol as p
except ImportError:                    # pragma: no cover
    import protocol as p  # type: ignore


def new_sid() -> str:
    return "t" + "".join(random.choice(string.ascii_lowercase + string.digits) for _ in range(7))


class CentralLogic:
    """Phone-side rules of the contract, without Bluetooth (unit-tested).

    - no events are forwarded or acked before OK|START|<our sid> (since our last START)
    - events are de-duplicated by (sid, seq); duplicates are acked again so the cart drops them
    - reconcile after (re)connect: our session -> START (resume); cart ACTIVE with a foreign sid -> STOP
    """

    def __init__(self, sid: str, store: str, auto_ack: bool = True):
        if not p.valid_sid(sid) or not p.valid_store_id(store):
            raise ValueError("bad sid or store")
        self.sid, self.store, self.auto_ack = sid, store, auto_ack
        self.confirmed = False
        self.want_session = False
        self.seen: List[int] = []
        self.received: List[p.Event] = []           # every event accepted after OK, incl. duplicates
        self.unacked: List[int] = []                # forwarded but not acked (auto_ack off)
        self.last_status: Optional[p.Status] = None
        self.log: List[str] = []

    def start(self) -> bytes:
        self.confirmed = False
        self.want_session = True
        return p.encode_control(p.Start(self.sid, self.store))

    def stop(self) -> bytes:
        self.want_session = False
        self.confirmed = False
        return p.encode_control(p.Stop(self.sid))

    def on_disconnect(self):
        self.confirmed = False

    def on_status(self, data) -> p.StatusMessage:
        msg = p.decode_status_message(data)
        if isinstance(msg, p.Status):
            self.last_status = msg
            if msg.state != p.ACTIVE or msg.sid != self.sid:
                self.confirmed = False          # cart lost / ended / replaced our session
            elif self.seen and msg.last_seq < max(self.seen):
                self.seen = []                  # cart restarted numbering: old seqs are not duplicates
        elif isinstance(msg, p.Ok) and msg.command == "START" and msg.sid == self.sid:
            self.confirmed = True
        elif isinstance(msg, p.Ok) and msg.command == "STOP" and msg.sid == self.sid:
            self.confirmed = False
        return msg

    def on_event(self, data) -> Tuple[Optional[p.Event], Optional[bytes], str]:
        """Returns (event or None, ACK to write or None, note)."""
        ev = p.decode_event(data)
        if not self.confirmed:
            note = f"ignored {ev.seq} (no OK|START yet)"
            self.log.append(note)
            return None, None, note
        dup = ev.seq in self.seen
        if not dup:
            self.seen.append(ev.seq)
        self.received.append(ev)
        if not self.auto_ack:
            if ev.seq not in self.unacked:
                self.unacked.append(ev.seq)
            return ev, None, "duplicate (not acked)" if dup else "new (not acked)"
        return ev, p.encode_control(p.Ack(self.sid, ev.seq)), "duplicate, acked again" if dup else "new, acked"

    def ack_pending(self) -> List[bytes]:
        out = [p.encode_control(p.Ack(self.sid, s)) for s in self.unacked]
        self.unacked = []
        return out

    def reconcile(self, status: p.Status) -> Optional[bytes]:
        if self.want_session:
            return self.start()
        if status.state == p.ACTIVE and status.sid:
            return p.encode_control(p.Stop(status.sid))
        return None


class Central:
    def __init__(self, args):
        self.args = args
        self.logic = CentralLogic(args.sid, args.store, auto_ack=not args.no_ack)
        self.client = None
        self.device = None
        self.status_q: asyncio.Queue = asyncio.Queue()
        self.event_q: asyncio.Queue = asyncio.Queue()
        self._write_lock: Optional[asyncio.Lock] = None     # BlueZ refuses overlapping writes (InProgress)

    async def find(self):
        from bleak import BleakScanner
        if self.args.address:
            self.device = await BleakScanner.find_device_by_address(self.args.address, timeout=self.args.scan)
        else:
            print(f"scanning {self.args.scan:.0f} s for service {p.SERVICE_UUID} ...")
            self.device = await BleakScanner.find_device_by_filter(
                lambda d, ad: p.SERVICE_UUID in [u.lower() for u in (ad.service_uuids or [])],
                timeout=self.args.scan)
        if self.device is None:
            raise SystemExit("no SmartCart found (is fake_cart / the scanner running and advertising?)")
        print(f"found {self.device.name or '?'} {self.device.address}")

    async def connect(self):
        from bleak import BleakClient
        self.client = BleakClient(self.device, disconnected_callback=lambda c: print("[disconnected]"))
        await self.client.connect(timeout=20)
        mtu = await self._mtu()
        print(f"connected, ATT MTU {mtu}" + ("" if mtu >= p.MIN_MTU else f"  << below {p.MIN_MTU}: messages may be cut"))
        await self.client.start_notify(p.STATUS_UUID, self._on_status)
        await self.client.start_notify(p.EVENTS_UUID, self._on_event)
        raw = await self.client.read_gatt_char(p.STATUS_UUID)
        st = self.logic.on_status(bytes(raw))
        print(f"STATUS read: {bytes(raw).decode()}")
        return st

    async def _mtu(self) -> int:
        backend = getattr(self.client, "_backend", None)
        acquire = getattr(backend, "_acquire_mtu", None)     # BlueZ: real value needs this
        if acquire is not None:
            try:
                await acquire()
            except Exception:
                pass
        return int(getattr(self.client, "mtu_size", 23) or 23)

    def _on_status(self, _char, data: bytearray):
        try:
            msg = self.logic.on_status(bytes(data))
        except p.ProtocolError as exc:
            print(f"STATUS ?? {bytes(data)!r} ({exc})")
            return
        print(f"STATUS <- {bytes(data).decode()}")
        self.status_q.put_nowait(msg)

    def _on_event(self, _char, data: bytearray):
        try:
            ev, ack, note = self.logic.on_event(bytes(data))
        except p.ProtocolError as exc:
            print(f"EVENTS ?? {bytes(data)!r} ({exc})")
            return
        print(f"EVENTS <- {bytes(data).decode()}   [{note}]")
        if ev is not None:
            self.event_q.put_nowait(ev)
        if ack is not None:
            asyncio.ensure_future(self.write(ack))

    async def write(self, data: bytes):
        if self._write_lock is None:
            self._write_lock = asyncio.Lock()
        async with self._write_lock:
            print(f"CONTROL -> {data.decode()}")
            await self.client.write_gatt_char(p.CONTROL_UUID, data, response=True)

    async def wait_status(self, pred, timeout=10.0):
        end = time.monotonic() + timeout
        while True:
            left = end - time.monotonic()
            if left <= 0:
                return None
            try:
                msg = await asyncio.wait_for(self.status_q.get(), left)
            except asyncio.TimeoutError:
                return None
            if pred(msg):
                return msg

    async def disconnect(self):
        if self.client and self.client.is_connected:
            await self.client.disconnect()
        self.logic.on_disconnect()

    # -- interactive -----------------------------------------------------------------------
    async def interactive(self):
        await self.find()
        st = await self.connect()
        if isinstance(st, p.Status) and st.state == p.ACTIVE and st.sid != self.logic.sid:
            print(f"cart is ACTIVE with another session ({st.sid}); 'start' replaces it, or type 'stop {st.sid}'")
        await self.write(self.logic.start())
        loop = asyncio.get_running_loop()
        while True:
            line = (await loop.run_in_executor(None, sys.stdin.readline))
            if not line:
                break
            parts = line.split()
            if not parts:
                continue
            cmd = parts[0]
            if cmd == "q":
                break
            if cmd == "start":
                if len(parts) > 1:
                    self.logic.sid = parts[1]
                    self.logic.seen = []
                if len(parts) > 2:
                    self.logic.store = parts[2]
                await self.write(self.logic.start())
            elif cmd == "stop":
                sid = parts[1] if len(parts) > 1 else self.logic.sid
                if sid == self.logic.sid:
                    await self.write(self.logic.stop())
                else:
                    await self.write(p.encode_control(p.Stop(sid)))
            elif cmd == "sync":
                await self.write(b"SYNC")
            elif cmd == "status":
                print(bytes(await self.client.read_gatt_char(p.STATUS_UUID)).decode())
            elif cmd == "ack" and len(parts) > 1:
                self.logic.auto_ack = parts[1] == "on"
                for a in (self.logic.ack_pending() if self.logic.auto_ack else []):
                    await self.write(a)
            elif cmd == "reconnect":
                await self.disconnect()
                await asyncio.sleep(2)
                st = await self.connect()
                cmd_bytes = self.logic.reconcile(st) if isinstance(st, p.Status) else None
                if cmd_bytes:
                    await self.write(cmd_bytes)
            else:
                print(__doc__)
        await self.disconnect()

    # -- automated resume test ------------------------------------------------------------------
    async def resume_test(self) -> bool:
        results = []

        def check(name, ok, detail=""):
            results.append(ok)
            print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  ({detail})" if detail else ""))
            return ok

        await self.find()
        await self.connect()
        self.logic.auto_ack = False
        await self.write(self.logic.start())
        ok = await self.wait_status(lambda m: isinstance(m, p.Ok) and m.command == "START" and m.sid == self.logic.sid)
        check("START answered OK|START", ok is not None)
        need = self.args.events
        print(f"now make {need} crossings on the cart (fake_cart: 'a apple'; or run fake_cart --demo) ...")
        got = []
        end = time.monotonic() + self.args.wait
        while len(got) < need and time.monotonic() < end:
            try:
                got.append(await asyncio.wait_for(self.event_q.get(), max(0.1, end - time.monotonic())))
            except asyncio.TimeoutError:
                break
        check(f"received {need} events", len(got) >= need, f"got {len(got)}")
        seqs = [e.seq for e in got]
        check("seq increases by 1", seqs == list(range(seqs[0], seqs[0] + len(seqs))) if seqs else False, str(seqs))
        await self.write(b"SYNC")
        st = await self.wait_status(lambda m: isinstance(m, p.Status))
        check("STATUS shows them unacked", isinstance(st, p.Status) and st.unacked >= len(got),
              st and f"unacked={st.unacked}")
        await self.disconnect()
        await asyncio.sleep(3)
        st = await self.connect()
        check("after reconnect cart still ACTIVE with our sid",
              isinstance(st, p.Status) and st.state == p.ACTIVE and st.sid == self.logic.sid, str(st))
        while not self.event_q.empty():
            self.event_q.get_nowait()
        self.logic.auto_ack = True
        await self.write(self.logic.reconcile(st))
        ok = await self.wait_status(lambda m: isinstance(m, p.Ok) and m.command == "START")
        check("resume answered OK|START", ok is not None)
        resent = []
        end = time.monotonic() + 5
        while len(resent) < len(got) and time.monotonic() < end:
            try:
                resent.append(await asyncio.wait_for(self.event_q.get(), max(0.1, end - time.monotonic())))
            except asyncio.TimeoutError:
                break
        check("unacked events resent after OK, same seqs", [e.seq for e in resent][:len(seqs)] == seqs,
              f"{[e.seq for e in resent]}")
        await asyncio.sleep(1)
        await self.write(b"SYNC")
        st = await self.wait_status(lambda m: isinstance(m, p.Status))
        check("ACKs cleared the buffer", isinstance(st, p.Status) and st.unacked == 0, st and f"unacked={st.unacked}")
        await self.write(self.logic.stop())
        ok = await self.wait_status(lambda m: isinstance(m, p.Ok) and m.command == "STOP")
        check("STOP answered OK|STOP", ok is not None)
        raw = await self.client.read_gatt_char(p.STATUS_UUID)
        st = p.decode_status_message(bytes(raw))
        check("cart IDLE after STOP", isinstance(st, p.Status) and st.state == p.IDLE, bytes(raw).decode())
        await self.write(self.logic.stop())
        ok = await self.wait_status(lambda m: isinstance(m, p.Ok) and m.command == "STOP")
        check("second STOP still OK (idempotent)", ok is not None)
        await self.disconnect()
        print("RESULT:", "PASS" if all(results) else "FAIL")
        return all(results)


def main(argv=None):
    ap = argparse.ArgumentParser(description="Bag It phone simulator for a SmartCart (needs: pip install bleak)")
    ap.add_argument("--address", help="connect to this address instead of scanning by service UUID")
    ap.add_argument("--sid", default=new_sid(), help="session id to START (default random)")
    ap.add_argument("--store", default="bayside-grocer")
    ap.add_argument("--scan", type=float, default=20.0, help="scan timeout in seconds")
    ap.add_argument("--no-ack", action="store_true", help="do not ACK events (they stay in the cart's buffer)")
    ap.add_argument("--resume-test", action="store_true", help="automated disconnect/resume/ACK/STOP test")
    ap.add_argument("--events", type=int, default=2, help="events to wait for in --resume-test")
    ap.add_argument("--wait", type=float, default=120.0, help="seconds to wait for those events")
    args = ap.parse_args(argv)
    try:
        import bleak  # noqa: F401
    except ImportError:
        print("bleak is not installed: pip install bleak", file=sys.stderr)
        return 2
    c = Central(args)
    if args.resume_test:
        return 0 if asyncio.run(c.resume_test()) else 1
    asyncio.run(c.interactive())
    return 0


if __name__ == "__main__":
    sys.exit(main())

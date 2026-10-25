"""Fake SmartCart: the real BLE peripheral without a camera or YOLO.

Run on the Jetson (or any Linux machine with Bluetooth + BlueZ):
    sudo python3 -m hardware.smartcart_ble.tools.fake_cart            # interactive
    sudo python3 -m hardware.smartcart_ble.tools.fake_cart --demo     # scripted trip, repeats
Then open Bag It (or tools/test_central.py on a laptop) and walk into a store.

Commands (stdin):
    a apple [93]      ADD apple (confidence percent, default 93)
    r apple [89]      REMOVE apple
    s                 show STATUS
    demo              run the scripted sequence once
    stop              end the session from the cart side
    q                 quit
--no-ble runs the same session logic with no Bluetooth at all; the phone is typed too:
    phone START|s1|bayside     phone SYNC     phone ACK|s1|1     connect / disconnect
"""
from __future__ import annotations

import argparse
import logging
import sys
import threading
import time

from .. import protocol as p
from ..session import EVENTS, CartSession

DEMO = [("A", "apple"), ("A", "banana"), ("A", "bottle"), ("R", "apple"), ("A", "orange"), ("A", "apple")]


def _parse_item(parts, default_conf):
    if len(parts) < 2:
        raise ValueError("usage: a <label> [confidence]   e.g. a apple 93")
    conf = default_conf
    words = parts[1:]
    if len(words) > 1 and words[-1].isdigit():
        conf = int(words[-1])
        words = words[:-1]
    return " ".join(words), conf


class _OfflineCart:
    """CartSession + printing, for --no-ble (protocol rehearsal without Bluetooth)."""

    def __init__(self, name):
        self.session = CartSession(name)
        self.lock = threading.Lock()

    def _emit(self, fx):
        for o in fx.out:
            print(("EVENTS -> " if o.channel == EVENTS else "STATUS -> ") + o.payload.decode(), flush=True)
        for n in fx.notices:
            print(f"[session {n.kind}] {n.sid} {n.reason or ('resumed' if n.resumed else 'new')}", flush=True)

    def publish(self, action, label, conf):
        with self.lock:
            seq, fx = self.session.publish(action, label, conf)
            self._emit(fx)
        return seq

    def phone(self, text):
        with self.lock:
            self.session.set_phone_present(True)
            self._emit(self.session.handle_control(text.encode()))

    def connect(self, on):
        with self.lock:
            self.session.set_phone_present(on)

    def tick(self):
        with self.lock:
            self._emit(self.session.tick())

    @property
    def status(self):
        with self.lock:
            return self.session.status()

    @property
    def active(self):
        return self.session.active

    def stop_session(self):
        with self.lock:
            self._emit(self.session.stop_local())

    def stop(self):
        pass


def run_demo(cart, conf, delay, stop_event, loop):
    while not stop_event.is_set():
        if not cart.active:
            print("demo: waiting for a shopping session (walk into a store with the phone)...", flush=True)
            while not cart.active and not stop_event.wait(1):
                pass
        for action, label in DEMO:
            if stop_event.wait(delay) or not cart.active:
                break
            seq = cart.publish(action, label, conf)
            print(f"demo: {'ADD' if action == 'A' else 'REMOVE'} {label} -> seq {seq}", flush=True)
        if not loop:
            return
        stop_event.wait(delay * 2)


def main(argv=None):
    ap = argparse.ArgumentParser(description="SmartCart peripheral without YOLO, for testing the phone app.")
    ap.add_argument("--adapter", help="hci0 (default: first adapter)")
    ap.add_argument("--name", help="advertised name (default SmartCart-XXXX from the adapter address)")
    ap.add_argument("--demo", action="store_true", help="run the scripted trip whenever a session is active")
    ap.add_argument("--once", action="store_true", help="with --demo: run it once instead of repeating")
    ap.add_argument("--delay", type=float, default=4.0, help="seconds between demo events (default 4)")
    ap.add_argument("--confidence", type=int, default=93, help="confidence percent for typed/demo events")
    ap.add_argument("--no-ble", action="store_true", help="no Bluetooth: rehearse the protocol on stdin")
    ap.add_argument("--state-file", help="where the last seq per session is kept "
                                         "(default ~/.local/state/smartcart-ble/sessions.json)")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s", datefmt="%H:%M:%S")

    if args.no_ble:
        cart = _OfflineCart(args.name or "SmartCart-TEST")
        print("offline cart (no Bluetooth). Type 'phone START|s1|bayside' to start a session.", flush=True)
    else:
        from ..peripheral import SmartCartBLE, SmartCartError
        extra = {"state_file": args.state_file} if args.state_file else {}
        cart = SmartCartBLE(adapter=args.adapter, name=args.name, **extra,
                            on_session_start=lambda sid, store, resumed: print(
                                f"[session] {'resumed' if resumed else 'started'} {sid} at {store}", flush=True),
                            on_session_stop=lambda sid, why: print(f"[session] {sid} ended ({why})", flush=True))
        try:
            cart.start()
        except SmartCartError as exc:
            print(f"Bluetooth setup failed: {exc}", file=sys.stderr)
            print("Run tools/check_adapter.sh and see README 'Troubleshooting'.", file=sys.stderr)
            return 2
        print(f"{cart.name} is advertising service {p.SERVICE_UUID}. Type 'help' for commands.", flush=True)

    stop_event = threading.Event()
    if args.demo:
        threading.Thread(target=run_demo, args=(cart, args.confidence, args.delay, stop_event, not args.once),
                         daemon=True).start()
    quit_requested = False
    try:
        for line in sys.stdin:
            parts = line.strip().split()
            if not parts:
                continue
            cmd = parts[0].lower()
            try:
                if cmd in ("q", "quit", "exit"):
                    quit_requested = True
                    break
                if cmd in ("a", "add", "r", "remove", "rm"):
                    label, conf = _parse_item(parts, args.confidence)
                    seq = cart.publish("A" if cmd in ("a", "add") else "R", label, conf)
                    print(f"seq {seq}" if seq else "dropped: no active session", flush=True)
                elif cmd in ("s", "status"):
                    print(p.encode_status(cart.status).decode(), flush=True)
                elif cmd == "demo":
                    threading.Thread(target=run_demo, args=(cart, args.confidence, args.delay, stop_event, False),
                                     daemon=True).start()
                elif cmd == "stop":
                    if args.no_ble:
                        cart.stop_session()
                    else:
                        print("the phone ends sessions (STOP); restart this tool to reset the cart", flush=True)
                elif args.no_ble and cmd == "phone" and len(parts) == 2:
                    cart.phone(parts[1])
                elif args.no_ble and cmd in ("connect", "disconnect"):
                    cart.connect(cmd == "connect")
                elif args.no_ble and cmd == "tick":
                    cart.tick()
                else:
                    print(__doc__, flush=True)
            except ValueError as exc:
                print(f"error: {exc}", flush=True)
        if args.demo and not quit_requested:
            print("stdin closed; demo keeps running (Ctrl-C to quit)", flush=True)
            while True:
                time.sleep(3600)
    except KeyboardInterrupt:
        pass
    stop_event.set()
    cart.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())

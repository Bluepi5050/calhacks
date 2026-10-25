"""SmartCartBLE in a child process, with the same API (start / publish / active / status / listeners / stop).

Why: dbus-python runs its GLib main loop on GLib's *default* context. OpenCV's GTK preview
(cv2.imshow / cv2.waitKey) iterates that same context from the scanner thread and blocks forever
while another thread owns it. In a child process the scanner never imports GLib, so --preview and
Bluetooth can run together. BleTransport uses this by default.

Protocol between the processes: JSON lines. Parent -> child on stdin, child -> parent on stdout;
the child's log goes to stderr (your terminal / journal).
    parent: {"op": "publish", "id": 7, "action": "A", "label": "apple", "confidence": 93}   {"op": "stop"}
    child:  {"ev": "ready", "name": ..., "status": ...}   {"ev": "error", "message": ...}
            {"ev": "seq", "id": 7, "seq": 12}   {"ev": "start", "sid", "store_id", "resumed", "status"}
            {"ev": "stop", "sid", "reason", "status"}   {"ev": "status", "status": ...}
"""
from __future__ import annotations

import json
import logging
import os
import subprocess
import sys
import threading
import time
from typing import Callable, List, Optional

from . import protocol as p

log = logging.getLogger("smartcart_ble")

PUBLISH_TIMEOUT_S = 2.0


def _status_dict(st: Optional[p.Status]):
    if st is None:
        return None
    return {"sid": st.sid, "state": st.state, "last_seq": st.last_seq, "unacked": st.unacked,
            "device_name": st.device_name}


class SmartCartProcess:
    """Drop-in for SmartCartBLE that runs it in `python -m <package>.process`."""

    def __init__(self, **kwargs):
        self._kwargs = {k: v for k, v in kwargs.items() if v is not None or k == "state_file"}
        self._start_listeners: List[Callable] = []
        self._stop_listeners: List[Callable] = []
        for key, lst in (("on_session_start", self._start_listeners), ("on_session_stop", self._stop_listeners)):
            fn = self._kwargs.pop(key, None)
            if fn:
                lst.append(fn)
        self._proc: Optional[subprocess.Popen] = None
        self._lock = threading.Lock()
        self._cond = threading.Condition(self._lock)
        self._replies = {}
        self._next_id = 0
        self._ready = threading.Event()
        self._error: Optional[str] = None
        self._status: Optional[p.Status] = None
        self._alive = False
        self.name: Optional[str] = None

    def add_session_start_listener(self, fn):
        self._start_listeners.append(fn)

    def add_session_stop_listener(self, fn):
        self._stop_listeners.append(fn)

    # -- lifecycle ------------------------------------------------------------------------------
    def start(self, timeout: float = 25.0) -> "SmartCartProcess":
        if self._proc is not None:
            return self
        from .peripheral_errors import SmartCartError
        pkg = __name__.rsplit(".", 1)[0]
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        for _ in pkg.split(".")[1:]:
            root = os.path.dirname(root)
        env = dict(os.environ, PYTHONUNBUFFERED="1")
        env["PYTHONPATH"] = root + (os.pathsep + env["PYTHONPATH"] if env.get("PYTHONPATH") else "")
        self._proc = subprocess.Popen([sys.executable, "-m", pkg + ".process", json.dumps(self._kwargs)],
                                      stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=None,
                                      text=True, bufsize=1, env=env)
        self._alive = True
        threading.Thread(target=self._reader, name="smartcart-ble-reader", daemon=True).start()
        if not self._ready.wait(timeout):
            self.stop()
            raise SmartCartError("Bluetooth process did not start within %.0f s" % timeout)
        if self._error:
            self.stop()
            raise SmartCartError(self._error)
        return self

    def stop(self, timeout: float = 6.0) -> None:
        proc, self._proc = self._proc, None
        if proc is None:
            return
        try:
            if proc.poll() is None:
                proc.stdin.write('{"op": "stop"}\n')
                proc.stdin.flush()
                proc.wait(timeout)
        except Exception:
            pass
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(2)
            except subprocess.TimeoutExpired:
                proc.kill()
        self._alive = False

    def __enter__(self):
        return self.start()

    def __exit__(self, *exc):
        self.stop()

    # -- API ------------------------------------------------------------------------------------
    @property
    def active(self) -> bool:
        with self._lock:
            return bool(self._alive and self._status and self._status.state == p.ACTIVE)

    @property
    def status(self) -> Optional[p.Status]:
        with self._lock:
            return self._status

    def publish(self, action, label, confidence=None) -> Optional[int]:
        try:
            act, lab, conf = p.normalize_action(action), p.normalize_label(label), p.normalize_confidence(confidence)
        except ValueError as exc:
            log.error("event not sent: %s", exc)
            return None
        with self._cond:
            if not self._alive or self._proc is None:
                log.error("Bluetooth process is not running: %s %s not sent", act, lab)
                return None
            self._next_id += 1
            rid = self._next_id
            try:
                self._proc.stdin.write(json.dumps({"op": "publish", "id": rid, "action": act, "label": lab,
                                                   "confidence": conf}) + "\n")
                self._proc.stdin.flush()
            except (BrokenPipeError, OSError, ValueError):
                self._alive = False
                log.error("Bluetooth process is gone: %s %s not sent", act, lab)
                return None
            end = time.monotonic() + PUBLISH_TIMEOUT_S
            while rid not in self._replies and self._alive:
                left = end - time.monotonic()
                if left <= 0:
                    log.error("Bluetooth process did not answer publish %d in time", rid)
                    return None
                self._cond.wait(left)
            return self._replies.pop(rid, None)

    # -- child -> parent ------------------------------------------------------------------------
    def _reader(self):
        proc = self._proc
        for line in proc.stdout:
            try:
                msg = json.loads(line)
            except ValueError:
                continue
            ev = msg.get("ev")
            st = msg.get("status")
            with self._cond:
                if st is not None:
                    self._status = p.Status(st["sid"], st["state"], st["last_seq"], st["unacked"], st["device_name"])
                if ev == "seq":
                    self._replies[msg["id"]] = msg["seq"]
                self._cond.notify_all()
            if ev == "ready":
                self.name = msg.get("name")
                self._ready.set()
            elif ev == "error":
                self._error = msg.get("message", "Bluetooth failed")
                self._ready.set()
            elif ev in ("start", "stop"):
                fns = self._start_listeners if ev == "start" else self._stop_listeners
                for fn in list(fns):
                    try:
                        if ev == "start":
                            fn(msg["sid"], msg["store_id"], msg["resumed"])
                        else:
                            fn(msg["sid"], msg["reason"])
                    except Exception:
                        log.exception("session %s listener failed", ev)
        with self._cond:
            self._alive = False
            self._cond.notify_all()
        if not self._ready.is_set():
            self._error = self._error or "Bluetooth process exited during start (see log above)"
            self._ready.set()
        elif self._proc is not None:
            log.error("Bluetooth process exited unexpectedly (code %s): events are no longer sent", proc.poll())


# ---- child side ---------------------------------------------------------------------------------
def _child(kwargs_json: str) -> int:
    logging.basicConfig(level=logging.INFO, stream=sys.stderr,
                        format="%(asctime)s smartcart-ble %(levelname)s %(message)s", datefmt="%H:%M:%S")
    from .peripheral import SmartCartBLE, SmartCartError
    out_lock = threading.Lock()
    real_stdout = sys.stdout
    sys.stdout = sys.stderr                      # stray prints must not corrupt the channel

    def send(obj):
        with out_lock:
            real_stdout.write(json.dumps(obj) + "\n")
            real_stdout.flush()

    kwargs = json.loads(kwargs_json)
    holder = {}

    def status():
        cart = holder.get("cart")
        return _status_dict(cart.status) if cart else None

    def on_start(sid, store, resumed):
        send({"ev": "start", "sid": sid, "store_id": store, "resumed": resumed, "status": status()})

    def on_stop(sid, reason):
        send({"ev": "stop", "sid": sid, "reason": reason, "status": status()})

    cart = SmartCartBLE(on_session_start=on_start, on_session_stop=on_stop, **kwargs)
    holder["cart"] = cart
    try:
        cart.start()
    except SmartCartError as exc:
        send({"ev": "error", "message": str(exc)})
        return 2
    send({"ev": "ready", "name": cart.name, "status": status()})

    def heartbeat():                              # keeps the parent's cached STATUS fresh
        while True:
            time.sleep(2)
            try:
                send({"ev": "status", "status": status()})
            except Exception:
                return
    threading.Thread(target=heartbeat, daemon=True).start()

    for line in sys.stdin:
        try:
            msg = json.loads(line)
        except ValueError:
            continue
        if msg.get("op") == "publish":
            seq = cart.publish(msg["action"], msg["label"], msg["confidence"])
            send({"ev": "seq", "id": msg["id"], "seq": seq, "status": status()})
        elif msg.get("op") == "stop":
            break
    cart.stop()                                   # stdin closed (parent died) or stop requested
    return 0


if __name__ == "__main__":
    sys.exit(_child(sys.argv[1] if len(sys.argv) > 1 else "{}"))

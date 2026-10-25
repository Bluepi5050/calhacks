"""The Jetson's session state machine for SmartCart BLE (contract v1.1). Pure: no Bluetooth, no threads.

    IDLE --START|sid--> ACTIVE(sid)            seq starts at 1, buffer empty
    ACTIVE(sid) --START|sid--> ACTIVE(sid)     resume: OK first, then every unacked event again, in order
    ACTIVE(a) --START|b--> ACTIVE(b)           a and its buffer are dropped, seq restarts at 1
                                               (or continues after b's last seq if this cart used b
                                               before and lost it: restart, stale stop)
    ACTIVE(sid) --STOP|sid--> IDLE             buffer cleared
    any --STOP|other--> unchanged              still answers OK|STOP|other (idempotent)
    ACTIVE --ACK|sid|seq--> ACTIVE             seq leaves the resend buffer
    ACTIVE --30 min without a phone--> IDLE    stale session, stopped by the Jetson itself

Every method returns an `Effects`: the notifications to send (in order) and notices for the host
application (session started/stopped). The caller serialises access (SmartCartBLE holds a lock).
"""
from __future__ import annotations

import json
import logging
import os
import tempfile
import time
from collections import OrderedDict
from dataclasses import dataclass, field
from typing import Callable, List, Optional, Tuple

from . import protocol as p

log = logging.getLogger("smartcart_ble")

EVENTS = "events"     # outbound channel names
STATUS = "status"

BUFFER_SIZE = 64
STALE_AFTER_S = 30 * 60.0


@dataclass(frozen=True)
class Outbound:
    channel: str       # EVENTS or STATUS
    payload: bytes


@dataclass(frozen=True)
class Notice:
    kind: str                      # "start" or "stop"
    sid: str
    store_id: str = ""
    resumed: bool = False          # start: True when the phone resumed the same session
    reason: str = ""               # stop: "phone", "replaced", "stale", "local"


@dataclass
class Effects:
    out: List[Outbound] = field(default_factory=list)
    notices: List[Notice] = field(default_factory=list)

    def extend(self, other: "Effects") -> "Effects":
        self.out.extend(other.out)
        self.notices.extend(other.notices)
        return self


class SeqMemory:
    """Last seq used per session id (most recent 32), so a session that is STARTed again after the cart
    lost it (scanner restart, stale stop, bluetoothd restart) continues numbering instead of reusing
    seqs the backend already processed (it de-duplicates by <sid>:<seq>). Optionally saved to a file."""

    KEEP = 32

    def __init__(self, path: Optional[str] = None):
        self.path = path
        self._data: "OrderedDict[str, int]" = OrderedDict()
        self._warned = False
        if path:
            try:
                with open(path, "r", encoding="utf-8") as fh:
                    raw = json.load(fh)
                for sid, seq in raw.get("sessions", []):
                    if p.valid_sid(sid) and isinstance(seq, int) and 0 <= seq <= p.SEQ_MAX:
                        self._data[sid] = seq
            except FileNotFoundError:
                pass
            except (OSError, ValueError, TypeError, AttributeError) as exc:
                log.warning("ignoring unreadable session file %s (%s)", path, exc)

    def get(self, sid: str) -> int:
        return self._data.get(sid, 0)

    def put(self, sid: str, seq: int) -> None:
        self._data.pop(sid, None)
        self._data[sid] = seq
        while len(self._data) > self.KEEP:
            self._data.popitem(last=False)
        if self.path:
            self._save()

    def _save(self) -> None:
        try:
            folder = os.path.dirname(self.path) or "."
            os.makedirs(folder, exist_ok=True)
            fd, tmp = tempfile.mkstemp(dir=folder, prefix=".sessions-")
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                json.dump({"sessions": list(self._data.items())}, fh)
            os.replace(tmp, self.path)
        except OSError as exc:
            if not self._warned:
                self._warned = True
                log.warning("cannot save %s (%s): seq numbers only survive while the process runs", self.path, exc)


class CartSession:
    def __init__(self, device_name: str, *, clock: Callable[[], float] = time.monotonic,
                 buffer_size: int = BUFFER_SIZE, stale_after: float = STALE_AFTER_S,
                 memory: Optional[SeqMemory] = None):
        if not p.valid_device_name(device_name):
            raise ValueError(f"bad device name {device_name!r}")
        if buffer_size < 1 or buffer_size > 99:
            raise ValueError("buffer_size must be 1..99 (STATUS carries it in 2 digits)")
        self.device_name = device_name
        self._clock = clock
        self._buffer_size = buffer_size
        self._stale_after = stale_after
        self.sid: Optional[str] = None
        self.store_id: str = ""
        self.last_seq = 0
        self._buffer: "OrderedDict[int, p.Event]" = OrderedDict()   # unacked, oldest first
        self._phone_present = False
        self._absent_since: Optional[float] = clock()
        self.dropped_overflow = 0      # events pushed out of a full buffer (diagnostics)
        self.memory = memory if memory is not None else SeqMemory()
        self.stopping = False          # set by the host during shutdown: CONTROL answers ERR|BUSY

    # ---- state ---------------------------------------------------------------------------------
    @property
    def active(self) -> bool:
        return self.sid is not None

    @property
    def state(self) -> str:
        return p.ACTIVE if self.active else p.IDLE

    @property
    def unacked(self) -> List[p.Event]:
        return list(self._buffer.values())

    def status(self) -> p.Status:
        return p.Status(self.sid, self.state, self.last_seq, len(self._buffer), self.device_name)

    def status_bytes(self) -> bytes:
        return p.encode_status(self.status())

    # ---- phone -> Jetson -----------------------------------------------------------------------
    def handle_control(self, data) -> Effects:
        """Handle one CONTROL write. Never raises for bad input: answers ERR on STATUS instead."""
        try:
            cmd = p.parse_control(data)
        except p.ProtocolError as exc:
            log.warning("CONTROL rejected (%s): %r", exc, _preview(data))
            return Effects(out=[Outbound(STATUS, p.encode_err(exc.code, exc.detail))])
        if self.stopping and not isinstance(cmd, p.Ack):
            return Effects(out=[Outbound(STATUS, p.encode_err(p.ERR_BUSY, "cart shutting down"))])
        if isinstance(cmd, p.Start):
            return self._start(cmd.sid, cmd.store_id)
        if isinstance(cmd, p.Stop):
            return self._stop_cmd(cmd.sid)
        if isinstance(cmd, p.Ack):
            self._ack(cmd.sid, cmd.seq)
            return Effects()
        return Effects(out=[Outbound(STATUS, self.status_bytes())])          # SYNC

    def _start(self, sid: str, store_id: str) -> Effects:
        fx = Effects()
        if self.sid == sid:
            # Resume (v1.1): OK first, so the phone has a confirmed session, then resend in order.
            self.store_id = store_id
            fx.out.append(Outbound(STATUS, p.encode_ok("START", sid)))
            fx.out.extend(Outbound(EVENTS, p.encode_event(ev)) for ev in self._buffer.values())
            fx.notices.append(Notice("start", sid, store_id, resumed=True))
            log.info("session %s resumed (store %s), resent %d unacked event(s)", sid, store_id, len(self._buffer))
        else:
            if self.sid is not None:
                log.info("session %s replaced by %s (%d unacked dropped)", self.sid, sid, len(self._buffer))
                fx.notices.append(Notice("stop", self.sid, self.store_id, reason="replaced"))
            # A sid this cart already used (it lost the session, the phone kept it) continues numbering:
            # the backend has already processed <sid>:1..n, reusing them would be dropped as duplicates.
            self.sid, self.store_id, self.last_seq = sid, store_id, self.memory.get(sid)
            self._buffer.clear()
            fx.out.append(Outbound(STATUS, p.encode_ok("START", sid)))
            fx.notices.append(Notice("start", sid, store_id, resumed=False))
            if self.last_seq:
                log.info("session %s started again (store %s), continuing after seq %d", sid, store_id, self.last_seq)
            else:
                log.info("session %s started (store %s)", sid, store_id)
        if not self._phone_present:
            # A START always arrives over a live connection; if the host has not reported the phone
            # yet, the stale timer counts from now rather than from an older disconnect.
            self._absent_since = self._clock()
        return fx

    def _stop_cmd(self, sid: str) -> Effects:
        fx = Effects(out=[])
        if self.sid == sid:
            fx.notices.append(Notice("stop", sid, self.store_id, reason="phone"))
            self._go_idle()
            log.info("session %s stopped by the phone", sid)
        else:
            log.info("STOP %s: not the active session (%s), answering OK", sid, self.sid or "-")
        fx.out.append(Outbound(STATUS, p.encode_ok("STOP", sid)))
        return fx

    def _ack(self, sid: str, seq: int) -> None:
        if self.sid != sid:
            log.debug("ACK %s|%d ignored: active session is %s", sid, seq, self.sid or "-")
            return
        if self._buffer.pop(seq, None) is None:
            log.debug("ACK %s|%d: not in the resend buffer (already acked or dropped)", sid, seq)

    def _go_idle(self) -> None:
        self.sid, self.store_id, self.last_seq = None, "", 0
        self._buffer.clear()

    # ---- scanner -> phone -----------------------------------------------------------------------
    def publish(self, action, label, confidence=None) -> Tuple[Optional[int], Effects]:
        """One confirmed crossing. Returns (seq, effects); seq is None when the event is dropped
        (no active session, or the 999999 sequence limit). Raises ValueError for bad input."""
        act = p.normalize_action(action)
        lab = p.normalize_label(label)
        conf = p.normalize_confidence(confidence)
        if self.sid is None:
            log.info("no active session: dropped %s %s", act, lab)
            return None, Effects()
        if self.last_seq >= p.SEQ_MAX:
            log.error("session %s reached seq %d: event dropped (ask the phone to start a new session)",
                      self.sid, p.SEQ_MAX)
            return None, Effects()
        self.last_seq += 1
        self.memory.put(self.sid, self.last_seq)
        ev = p.Event(self.last_seq, act, lab, conf)
        payload = p.encode_event(ev)
        if len(self._buffer) >= self._buffer_size:
            old_seq, _ = self._buffer.popitem(last=False)
            self.dropped_overflow += 1
            log.warning("resend buffer full: event %d of %s dropped from it (never acked)", old_seq, self.sid)
        self._buffer[ev.seq] = ev
        return ev.seq, Effects(out=[Outbound(EVENTS, payload)])

    # ---- connection tracking / timers ------------------------------------------------------------
    def set_phone_present(self, present: bool) -> None:
        present = bool(present)
        if present == self._phone_present:
            return
        self._phone_present = present
        self._absent_since = None if present else self._clock()

    @property
    def phone_present(self) -> bool:
        return self._phone_present

    def tick(self) -> Effects:
        """Call periodically (SmartCartBLE does every 5 s). Stops a stale session."""
        if (self.sid is not None and not self._phone_present and self._absent_since is not None
                and self._clock() - self._absent_since >= self._stale_after):
            sid, store = self.sid, self.store_id
            log.warning("session %s stale: no phone for %.0f min, stopping it", sid, self._stale_after / 60)
            self._go_idle()
            return Effects(out=[Outbound(STATUS, self.status_bytes())],
                           notices=[Notice("stop", sid, store, reason="stale")])
        return Effects()

    def stop_local(self, reason: str = "local") -> Effects:
        """End the session from the Jetson side (e.g. shutdown). Notifies the new STATUS."""
        if self.sid is None:
            return Effects()
        sid, store = self.sid, self.store_id
        self._go_idle()
        return Effects(out=[Outbound(STATUS, self.status_bytes())],
                       notices=[Notice("stop", sid, store, reason=reason)])


def _preview(data) -> str:
    try:
        raw = bytes(int(b) for b in data) if isinstance(data, list) else (
            data.encode() if isinstance(data, str) else bytes(data))
    except Exception:
        return "?"
    return raw[:80].decode("utf-8", "replace")

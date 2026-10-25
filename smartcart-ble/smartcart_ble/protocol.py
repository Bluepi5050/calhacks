"""SmartCart <-> Bag It Bluetooth protocol, v1 (docs/SMARTCART-BLE.md). Pure: no I/O, no Bluetooth.

Every message is UTF-8 text, fields separated by "|", one message per GATT write or notification,
no newline, at most 60 bytes.

    phone -> Jetson  (CONTROL, write with response)
        START|<sid>|<store_id>      start or resume a session
        STOP|<sid>                  end a session (idempotent)
        ACK|<sid>|<seq>             backend processed event <seq>
        SYNC                        notify STATUS now

    Jetson -> phone  (EVENTS, notify)
        <seq>|<A|R>|<label>|<confidence>          e.g. 17|A|apple|93

    Jetson -> phone  (STATUS, read + notify)
        S|<sid or ->|<IDLE|ACTIVE>|<last_seq>|<unacked>|<device_name>
        OK|START|<sid>   OK|STOP|<sid>
        ERR|<BAD_CMD|BAD_SID|BUSY>|<detail, max 30 chars>
"""
from __future__ import annotations

import math
import re
from dataclasses import dataclass
from typing import Optional, Union

# ---- GATT layout (spec section 1) -------------------------------------------------------------
SERVICE_UUID = "7ccd0001-d416-4afc-a97d-f7c98e1afca9"
CONTROL_UUID = "7ccd0002-d416-4afc-a97d-f7c98e1afca9"
EVENTS_UUID = "7ccd0003-d416-4afc-a97d-f7c98e1afca9"
STATUS_UUID = "7ccd0004-d416-4afc-a97d-f7c98e1afca9"

MAX_MESSAGE_BYTES = 60      # every message fits in one notification once ATT MTU >= 64
MIN_MTU = 64                # the phone must negotiate at least this before subscribing
PREFERRED_MTU = 247         # what the phone requests
SEQ_MIN, SEQ_MAX = 1, 999999
MAX_LABEL_CHARS = 20
MAX_ERR_DETAIL = 30
MAX_DEVICE_NAME = 20
DEVICE_NAME_PREFIX = "SmartCart-"

IDLE, ACTIVE = "IDLE", "ACTIVE"
ADD, REMOVE = "A", "R"
ERR_BAD_CMD, ERR_BAD_SID, ERR_BUSY = "BAD_CMD", "BAD_SID", "BUSY"

_SID_RE = re.compile(r"[a-z0-9]{1,12}")
_STORE_RE = re.compile(r"[a-z0-9-]{1,32}")
_SEQ_RE = re.compile(r"[0-9]{1,6}")
_CONF_RE = re.compile(r"[0-9]{1,3}")
_DEVNAME_RE = re.compile(r"[\x21-\x7e](?:[\x20-\x7e]*[\x21-\x7e])?")   # printable ASCII, no edge spaces


class ProtocolError(ValueError):
    """A message that cannot be accepted. `code` is the ERR code the Jetson answers with."""

    def __init__(self, code: str, detail: str):
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail


# ---- message types -----------------------------------------------------------------------------
@dataclass(frozen=True)
class Start:
    sid: str
    store_id: str


@dataclass(frozen=True)
class Stop:
    sid: str


@dataclass(frozen=True)
class Ack:
    sid: str
    seq: int


@dataclass(frozen=True)
class Sync:
    pass


Control = Union[Start, Stop, Ack, Sync]


@dataclass(frozen=True)
class Event:
    seq: int
    action: str        # "A" or "R"
    label: str
    confidence: int    # 0..100


@dataclass(frozen=True)
class Status:
    sid: Optional[str]          # None while IDLE
    state: str                  # IDLE or ACTIVE
    last_seq: int
    unacked: int
    device_name: str


@dataclass(frozen=True)
class Ok:
    command: str       # START or STOP
    sid: str


@dataclass(frozen=True)
class Err:
    code: str
    detail: str


StatusMessage = Union[Status, Ok, Err]


# ---- field validation / normalisation ----------------------------------------------------------
def valid_sid(sid: object) -> bool:
    return isinstance(sid, str) and _SID_RE.fullmatch(sid) is not None


def valid_store_id(store_id: object) -> bool:
    return isinstance(store_id, str) and _STORE_RE.fullmatch(store_id) is not None


def valid_seq(seq: object) -> bool:
    return isinstance(seq, int) and not isinstance(seq, bool) and SEQ_MIN <= seq <= SEQ_MAX


def valid_label(label: object) -> bool:
    """The COCO class exactly as configured: lowercase, may contain spaces, 1..20 chars, no '|'."""
    if not isinstance(label, str) or not (1 <= len(label) <= MAX_LABEL_CHARS):
        return False
    if label != label.strip() or label != label.lower() or "|" in label:
        return False
    return all(ch.isprintable() for ch in label)


def normalize_label(label: object) -> str:
    """Trim + lowercase a scanner label and validate it. Raises ValueError if it can't be sent."""
    if not isinstance(label, str):
        raise ValueError(f"label must be a string, got {type(label).__name__}")
    out = label.strip().lower()
    if not valid_label(out):
        raise ValueError(f"label {label!r} is not sendable (1-{MAX_LABEL_CHARS} printable chars, no '|')")
    return out


def normalize_action(action: object) -> str:
    """ADD / A / add / + -> 'A'; REMOVE / R / remove / - -> 'R'. Enums are read by name, then value."""
    for cand in _action_candidates(action):
        key = cand.strip().upper()
        if key in ("A", "ADD", "ADDED", "IN", "+"):
            return ADD
        if key in ("R", "REMOVE", "REMOVED", "OUT", "-", "DEL", "DELETE"):
            return REMOVE
    raise ValueError(f"unknown action {action!r} (use ADD or REMOVE)")


def _action_candidates(action: object):
    if isinstance(action, str):
        yield action
        return
    name = getattr(action, "name", None)     # enum.Enum
    if isinstance(name, str):
        yield name
    value = getattr(action, "value", None)
    if isinstance(value, str):
        yield value


def normalize_confidence(confidence: object) -> int:
    """Integer percent 0..100. Floats in 0..1 are fractions (0.93 -> 93); ints are percent (93 -> 93);
    floats above 1 are percent (93.4 -> 93). None -> 100. Out-of-range values are clamped."""
    if confidence is None:
        return 100
    if isinstance(confidence, bool):
        raise ValueError("confidence must be a number, not a bool")
    if isinstance(confidence, str):
        confidence = float(confidence.strip().rstrip("%"))
    try:
        if isinstance(confidence, int):
            pct = float(confidence)
        else:
            c = float(confidence)                 # numpy floats, Decimal, ...
            if math.isnan(c):
                raise ValueError("confidence is NaN")
            pct = c * 100.0 if 0.0 <= c <= 1.0 else c
    except TypeError:
        raise ValueError(f"confidence {confidence!r} is not a number") from None
    if math.isinf(pct):
        pct = 100.0 if pct > 0 else 0.0
    return int(min(100, max(0, round(pct))))


def device_name_from_address(address: str) -> str:
    """'AA:BB:CC:DD:3F:2A' -> 'SmartCart-3F2A' (last two bytes of the adapter MAC, uppercase hex)."""
    hexdigits = re.sub(r"[^0-9A-Fa-f]", "", address or "")
    if len(hexdigits) < 4:
        raise ValueError(f"not a Bluetooth address: {address!r}")
    return DEVICE_NAME_PREFIX + hexdigits[-4:].upper()


def valid_device_name(name: object) -> bool:
    return (isinstance(name, str) and 1 <= len(name) <= MAX_DEVICE_NAME and "|" not in name
            and _DEVNAME_RE.fullmatch(name) is not None)


def clean_detail(detail: object) -> str:
    """ERR detail: human text, max 30 chars, no '|', printable ASCII."""
    text = "".join(ch if 32 <= ord(ch) < 127 and ch != "|" else " " for ch in str(detail))
    text = " ".join(text.split())
    return text[:MAX_ERR_DETAIL].rstrip() or "error"


def _to_text(data: Union[bytes, bytearray, memoryview, str, list]) -> str:
    if isinstance(data, str):
        return data
    if isinstance(data, list):                 # dbus.Array of dbus.Byte
        data = bytes(int(b) for b in data)
    raw = bytes(data)
    if len(raw) > MAX_MESSAGE_BYTES:
        raise ProtocolError(ERR_BAD_CMD, "message too long")
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        raise ProtocolError(ERR_BAD_CMD, "not UTF-8") from None


def _check_len(text: str) -> bytes:
    raw = text.encode("utf-8")
    if len(raw) > MAX_MESSAGE_BYTES:
        raise ValueError(f"message longer than {MAX_MESSAGE_BYTES} bytes: {text!r}")
    return raw


def _seq_field(field: str) -> int:
    if _SEQ_RE.fullmatch(field) is None or not (SEQ_MIN <= int(field) <= SEQ_MAX):
        raise ProtocolError(ERR_BAD_CMD, "bad seq")
    return int(field)


# ---- CONTROL (phone -> Jetson) ------------------------------------------------------------------
def parse_control(data) -> Control:
    """Parse one CONTROL write. Raises ProtocolError(code, detail) for anything off-contract.

    Tolerated: trailing CR/LF/NUL/spaces (some BLE stacks add them). Everything else is exact:
    verbs are uppercase, field counts are fixed, sids must match [a-z0-9]{1,12}.
    BAD_SID = verb and shape are right but the session id is malformed; BAD_CMD = everything else.
    """
    text = _to_text(data)
    text = text.rstrip("\r\n\x00 ")
    if len(text.encode("utf-8")) > MAX_MESSAGE_BYTES:
        raise ProtocolError(ERR_BAD_CMD, "message too long")
    if not text:
        raise ProtocolError(ERR_BAD_CMD, "empty message")
    parts = text.split("|")
    verb = parts[0]
    if verb == "START":
        if len(parts) != 3:
            raise ProtocolError(ERR_BAD_CMD, "START needs sid and store")
        if not valid_sid(parts[1]):
            raise ProtocolError(ERR_BAD_SID, "bad session id")
        if not valid_store_id(parts[2]):
            raise ProtocolError(ERR_BAD_CMD, "bad store id")
        return Start(parts[1], parts[2])
    if verb == "STOP":
        if len(parts) != 2:
            raise ProtocolError(ERR_BAD_CMD, "STOP needs sid")
        if not valid_sid(parts[1]):
            raise ProtocolError(ERR_BAD_SID, "bad session id")
        return Stop(parts[1])
    if verb == "ACK":
        if len(parts) != 3:
            raise ProtocolError(ERR_BAD_CMD, "ACK needs sid and seq")
        if not valid_sid(parts[1]):
            raise ProtocolError(ERR_BAD_SID, "bad session id")
        return Ack(parts[1], _seq_field(parts[2]))
    if verb == "SYNC":
        if len(parts) != 1:
            raise ProtocolError(ERR_BAD_CMD, "SYNC takes no fields")
        return Sync()
    raise ProtocolError(ERR_BAD_CMD, "unknown command")


def encode_control(cmd: Control) -> bytes:
    """Used by phone simulators (tools/test_central.py)."""
    if isinstance(cmd, Start):
        if not valid_sid(cmd.sid) or not valid_store_id(cmd.store_id):
            raise ValueError("bad sid or store id")
        return _check_len(f"START|{cmd.sid}|{cmd.store_id}")
    if isinstance(cmd, Stop):
        if not valid_sid(cmd.sid):
            raise ValueError("bad sid")
        return _check_len(f"STOP|{cmd.sid}")
    if isinstance(cmd, Ack):
        if not valid_sid(cmd.sid) or not valid_seq(cmd.seq):
            raise ValueError("bad sid or seq")
        return _check_len(f"ACK|{cmd.sid}|{cmd.seq}")
    if isinstance(cmd, Sync):
        return b"SYNC"
    raise TypeError(f"not a control message: {cmd!r}")


# ---- EVENTS (Jetson -> phone) --------------------------------------------------------------------
def encode_event(ev: Event) -> bytes:
    if not valid_seq(ev.seq):
        raise ValueError(f"seq out of range: {ev.seq}")
    if ev.action not in (ADD, REMOVE):
        raise ValueError(f"action must be A or R: {ev.action!r}")
    if not valid_label(ev.label):
        raise ValueError(f"bad label: {ev.label!r}")
    if not isinstance(ev.confidence, int) or isinstance(ev.confidence, bool) or not 0 <= ev.confidence <= 100:
        raise ValueError(f"confidence must be int 0..100: {ev.confidence!r}")
    return _check_len(f"{ev.seq}|{ev.action}|{ev.label}|{ev.confidence}")


def decode_event(data) -> Event:
    text = _to_text(data)
    parts = text.split("|")
    if len(parts) != 4:
        raise ProtocolError(ERR_BAD_CMD, "event needs 4 fields")
    seq = _seq_field(parts[0])
    if parts[1] not in (ADD, REMOVE):
        raise ProtocolError(ERR_BAD_CMD, "bad action")
    if not valid_label(parts[2]):
        raise ProtocolError(ERR_BAD_CMD, "bad label")
    if _CONF_RE.fullmatch(parts[3]) is None or int(parts[3]) > 100:
        raise ProtocolError(ERR_BAD_CMD, "bad confidence")
    return Event(seq, parts[1], parts[2], int(parts[3]))


# ---- STATUS (Jetson -> phone) --------------------------------------------------------------------
def encode_status(st: Status) -> bytes:
    if st.state == IDLE:
        if st.sid is not None:
            raise ValueError("IDLE status has no sid")
        sid = "-"
    elif st.state == ACTIVE:
        if not valid_sid(st.sid):
            raise ValueError(f"bad sid {st.sid!r}")
        sid = st.sid
    else:
        raise ValueError(f"bad state {st.state!r}")
    if not (0 <= st.last_seq <= SEQ_MAX) or not (0 <= st.unacked <= 99):
        raise ValueError("last_seq/unacked out of range")
    if not valid_device_name(st.device_name):
        raise ValueError(f"bad device name {st.device_name!r}")
    return _check_len(f"S|{sid}|{st.state}|{st.last_seq}|{st.unacked}|{st.device_name}")


def encode_ok(command: str, sid: str) -> bytes:
    if command not in ("START", "STOP") or not valid_sid(sid):
        raise ValueError("bad OK")
    return _check_len(f"OK|{command}|{sid}")


def encode_err(code: str, detail: object) -> bytes:
    if code not in (ERR_BAD_CMD, ERR_BAD_SID, ERR_BUSY):
        raise ValueError(f"bad ERR code {code!r}")
    return _check_len(f"ERR|{code}|{clean_detail(detail)}")


def decode_status_message(data) -> StatusMessage:
    """Decode any STATUS value (read or notification) into Status, Ok or Err."""
    text = _to_text(data)
    parts = text.split("|")
    kind = parts[0]
    if kind == "S" and len(parts) == 6:
        sid_f, state, last_f, unacked_f, name = parts[1:]
        if state == IDLE and sid_f == "-":
            sid = None
        elif state == ACTIVE and valid_sid(sid_f):
            sid = sid_f
        else:
            raise ProtocolError(ERR_BAD_CMD, "bad S sid/state")
        if not re.fullmatch(r"[0-9]{1,6}", last_f) or not re.fullmatch(r"[0-9]{1,2}", unacked_f):
            raise ProtocolError(ERR_BAD_CMD, "bad S counters")
        if not valid_device_name(name):
            raise ProtocolError(ERR_BAD_CMD, "bad S device name")
        return Status(sid, state, int(last_f), int(unacked_f), name)
    if kind == "OK" and len(parts) == 3 and parts[1] in ("START", "STOP") and valid_sid(parts[2]):
        return Ok(parts[1], parts[2])
    if kind == "ERR" and len(parts) == 3 and parts[1] in (ERR_BAD_CMD, ERR_BAD_SID, ERR_BUSY):
        return Err(parts[1], parts[2])
    raise ProtocolError(ERR_BAD_CMD, "unknown status message")

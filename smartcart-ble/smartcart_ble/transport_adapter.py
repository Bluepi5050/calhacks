"""BleTransport: the scanner's `send(event)` transport, backed by SmartCartBLE.

The scanner's EventProcessor calls transport.send(event) with
    {"event_id": "...", "action": "ADD" | "REMOVE", "product_id": "apple", "confidence": 0.93, "timestamp": "..."}
BleTransport maps action -> A/R, product_id -> label, confidence 0..1 -> percent, and publishes it.
Session gating lives in SmartCartBLE: while no shopping session is active nothing is sent.

Hooks the scanner uses when present:
    transport.backend_counts = True             -> EventProcessor offers every confirmed crossing (also a
                                                   REMOVE with a local count of 0: the backend owns the cart and
                                                   answers invalid_remove) and updates its counts only when
                                                   send() returns a seq (i.e. the event really went out)
    transport.add_session_start_listener(fn)    -> fn() runs when the phone starts a NEW session
                                                   (not on a resume); run_scanner passes
                                                   event_processor.reset_counts
    transport.accepts_events()                  -> False while no session is active (informational)

By default the Bluetooth stack runs in a child process (SmartCartProcess), so OpenCV's GTK preview
and GLib never share a main loop. isolate=False runs SmartCartBLE in this process instead.
"""
from __future__ import annotations

import logging
from typing import Any, Callable, List, Optional, Tuple

from . import protocol as p

log = logging.getLogger("smartcart_ble")

_ACTION_KEYS = ("action", "type", "event", "kind", "direction")
_LABEL_KEYS = ("product_id", "label", "product", "class_name", "name", "cls")
_CONF_KEYS = ("confidence", "conf", "score")


def extract_event(event: Any) -> Tuple[Any, Any, Any]:
    """(action, label, confidence) from a dict, an object with attributes, or 'ADD apple 0.93'."""
    if isinstance(event, str):
        parts = event.split()
        if len(parts) < 2:
            raise ValueError(f"cannot read event {event!r}")
        conf = parts[-1] if len(parts) >= 3 and _is_number(parts[-1]) else None
        label = " ".join(parts[1:-1] if conf is not None else parts[1:])
        return parts[0], label, conf

    def pick(keys):
        for k in keys:
            if isinstance(event, dict):
                if k in event and event[k] is not None:
                    return event[k]
            elif getattr(event, k, None) is not None:
                return getattr(event, k)
        return None
    action, label = pick(_ACTION_KEYS), pick(_LABEL_KEYS)
    if action is None or label is None:
        raise ValueError(f"event has no action/product_id: {event!r}")
    return action, label, pick(_CONF_KEYS)


def _is_number(s: str) -> bool:
    try:
        float(s.rstrip("%"))
        return True
    except ValueError:
        return False


class BleTransport:
    """send(event) -> Bluetooth. `also` is another transport (e.g. JsonLineTransport) that receives
    every event first, so the terminal output stays as it is. `label_map` maps product_id -> COCO
    label when they differ (default: product_id is already the COCO class, lowercased)."""

    backend_counts = True

    def __init__(self, cart=None, *, also=None, label_map: Optional[dict] = None, start: bool = True,
                 isolate: bool = True, **cart_kwargs):
        if cart is None:
            if isolate:
                from .process import SmartCartProcess
                cart = SmartCartProcess(**cart_kwargs)
            else:
                from .peripheral import SmartCartBLE
                cart = SmartCartBLE(**cart_kwargs)
        self.cart = cart
        self.also = also
        self.label_map = {str(k).lower(): v for k, v in (label_map or {}).items()}
        self.sent: List[Tuple[Optional[int], str, str, int]] = []      # (seq, A/R, label, pct), last 200
        self._new_session_listeners: List[Callable[[], None]] = []
        cart.add_session_start_listener(self._on_start)
        if start:
            cart.start()

    # -- hooks used by run_scanner / EventProcessor ------------------------------------------
    def accepts_events(self) -> bool:
        return bool(self.cart.active)

    def add_session_start_listener(self, fn: Callable[[], None]) -> None:
        self._new_session_listeners.append(fn)

    def _on_start(self, sid, store_id, resumed):
        if resumed:
            return
        for fn in list(self._new_session_listeners):
            try:
                fn()
            except Exception:
                log.exception("new-session listener failed")

    # -- transport API ---------------------------------------------------------------------
    def send(self, event) -> Optional[int]:
        if self.also is not None:
            self.also.send(event)
        try:
            action, product, conf = extract_event(event)
            label = self.label_map.get(str(product).lower(), product)
            act = p.normalize_action(action)
            lab = p.normalize_label(str(label))
            pct = p.normalize_confidence(conf)
        except ValueError as exc:
            log.error("BLE: event not sent: %s", exc)
            return None
        seq = self.cart.publish(act, lab, pct)
        self.sent.append((seq, act, lab, pct))
        del self.sent[:-200]
        return seq

    publish = emit = send

    def __call__(self, event):
        return self.send(event)

    def close(self) -> None:
        stop = getattr(self.cart, "stop", None)
        if callable(stop):
            stop()

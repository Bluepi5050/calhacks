"""Session-scoped structured events and local bag accounting."""

import json
import uuid
from collections import Counter
from datetime import datetime, timezone


class EventFactory:
    def __init__(self, session_id=None):
        self.session_id = session_id or "session{}".format(uuid.uuid4().hex[:10])
        self._sequence = 0

    def create(self, action, product_id, confidence):
        if action not in ("ADD", "REMOVE"):
            raise ValueError("action must be ADD or REMOVE")
        self._sequence += 1
        return {
            "event_id": "{}-{}".format(self.session_id, self._sequence),
            "action": action,
            "product_id": product_id,
            "confidence": round(float(confidence), 4),
            "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        }


def make_event(product_id: str, confidence: float, action="ADD", event_id=None) -> dict:
    if event_id is None:
        return EventFactory().create(action, product_id, confidence)
    return {
        "event_id": event_id,
        "action": action,
        "product_id": product_id,
        "confidence": round(float(confidence), 4),
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }


def print_event(product_id: str, confidence: float) -> None:
    print(json.dumps(make_event(product_id, confidence), separators=(",", ":")), flush=True)


class EventProcessor:
    """Apply confirmed crossings to the local bag count before transport."""

    def __init__(self, transport, event_factory=None):
        self.transport = transport
        self.event_factory = event_factory or EventFactory()
        self.counts = Counter()
        self.last_status = "No confirmed crossing"

    def process(self, crossing):
        if getattr(self.transport, "backend_counts", False):
            return self._process_backend_counted(crossing)
        if crossing.action == "REMOVE" and self.counts[crossing.product_id] <= 0:
            self.last_status = "REMOVE rejected: no {} recorded in bag".format(crossing.product_id)
            return None
        event = self.event_factory.create(crossing.action, crossing.product_id,
                                          crossing.confidence)
        if crossing.action == "ADD":
            self.counts[crossing.product_id] += 1
        else:
            self.counts[crossing.product_id] -= 1
        self.transport.send(event)
        self.last_status = "{} {} | bag count {}".format(
            event["action"], event["product_id"], self.counts[event["product_id"]]
        )
        return event

    def _process_backend_counted(self, crossing):
        """BLE transport: the backend owns the cart. Every confirmed crossing is offered (a REMOVE the
        backend has no item for is answered invalid_remove there), and the local counts follow only
        events that were actually sent (send() returns their sequence number; None while no shopping
        session is active)."""
        event = self.event_factory.create(crossing.action, crossing.product_id, crossing.confidence)
        seq = self.transport.send(event)
        if seq is None:
            self.last_status = "Not sent (no shopping session): {} {}".format(
                event["action"], event["product_id"])
            return None
        if crossing.action == "ADD":
            self.counts[crossing.product_id] += 1
        else:
            self.counts[crossing.product_id] = max(0, self.counts[crossing.product_id] - 1)
        self.last_status = "{} {} sent (#{}) | bag count {}".format(
            event["action"], event["product_id"], seq, self.counts[event["product_id"]])
        return event

    def reset_counts(self):
        """A new shopping session started (BLE): the bag starts empty, like the backend's new cart."""
        self.counts.clear()
        self.last_status = "New shopping session"

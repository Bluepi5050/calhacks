"""Structured JSON event output."""

import json
import uuid
from datetime import datetime, timezone


def make_event(product_id: str, confidence: float) -> dict:
    return {
        "event_id": str(uuid.uuid4()),
        "action": "ADD",
        "product_id": product_id,
        "confidence": round(float(confidence), 4),
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }


def print_event(product_id: str, confidence: float) -> None:
    print(json.dumps(make_event(product_id, confidence), separators=(",", ":")), flush=True)

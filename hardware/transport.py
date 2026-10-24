"""Pluggable local transports; BLE can implement the same send(event) API."""

import json
import sys


class JsonLineTransport:
    def send(self, event):
        print(json.dumps(event, separators=(",", ":")), flush=True)


class MockTransport:
    """In-process receiver used in demos and tests; stores accepted events."""
    def __init__(self, report=True):
        self.events = []
        self.report = report

    def send(self, event):
        self.events.append(dict(event))
        if self.report:
            print("[mock receiver] accepted {}".format(event["event_id"]),
                  file=sys.stderr, flush=True)


class CompositeTransport:
    def __init__(self, *transports):
        self.transports = transports

    def send(self, event):
        for transport in self.transports:
            transport.send(event)

import unittest

from hardware.crossing import CrossingDetector
from hardware.events import EventFactory, EventProcessor
from hardware.item_tracker import Detection, ItemTracker
from hardware.scan_geometry import ScanGeometry
from hardware.transport import MockTransport


def make_detection(y, name="apple", width=1000, height=600, confidence=0.93):
    cx, cy = 0.5 * width, y * height
    return Detection(name, confidence, (cx - 24, cy - 24, cx + 24, cy + 24))


class CrossingHarness:
    def __init__(self, supported=("apple", "banana", "orange"), width=1000, height=600):
        self.width, self.height = width, height
        self.geometry = ScanGeometry.from_file()
        self.tracker = ItemTracker(
            max_missed_seconds=self.geometry.max_missed_seconds,
            min_iou=self.geometry.minimum_iou,
            max_centroid_distance=self.geometry.maximum_centroid_distance,
            centroid_smoothing=self.geometry.centroid_smoothing,
        )
        self.crossing = CrossingDetector(self.geometry, supported)
        self.transport = MockTransport(report=False)
        self.events = EventProcessor(self.transport, EventFactory("session-test"))
        self.timestamp = 0.0

    def step(self, y=None, name="apple", ambiguous=False, dt=1 / 30):
        self.timestamp += dt
        detections = [] if y is None else [make_detection(
            y, name, self.width, self.height
        )]
        tracks = self.tracker.update(
            detections, self.width, self.height, timestamp=self.timestamp
        )
        crossing = self.crossing.update(
            tracks, expired_track_ids=self.tracker.expired_track_ids,
            ambiguous=ambiguous,
        )
        if crossing:
            return self.events.process(crossing)
        return None

    def move(self, positions, name="apple", ambiguous=False):
        return [self.step(y, name, ambiguous=ambiguous) for y in positions]


class CrossingTests(unittest.TestCase):
    def test_stationary_outside_and_inside_do_not_emit_events(self):
        scanner = CrossingHarness()
        scanner.move([0.2] * 8)
        scanner.move([0.8] * 8)  # A new track appearing inside has no known crossing.
        self.assertEqual(scanner.transport.events, [])

    def test_outside_to_inside_emits_one_add_then_stays_quiet(self):
        scanner = CrossingHarness()
        scanner.move([0.2] * 4)
        scanner.move([0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.8])
        self.assertEqual([event["action"] for event in scanner.transport.events], ["ADD"])
        self.assertEqual(scanner.events.counts["apple"], 1)
        scanner.move([0.8] * 10)
        self.assertEqual(len(scanner.transport.events), 1)

    def test_inside_to_outside_emits_remove_only_with_matching_cart_item(self):
        scanner = CrossingHarness()
        scanner.move([0.8] * 4)
        scanner.move([0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.2])
        self.assertEqual(scanner.transport.events, [])
        self.assertIn("no apple recorded", scanner.events.last_status)

        scanner = CrossingHarness()
        scanner.move([0.2] * 4)
        scanner.move([0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.8])
        scanner.move([0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.2])
        self.assertEqual([event["action"] for event in scanner.transport.events], ["ADD", "REMOVE"])
        self.assertEqual(scanner.events.counts["apple"], 0)

    def test_boundary_jitter_does_not_count_as_crossing(self):
        scanner = CrossingHarness()
        scanner.move([0.2] * 4)
        scanner.move([0.43, 0.48, 0.52, 0.48, 0.43, 0.40, 0.46, 0.53, 0.44])
        self.assertEqual(scanner.transport.events, [])

    def test_short_detection_dropout_does_not_create_exit_and_reacquisition_can_cross(self):
        scanner = CrossingHarness()
        scanner.move([0.2] * 4)
        scanner.step(None)
        scanner.step(None)
        scanner.move([0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.8])
        self.assertEqual([event["action"] for event in scanner.transport.events], ["ADD"])
        for _ in range(10):
            scanner.step(None)
        self.assertEqual([event["action"] for event in scanner.transport.events], ["ADD"])

    def test_stationary_apple_reconnects_after_0_7_second_dropout_without_duplicate_add(self):
        scanner = CrossingHarness()
        scanner.move([0.2] * 4)
        scanner.move([0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.8])
        self.assertEqual([event["action"] for event in scanner.transport.events], ["ADD"])
        original_track_id = next(iter(scanner.tracker.tracks))
        scanner.step(None, dt=0.70)
        recovered = scanner.step(0.8, dt=0.04)
        self.assertIsNone(recovered)
        self.assertIn(original_track_id, scanner.tracker.tracks)
        self.assertEqual(len(scanner.transport.events), 1)

        # After expiry, rediscovery while stationary inside is not an exit or re-entry.
        scanner.step(None, dt=0.80)
        scanner.step(0.8, dt=0.04)
        scanner.move([0.8] * 5)
        self.assertEqual(len(scanner.transport.events), 1)

    def test_same_product_can_cross_repeatedly_and_event_ids_are_unique(self):
        scanner = CrossingHarness()
        scanner.move([0.2] * 4)
        scanner.move([0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.8])
        scanner.move([0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.2])
        scanner.move([0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.8])
        self.assertEqual([event["action"] for event in scanner.transport.events],
                         ["ADD", "REMOVE", "ADD"])
        ids = [event["event_id"] for event in scanner.transport.events]
        self.assertEqual(len(ids), len(set(ids)))

    def test_unsupported_class_and_ambiguous_crossing_are_suppressed(self):
        scanner = CrossingHarness(supported=("apple", "banana"))
        scanner.move([0.2] * 4, name="car")
        scanner.move([0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.8], name="car")
        self.assertEqual(scanner.transport.events, [])

        scanner = CrossingHarness()
        scanner.move([0.2] * 4)
        scanner.move([0.3, 0.4, 0.5, 0.6], ambiguous=True)
        scanner.move([0.7, 0.8, 0.8], ambiguous=True)
        self.assertEqual(scanner.transport.events, [])

    def test_vertical_crossing_is_resolution_independent(self):
        for width, height in ((640, 480), (1280, 720), (1920, 1080)):
            with self.subTest(size=(width, height)):
                scanner = CrossingHarness(width=width, height=height)
                scanner.move([0.2] * 4)
                scanner.move([0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.8])
                self.assertEqual([event["action"] for event in scanner.transport.events], ["ADD"])


if __name__ == "__main__":
    unittest.main()

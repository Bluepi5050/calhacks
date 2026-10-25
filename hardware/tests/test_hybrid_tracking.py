"""Deterministic occlusion and signed-vertical-crossing regression cases."""

import threading
import unittest

from hardware.crossing import CrossingDetector
from hardware.events import EventFactory, EventProcessor
from hardware.item_tracker import Detection, ItemTracker
from hardware.scan_geometry import ScanGeometry
from hardware.scanner import connect_session_hooks
from hardware.smartcart_ble.transport_adapter import BleTransport
from hardware.transport import MockTransport


WIDTH, HEIGHT = 1000, 600


def detection(y, *, x=0.5, name="apple", box_size=0.08):
    half_width = box_size * WIDTH / 2
    half_height = box_size * HEIGHT / 2
    cx, cy = x * WIDTH, y * HEIGHT
    return Detection(name, 0.93, (cx - half_width, cy - half_height,
                                  cx + half_width, cy + half_height))


class HybridHarness:
    def __init__(self):
        self.geometry = ScanGeometry.from_file()
        config = self.geometry
        self.tracker = ItemTracker(
            max_missed_seconds=config.max_missed_seconds,
            min_iou=config.minimum_iou,
            max_centroid_distance=config.maximum_centroid_distance,
            centroid_smoothing=config.centroid_smoothing,
            maximum_reacquisition_distance=config.maximum_reacquisition_distance,
            maximum_reacquisition_horizontal_distance=(
                config.maximum_reacquisition_horizontal_distance),
            maximum_vertical_speed=config.maximum_vertical_speed,
            maximum_box_size_ratio=config.maximum_box_size_ratio,
            reassociation_min_confidence=config.reassociation_min_confidence,
            association_ambiguity_margin=config.association_ambiguity_margin,
        )
        self.crossing = CrossingDetector(config, ("apple", "banana"))
        self.transport = MockTransport(report=False)
        self.processor = EventProcessor(self.transport, EventFactory("hybrid-test"))
        self.time = 0.0

    def step(self, objects=(), *, dt=1 / 30, ambiguous=None):
        self.time += dt
        tracks = self.tracker.update(objects, WIDTH, HEIGHT, timestamp=self.time)
        if ambiguous is None:
            relevant = [track for track in tracks if self.geometry.in_scan_region(
                track.last_normalized_center
            )]
            ambiguous = len(relevant) > 1
        crossing = self.crossing.update(
            tracks, expired_track_ids=self.tracker.expired_track_ids,
            ambiguous=ambiguous,
        )
        return self.processor.process(crossing) if crossing else None

    def at(self, y, *, x=0.5, name="apple", dt=1 / 30, box_size=0.08,
           ambiguous=None):
        return self.step([detection(y, x=x, name=name, box_size=box_size)],
                         dt=dt, ambiguous=ambiguous)

    def move(self, positions, **kwargs):
        return [self.at(y, **kwargs) for y in positions]

    @property
    def actions(self):
        return [event["action"] for event in self.transport.events]

    def add_apple(self):
        self.move([0.2, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.75, 0.75])


class HybridTrackingTests(unittest.TestCase):
    def test_continuous_downward_crossing_emits_one_add(self):
        scanner = HybridHarness()
        scanner.add_apple()
        scanner.move([0.75] * 8)
        self.assertEqual(scanner.actions, ["ADD"])

    def test_continuous_upward_crossing_emits_one_remove(self):
        scanner = HybridHarness()
        scanner.add_apple()
        scanner.move([0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.2])
        self.assertEqual(scanner.actions, ["ADD", "REMOVE"])

    def test_half_second_gap_recovers_add(self):
        scanner = HybridHarness()
        scanner.move([0.25, 0.3, 0.38])
        original_id = next(iter(scanner.tracker.tracks))
        scanner.step(dt=0.5)
        scanner.move([0.75] * 4)
        self.assertEqual(scanner.actions, ["ADD"])
        self.assertIn(original_id, scanner.tracker.tracks)

    def test_one_point_five_second_gap_recovers_add(self):
        scanner = HybridHarness()
        scanner.move([0.25, 0.3, 0.38])
        original_id = next(iter(scanner.tracker.tracks))
        scanner.step(dt=1.5)
        scanner.move([0.75] * 4)
        self.assertEqual(scanner.actions, ["ADD"])
        self.assertIn(original_id, scanner.tracker.tracks)

    def test_slow_inference_interval_without_empty_frame_recovers_add(self):
        scanner = HybridHarness()
        scanner.move([0.25, 0.3, 0.38])
        original_id = next(iter(scanner.tracker.tracks))
        scanner.at(0.75, dt=0.7)
        scanner.move([0.75] * 3)
        self.assertEqual(scanner.actions, ["ADD"])
        self.assertTrue(scanner.tracker.tracks[original_id].observed)

    def test_apple_box_grows_during_occluded_entry(self):
        scanner = HybridHarness()
        scanner.move([0.25, 0.3, 0.38])
        original_id = next(iter(scanner.tracker.tracks))
        scanner.step(dt=0.7)
        for _ in range(3):
            scanner.at(0.75, x=0.55, box_size=0.19)
        self.assertEqual(scanner.actions, ["ADD"])
        self.assertTrue(scanner.tracker.tracks[original_id].observed)

    def test_apple_box_shrinks_during_occluded_exit(self):
        scanner = HybridHarness()
        scanner.add_apple()
        scanner.move([0.75] * 5)
        original_id = next(iter(scanner.tracker.tracks))
        scanner.at(0.22, x=0.54, box_size=0.04, dt=0.7)
        for _ in range(2):
            scanner.at(0.22, x=0.54, box_size=0.04)
        self.assertEqual(scanner.actions, ["ADD", "REMOVE"])
        self.assertTrue(scanner.tracker.tracks[original_id].observed)

    def test_two_clear_inside_observations_after_gap_are_enough(self):
        scanner = HybridHarness()
        scanner.move([0.3, 0.35])
        scanner.step(dt=0.4)
        scanner.move([0.64, 0.64])
        self.assertEqual(scanner.actions, ["ADD"])

    def test_upward_crossing_after_gap_emits_remove(self):
        scanner = HybridHarness()
        scanner.add_apple()
        scanner.step(dt=0.5)
        scanner.move([0.22] * 5)
        self.assertEqual(scanner.actions, ["ADD", "REMOVE"])

    def test_stationary_apple_with_dropouts_is_quiet(self):
        scanner = HybridHarness()
        scanner.move([0.25] * 4)
        for _ in range(3):
            scanner.step(dt=0.5)
            scanner.move([0.25] * 3)
        self.assertEqual(scanner.actions, [])

    def test_moving_down_only_within_outside_is_quiet(self):
        scanner = HybridHarness()
        scanner.move([0.15, 0.2, 0.25, 0.3, 0.35])
        self.assertEqual(scanner.actions, [])

    def test_moving_up_only_within_inside_is_quiet(self):
        scanner = HybridHarness()
        scanner.move([0.85, 0.8, 0.75, 0.7, 0.65])
        self.assertEqual(scanner.actions, [])

    def test_disappearance_without_return_is_quiet(self):
        scanner = HybridHarness()
        scanner.move([0.25] * 3)
        scanner.step(dt=2.0)
        self.assertEqual(scanner.actions, [])
        self.assertFalse(scanner.tracker.tracks)

    def test_two_similar_apples_do_not_receive_speculative_identity(self):
        scanner = HybridHarness()
        for _ in range(3):
            scanner.step([detection(0.3, x=0.45), detection(0.3, x=0.55)],
                         ambiguous=False)
        old_ids = set(scanner.tracker.tracks)
        scanner.step(dt=0.7, ambiguous=False)
        scanner.at(0.75, x=0.5, ambiguous=False)
        self.assertTrue(any(scanner.tracker.tracks[track_id].identity_uncertain
                            for track_id in old_ids))
        scanner.move([0.75] * 4)
        self.assertEqual(scanner.actions, [])

    def test_expired_track_cannot_claim_new_inside_detection(self):
        scanner = HybridHarness()
        scanner.move([0.25] * 3)
        old_id = next(iter(scanner.tracker.tracks))
        scanner.step(dt=scanner.geometry.max_missed_seconds + 0.1)
        scanner.move([0.75] * 4)
        self.assertNotIn(old_id, scanner.tracker.tracks)
        self.assertEqual(scanner.actions, [])

    def test_stationary_destination_after_gap_emits_at_most_once(self):
        scanner = HybridHarness()
        scanner.move([0.25, 0.3, 0.38])
        scanner.step(dt=0.7)
        scanner.move([0.75] * 12)
        self.assertEqual(scanner.actions, ["ADD"])

    def test_boundary_touch_then_return_is_quiet(self):
        scanner = HybridHarness()
        scanner.move([0.25] * 3)
        scanner.at(0.75)
        scanner.move([0.25] * 5)
        self.assertEqual(scanner.actions, [])

    def test_add_followed_by_remove_has_two_ordered_events(self):
        scanner = HybridHarness()
        scanner.add_apple()
        scanner.move([0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.2])
        self.assertEqual(scanner.actions, ["ADD", "REMOVE"])
        self.assertEqual(len({event["event_id"] for event in scanner.transport.events}), 2)

    def test_large_horizontal_or_size_change_rejects_recovery(self):
        for x, box_size in ((0.85, 0.08), (0.5, 0.25)):
            with self.subTest(x=x, box_size=box_size):
                scanner = HybridHarness()
                scanner.move([0.25, 0.3, 0.38])
                old_id = next(iter(scanner.tracker.tracks))
                scanner.step(dt=0.7)
                scanner.at(0.75, x=x, box_size=box_size)
                self.assertFalse(scanner.tracker.tracks[old_id].observed)
                scanner.move([0.75] * 3, x=x, box_size=box_size)
                self.assertEqual(scanner.actions, [])

    def test_different_class_cannot_reclaim_missing_apple(self):
        scanner = HybridHarness()
        scanner.move([0.25, 0.3, 0.38])
        apple_id = next(iter(scanner.tracker.tracks))
        scanner.step(dt=0.7)
        scanner.move([0.75] * 4, name="banana")
        self.assertFalse(scanner.tracker.tracks[apple_id].observed)
        self.assertEqual(scanner.actions, [])

    def test_ble_transport_receives_original_event_fields(self):
        class Cart:
            active = True

            def __init__(self):
                self.published = []

            def add_session_start_listener(self, callback):
                pass

            def publish(self, *event):
                self.published.append(event)
                return len(self.published)

        cart = Cart()
        scanner = HybridHarness()
        scanner.processor = EventProcessor(BleTransport(cart, start=False),
                                           EventFactory("hybrid-ble"))
        scanner.add_apple()
        self.assertEqual(scanner.actions, [])  # The BLE transport has no mock receiver.
        self.assertEqual(cart.published, [("A", "apple", 93)])
        event = scanner.processor.event_factory.create("ADD", "apple", 0.93)
        self.assertEqual(set(event), {"event_id", "action", "product_id",
                                      "confidence", "timestamp"})

    def test_session_change_discards_old_crossing_history(self):
        scanner = HybridHarness()
        scanner.move([0.25] * 3)
        scanner.tracker.reset()
        scanner.crossing.reset()
        scanner.processor.reset_counts()
        scanner.move([0.75] * 5)
        self.assertEqual(scanner.actions, [])

        class SessionCart:
            def __init__(self):
                self.started = None
                self.stopped = None

            def add_session_start_listener(self, callback):
                self.started = callback

            def add_session_stop_listener(self, callback):
                self.stopped = callback

        class SessionTransport:
            def __init__(self):
                self.cart = SessionCart()
                self.started = None

            def add_session_start_listener(self, callback):
                self.started = callback

        transport = SessionTransport()
        pending = threading.Event()
        connect_session_hooks(transport, scanner.processor, pending.set)
        transport.started()
        self.assertTrue(pending.is_set())
        pending.clear()
        transport.cart.stopped("sid", "stop")
        self.assertTrue(pending.is_set())


if __name__ == "__main__":
    unittest.main()

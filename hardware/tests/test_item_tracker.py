import unittest

from hardware.item_tracker import Detection, ItemTracker


def detection(x, name="apple", y=240, width=640, height=480):
    return Detection(name, 0.9, (x - 20, y - 20, x + 20, y + 20))


class ItemTrackerTests(unittest.TestCase):
    def test_centroid_iou_association_preserves_track_id(self):
        tracker = ItemTracker()
        first = tracker.update([detection(100)], 640, 480)[0]
        second = tracker.update([detection(110)], 640, 480)[0]
        self.assertEqual(first.track_id, second.track_id)
        self.assertTrue(second.observed)

    def test_short_dropout_preserves_track_then_reacquires(self):
        tracker = ItemTracker(max_missed_seconds=0.75)
        first = tracker.update([detection(100)], 640, 480, timestamp=1.0)[0]
        missed = tracker.update([], 640, 480, timestamp=1.5)[0]
        self.assertFalse(missed.observed)
        recovered = tracker.update([detection(115)], 640, 480, timestamp=1.7)[0]
        self.assertEqual(first.track_id, recovered.track_id)

    def test_track_expires_after_configured_dropout(self):
        tracker = ItemTracker(max_missed_seconds=0.75)
        first = tracker.update([detection(100)], 640, 480, timestamp=0.0)[0]
        tracker.update([], 640, 480, timestamp=0.5)
        tracker.update([], 640, 480, timestamp=0.76)
        self.assertIn(first.track_id, tracker.expired_track_ids)
        recovered = tracker.update([detection(100)], 640, 480, timestamp=0.8)[0]
        self.assertNotEqual(first.track_id, recovered.track_id)

    def test_product_class_requires_multiple_consistent_observations(self):
        tracker = ItemTracker()
        for name in ("apple", "banana", "apple", "apple"):
            obj = tracker.update([detection(100, name)], 640, 480)[0]
        self.assertEqual(obj.stable_class(4, 0.67), ("apple", 0.9))
        tracker.update([detection(100, "banana")], 640, 480)
        self.assertEqual(tracker.tracks[obj.track_id].stable_class(5, 0.67)[0], None)


if __name__ == "__main__":
    unittest.main()

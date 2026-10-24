import unittest

from hardware.item_tracker import Detection, ItemTracker


def fruit(name="apple", confidence=0.9, center=(320, 240)):
    x, y = center
    return Detection(name, confidence, (x - 30, y - 30, x + 30, y + 30))


class ItemTrackerTests(unittest.TestCase):
    def setUp(self):
        self.tracker = ItemTracker(confirm_frames=3, clear_frames=2)

    def update(self, detections):
        return self.tracker.update(detections, 640, 480)

    def test_confirms_once_and_does_not_duplicate_while_visible(self):
        self.assertIsNone(self.update([fruit()]))
        self.assertIsNone(self.update([fruit()]))
        event = self.update([fruit()])
        self.assertEqual(event.product_id, "apple")
        for _ in range(10):
            self.assertIsNone(self.update([fruit()]))

    def test_rearms_after_clear_and_emits_on_reintroduction(self):
        for _ in range(3):
            event = self.update([fruit("banana")])
        self.assertEqual(event.product_id, "banana")
        self.assertIsNone(self.update([]))
        self.assertIsNone(self.update([]))
        for _ in range(2):
            self.assertIsNone(self.update([fruit("banana")]))
        event = self.update([fruit("banana")])
        self.assertEqual(event.product_id, "banana")

    def test_unsupported_or_uncertain_detections_do_not_generate_events(self):
        for _ in range(5):
            self.assertIsNone(self.update([fruit("carrot")]))
        self.assertIsNone(self.update([fruit("orange", 0.49)]))

    def test_multiple_objects_are_ambiguous_and_do_not_confirm(self):
        for _ in range(5):
            self.assertIsNone(self.update([fruit("apple"), fruit("banana", center=(350, 240))]))
        for _ in range(2):
            self.assertIsNone(self.update([fruit("apple")]))
        self.assertEqual(self.update([fruit("apple")]).product_id, "apple")

    def test_zone_uses_detection_center(self):
        outside = fruit(center=(20, 20))
        for _ in range(3):
            self.assertIsNone(self.update([outside]))


if __name__ == "__main__":
    unittest.main()

"""Conservative one-item-at-a-time scanning state machine."""

from dataclasses import dataclass
from typing import Iterable, Optional


SUPPORTED_PRODUCTS = {"apple", "banana", "orange"}


@dataclass(frozen=True)
class Detection:
    class_name: str
    confidence: float
    bbox: tuple[float, float, float, float]


@dataclass(frozen=True)
class AddEvent:
    product_id: str
    confidence: float


class ItemTracker:
    """Confirms a unique product and rearms only after the zone clears."""

    def __init__(self, *, confidence_threshold: float = 0.5,
                 confirm_frames: int = 4, clear_frames: int = 8,
                 zone_width: float = 0.5, zone_height: float = 0.5,
                 zone_center_x: float = 0.5, zone_center_y: float = 0.5):
        if not 0 <= confidence_threshold <= 1:
            raise ValueError("confidence_threshold must be between 0 and 1")
        if confirm_frames < 1 or clear_frames < 1:
            raise ValueError("confirm_frames and clear_frames must be positive")
        if not (0 < zone_width <= 1 and 0 < zone_height <= 1):
            raise ValueError("zone dimensions must be in (0, 1]")
        if not (0 <= zone_center_x <= 1 and 0 <= zone_center_y <= 1):
            raise ValueError("zone center must be in [0, 1]")
        self.confidence_threshold = confidence_threshold
        self.confirm_frames = confirm_frames
        self.clear_frames = clear_frames
        self.zone_width, self.zone_height = zone_width, zone_height
        self.zone_center_x, self.zone_center_y = zone_center_x, zone_center_y
        self._candidate: Optional[str] = None
        self._candidate_count = 0
        self._candidate_confidence = 0.0
        self._latched = False
        self._empty_count = 0

    def _in_zone(self, detection: Detection, frame_width: int, frame_height: int) -> bool:
        x1, y1, x2, y2 = detection.bbox
        cx, cy = (x1 + x2) / 2 / frame_width, (y1 + y2) / 2 / frame_height
        left = self.zone_center_x - self.zone_width / 2
        right = self.zone_center_x + self.zone_width / 2
        top = self.zone_center_y - self.zone_height / 2
        bottom = self.zone_center_y + self.zone_height / 2
        return left <= cx <= right and top <= cy <= bottom

    def update(self, detections: Iterable[Detection], frame_width: int,
               frame_height: int) -> Optional[AddEvent]:
        """Consume one frame's detections and return at most one ADD event."""
        if frame_width <= 0 or frame_height <= 0:
            raise ValueError("frame dimensions must be positive")
        in_zone = [d for d in detections
                   if d.confidence >= self.confidence_threshold
                   and self._in_zone(d, frame_width, frame_height)]

        # Unsupported detections still occupy the zone. Multiple objects are
        # ambiguous and therefore cannot confirm or rearm the scanner.
        if not in_zone:
            self._candidate = None
            self._candidate_count = 0
            self._candidate_confidence = 0.0
            if self._latched:
                self._empty_count += 1
                if self._empty_count >= self.clear_frames:
                    self._latched = False
                    self._empty_count = 0
            return None

        self._empty_count = 0
        if self._latched or len(in_zone) != 1:
            self._candidate = None
            self._candidate_count = 0
            self._candidate_confidence = 0.0
            return None

        detection = in_zone[0]
        if detection.class_name not in SUPPORTED_PRODUCTS:
            self._candidate = None
            self._candidate_count = 0
            self._candidate_confidence = 0.0
            return None
        if detection.class_name == self._candidate:
            self._candidate_count += 1
            self._candidate_confidence = max(self._candidate_confidence, detection.confidence)
        else:
            self._candidate = detection.class_name
            self._candidate_count = 1
            self._candidate_confidence = detection.confidence
        if self._candidate_count < self.confirm_frames:
            return None

        event = AddEvent(self._candidate, self._candidate_confidence)
        self._latched = True
        self._candidate = None
        self._candidate_count = 0
        return event

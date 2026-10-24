"""Lightweight class-agnostic centroid/IoU tracker; requires only Python."""

from dataclasses import dataclass, field
from math import hypot
from time import monotonic
from typing import Iterable, Optional


@dataclass(frozen=True)
class Detection:
    class_name: str
    confidence: float
    bbox: tuple[float, float, float, float]

    @property
    def centroid(self):
        x1, y1, x2, y2 = self.bbox
        return ((x1 + x2) / 2, (y1 + y2) / 2)


@dataclass
class TrackedObject:
    track_id: int
    bbox: tuple[float, float, float, float]
    centroid: tuple[float, float]
    class_name: str
    confidence: float
    frame_size: tuple[int, int]
    last_seen_at: float
    observed: bool = True
    missed_frames: int = 0
    missed_seconds: float = 0.0
    hits: int = 1
    class_scores: dict[str, float] = field(default_factory=dict)
    class_observations: dict[str, int] = field(default_factory=dict)

    def observe(self, detection: Detection, frame_width: int, frame_height: int,
                timestamp: float, centroid_smoothing: float = 0.5) -> None:
        self.bbox = detection.bbox
        measured_center = detection.centroid
        alpha = centroid_smoothing
        self.centroid = (
            alpha * measured_center[0] + (1.0 - alpha) * self.centroid[0],
            alpha * measured_center[1] + (1.0 - alpha) * self.centroid[1],
        )
        self.class_name = detection.class_name
        self.confidence = detection.confidence
        self.frame_size = (frame_width, frame_height)
        self.last_seen_at = timestamp
        self.observed = True
        self.missed_frames = 0
        self.missed_seconds = 0.0
        self.hits += 1
        self.class_scores[detection.class_name] = (
            self.class_scores.get(detection.class_name, 0.0) + detection.confidence
        )
        self.class_observations[detection.class_name] = (
            self.class_observations.get(detection.class_name, 0) + 1
        )

    def stable_class(self, minimum_observations=3, minimum_ratio=0.67):
        total = sum(self.class_observations.values())
        if total < minimum_observations:
            return None, 0.0
        name = max(self.class_scores, key=self.class_scores.get)
        count = self.class_observations[name]
        if count / total < minimum_ratio:
            return None, 0.0
        return name, self.class_scores[name] / count


def intersection_over_union(box_a, box_b):
    ax1, ay1, ax2, ay2 = box_a
    bx1, by1, bx2, by2 = box_b
    ix1, iy1 = max(ax1, bx1), max(ay1, by1)
    ix2, iy2 = min(ax2, bx2), min(ay2, by2)
    intersection = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
    area_a = max(0.0, ax2 - ax1) * max(0.0, ay2 - ay1)
    area_b = max(0.0, bx2 - bx1) * max(0.0, by2 - by1)
    return intersection / max(area_a + area_b - intersection, 1e-9)


class ItemTracker:
    """Greedy IoU/centroid association with elapsed-time dropout tolerance."""

    def __init__(self, *, max_missed_seconds=0.75, min_iou=0.05,
                 max_centroid_distance=0.14, centroid_smoothing=0.5):
        if max_missed_seconds < 0:
            raise ValueError("max_missed_seconds must be nonnegative")
        if not 0 <= min_iou <= 1:
            raise ValueError("min_iou must be in [0, 1]")
        if max_centroid_distance <= 0:
            raise ValueError("max_centroid_distance must be positive")
        if not 0 < centroid_smoothing <= 1:
            raise ValueError("centroid_smoothing must be in (0, 1]")
        self.max_missed_seconds = max_missed_seconds
        self.min_iou = min_iou
        self.max_centroid_distance = max_centroid_distance
        self.centroid_smoothing = centroid_smoothing
        self.tracks: dict[int, TrackedObject] = {}
        self._next_id = 1
        self.expired_track_ids: set[int] = set()

    @staticmethod
    def _normalized_distance(center_a, center_b, width, height):
        return hypot((center_a[0] - center_b[0]) / width,
                     (center_a[1] - center_b[1]) / height)

    def update(self, detections: Iterable[Detection], frame_width: int,
               frame_height: int, timestamp: Optional[float] = None) -> list[TrackedObject]:
        if frame_width <= 0 or frame_height <= 0:
            raise ValueError("frame dimensions must be positive")
        detections = list(detections)
        now = monotonic() if timestamp is None else float(timestamp)
        self.expired_track_ids = set()
        for track in self.tracks.values():
            track.observed = False
            track.missed_frames += 1
            track.missed_seconds = max(0.0, now - track.last_seen_at)
        for track_id in list(self.tracks):
            if self.tracks[track_id].missed_seconds > self.max_missed_seconds:
                del self.tracks[track_id]
                self.expired_track_ids.add(track_id)

        pairs = []
        for track_id, track in self.tracks.items():
            # Match against the last observed box center. The public track
            # centroid is smoothed for zone decisions and trails moving objects.
            last_box_center = (
                (track.bbox[0] + track.bbox[2]) / 2,
                (track.bbox[1] + track.bbox[3]) / 2,
            )
            for index, detection in enumerate(detections):
                distance = self._normalized_distance(last_box_center, detection.centroid,
                                                     frame_width, frame_height)
                iou = intersection_over_union(track.bbox, detection.bbox)
                if iou < self.min_iou and distance > self.max_centroid_distance:
                    continue
                # Prefer overlap; centroid distance bridges brief box jitter.
                cost = 0.65 * (1.0 - iou) + 0.35 * min(
                    distance / self.max_centroid_distance, 1.0
                )
                pairs.append((cost, track_id, index))

        assigned_tracks, assigned_detections = set(), set()
        for _, track_id, index in sorted(pairs):
            if track_id in assigned_tracks or index in assigned_detections:
                continue
            self.tracks[track_id].observe(
                detections[index], frame_width, frame_height, now,
                centroid_smoothing=self.centroid_smoothing,
            )
            assigned_tracks.add(track_id)
            assigned_detections.add(index)

        for index, detection in enumerate(detections):
            if index in assigned_detections:
                continue
            track = TrackedObject(self._next_id, detection.bbox, detection.centroid,
                                 detection.class_name, detection.confidence,
                                 (frame_width, frame_height), now)
            track.class_scores[detection.class_name] = detection.confidence
            track.class_observations[detection.class_name] = 1
            self.tracks[self._next_id] = track
            self._next_id += 1

        return list(self.tracks.values())

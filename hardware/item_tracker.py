"""Lightweight class-agnostic centroid/IoU tracker; requires only Python."""

from dataclasses import dataclass, field
import logging
from math import hypot, log
from time import monotonic
from typing import Iterable, Optional


logger = logging.getLogger("smartcart.tracking")


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
    position_history: list[tuple[float, float, float]] = field(default_factory=list)
    velocity_y: float = 0.0
    missing_since: Optional[float] = None
    recovered_after_gap: bool = False
    reassociation_confidence: float = 1.0
    association_note: str = "new track"
    identity_uncertain: bool = False

    def __post_init__(self):
        if not self.position_history:
            width, height = self.frame_size
            self.position_history.append((self.last_seen_at,
                                          self.centroid[0] / width,
                                          self.centroid[1] / height))

    @property
    def last_normalized_center(self):
        return self.position_history[-1][1:]

    def mark_missing(self, timestamp):
        self.observed = False
        self.missed_frames += 1
        self.missed_seconds = max(0.0, timestamp - self.last_seen_at)
        if self.missing_since is None:
            self.missing_since = timestamp

    def observe(self, detection: Detection, frame_width: int, frame_height: int,
                timestamp: float, centroid_smoothing: float = 0.5,
                association_confidence: float = 1.0,
                association_note: str = "continued") -> None:
        previous_time, _, previous_y = self.position_history[-1]
        current_x = detection.centroid[0] / frame_width
        current_y = detection.centroid[1] / frame_height
        elapsed = timestamp - previous_time
        if elapsed > 0:
            measured_velocity = (current_y - previous_y) / elapsed
            self.velocity_y = 0.5 * self.velocity_y + 0.5 * measured_velocity
        self.position_history.append((timestamp, current_x, current_y))
        del self.position_history[:-6]
        self.recovered_after_gap = (self.missed_frames > 0
                                    or association_note.startswith("recovered"))
        self.reassociation_confidence = association_confidence
        self.association_note = association_note
        self.identity_uncertain = False
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
        self.missing_since = None
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
    """Spatial matching followed by conservative, time-aware gap recovery."""

    def __init__(self, *, max_missed_seconds=0.75, min_iou=0.05,
                 max_centroid_distance=0.14, centroid_smoothing=0.5,
                 maximum_reacquisition_distance=0.65,
                 maximum_reacquisition_horizontal_distance=0.15,
                 maximum_vertical_speed=1.5, maximum_box_size_ratio=3.0,
                 reassociation_min_confidence=0.55,
                 association_ambiguity_margin=0.12):
        if max_missed_seconds < 0:
            raise ValueError("max_missed_seconds must be nonnegative")
        if not 0 <= min_iou <= 1:
            raise ValueError("min_iou must be in [0, 1]")
        if max_centroid_distance <= 0:
            raise ValueError("max_centroid_distance must be positive")
        if not 0 < centroid_smoothing <= 1:
            raise ValueError("centroid_smoothing must be in (0, 1]")
        if maximum_reacquisition_distance <= 0 or maximum_reacquisition_horizontal_distance <= 0:
            raise ValueError("reacquisition distances must be positive")
        if maximum_vertical_speed <= 0 or maximum_box_size_ratio <= 1:
            raise ValueError("vertical speed must be positive and box size ratio must exceed 1")
        if not 0 <= reassociation_min_confidence <= 1:
            raise ValueError("reassociation_min_confidence must be in [0, 1]")
        if not 0 <= association_ambiguity_margin <= 1:
            raise ValueError("association_ambiguity_margin must be in [0, 1]")
        self.max_missed_seconds = max_missed_seconds
        self.min_iou = min_iou
        self.max_centroid_distance = max_centroid_distance
        self.centroid_smoothing = centroid_smoothing
        self.maximum_reacquisition_distance = maximum_reacquisition_distance
        self.maximum_reacquisition_horizontal_distance = maximum_reacquisition_horizontal_distance
        self.maximum_vertical_speed = maximum_vertical_speed
        self.maximum_box_size_ratio = maximum_box_size_ratio
        self.reassociation_min_confidence = reassociation_min_confidence
        self.association_ambiguity_margin = association_ambiguity_margin
        self.tracks: dict[int, TrackedObject] = {}
        self._next_id = 1
        self.expired_track_ids: set[int] = set()

    def reset(self):
        """Discard identity history at a shopping-session boundary."""
        self.tracks.clear()
        self.expired_track_ids.clear()

    @staticmethod
    def _normalized_box(box, width, height):
        return (box[0] / width, box[1] / height,
                box[2] / width, box[3] / height)

    def _size_cost(self, old_box, new_box):
        old_w, old_h = old_box[2] - old_box[0], old_box[3] - old_box[1]
        new_w, new_h = new_box[2] - new_box[0], new_box[3] - new_box[1]
        if min(old_w, old_h, new_w, new_h) <= 0:
            return None
        ratio = max(old_w / new_w, new_w / old_w,
                    old_h / new_h, new_h / old_h)
        if ratio > self.maximum_box_size_ratio:
            return None
        return log(ratio) / log(self.maximum_box_size_ratio)

    @staticmethod
    def _same_product(track, detection):
        expected = (max(track.class_scores, key=track.class_scores.get)
                    if track.class_scores else track.class_name)
        return detection.class_name == expected

    def _continuous_candidate(self, track, detection, width, height):
        old_width, old_height = track.frame_size
        old_box = self._normalized_box(track.bbox, old_width, old_height)
        new_box = self._normalized_box(detection.bbox, width, height)
        old_x, old_y = track.last_normalized_center
        new_x, new_y = detection.centroid[0] / width, detection.centroid[1] / height
        distance = hypot(new_x - old_x, new_y - old_y)
        iou = intersection_over_union(old_box, new_box)
        if iou < self.min_iou and distance > self.max_centroid_distance:
            return None
        cost = 0.65 * (1.0 - iou) + 0.35 * min(
            distance / self.max_centroid_distance, 1.0
        )
        return cost, max(0.0, 1.0 - cost), "continuous"

    def _recovery_candidate(self, track, detection, width, height, now):
        gap = now - track.last_seen_at
        if not self._same_product(track, detection):
            return None, "class differs from prior observations"
        old_width, old_height = track.frame_size
        old_box = self._normalized_box(track.bbox, old_width, old_height)
        new_box = self._normalized_box(detection.bbox, width, height)
        size_cost = self._size_cost(old_box, new_box)
        if size_cost is None:
            return None, "box size changed too much"
        old_x, old_y = track.last_normalized_center
        new_x, new_y = detection.centroid[0] / width, detection.centroid[1] / height
        dx, dy = abs(new_x - old_x), new_y - old_y
        x_limit = self.maximum_reacquisition_horizontal_distance
        y_limit = min(self.maximum_reacquisition_distance,
                      self.max_centroid_distance + self.maximum_vertical_speed * gap)
        if dx > x_limit:
            return None, "horizontal position too far"
        if abs(dy) > y_limit:
            return None, "vertical motion exceeds time-aware limit"
        predicted_y = max(0.0, min(1.0, old_y + max(
            -self.maximum_vertical_speed, min(self.maximum_vertical_speed,
                                               track.velocity_y)) * gap))
        prediction_cost = min(abs(new_y - predicted_y) / max(y_limit, 1e-9), 1.0)
        age_cost = min(gap / max(self.max_missed_seconds, 1e-9), 1.0)
        # Vertical travel, size change, and even a direction reversal can happen
        # around the opening. Horizontal consistency carries the most weight.
        cost = (0.40 * dx / x_limit + 0.10 * abs(dy) / max(y_limit, 1e-9)
                + 0.12 * size_cost + 0.08 * prediction_cost + 0.10 * age_cost)
        confidence = max(0.0, 1.0 - cost)
        if confidence < self.reassociation_min_confidence:
            return None, "reassociation confidence {:.2f} below {:.2f}".format(
                confidence, self.reassociation_min_confidence)
        return (cost, confidence, "recovered after {:.2f}s".format(gap)), None

    def update(self, detections: Iterable[Detection], frame_width: int,
               frame_height: int, timestamp: Optional[float] = None) -> list[TrackedObject]:
        if frame_width <= 0 or frame_height <= 0:
            raise ValueError("frame dimensions must be positive")
        detections = list(detections)
        now = monotonic() if timestamp is None else float(timestamp)
        self.expired_track_ids = set()
        for track in self.tracks.values():
            track.observed = False
            track.missed_seconds = max(0.0, now - track.last_seen_at)
        for track_id in list(self.tracks):
            if self.tracks[track_id].missed_seconds > self.max_missed_seconds:
                expired = self.tracks.pop(track_id)
                self.expired_track_ids.add(track_id)
                logger.debug("track=%s expired after %.2fs", track_id,
                             now - expired.last_seen_at)

        assigned_tracks, assigned_detections, uncertain_detections = set(), set(), set()

        def assign(pairs):
            by_track, by_detection = {}, {}
            for pair in pairs:
                cost, track_id, index, _, _ = pair
                by_track.setdefault(track_id, []).append(pair)
                by_detection.setdefault(index, []).append(pair)
            uncertain_tracks = set()
            for track_id, options in by_track.items():
                ranked = sorted(options)
                if (len(ranked) > 1
                        and ranked[1][0] - ranked[0][0] <= self.association_ambiguity_margin):
                    uncertain_tracks.add(track_id)
                    uncertain_detections.update(pair[2] for pair in ranked[:2])
            for index, options in by_detection.items():
                ranked = sorted(options)
                if (len(ranked) > 1
                        and ranked[1][0] - ranked[0][0] <= self.association_ambiguity_margin):
                    uncertain_detections.add(index)
                    uncertain_tracks.update(pair[1] for pair in ranked[:2])
            for track_id in uncertain_tracks:
                self.tracks[track_id].identity_uncertain = True
                self.tracks[track_id].association_note = "ambiguous identity match"
                logger.debug("track=%s reassociation rejected: ambiguous identity", track_id)
            for cost, track_id, index, confidence, note in sorted(pairs):
                if (track_id in uncertain_tracks or index in uncertain_detections
                        or track_id in assigned_tracks or index in assigned_detections):
                    continue
                track = self.tracks[track_id]
                old_y = track.last_normalized_center[1]
                gap = now - track.last_seen_at
                track.observe(detections[index], frame_width, frame_height, now,
                              centroid_smoothing=self.centroid_smoothing,
                              association_confidence=confidence,
                              association_note=note)
                if track.recovered_after_gap:
                    logger.debug("track=%s class=%s recovered old_y=%.3f new_y=%.3f "
                                 "delta_y=%+.3f dt=%.2fs confidence=%.2f (%s)",
                                 track_id, track.class_name, old_y,
                                 track.last_normalized_center[1],
                                 track.last_normalized_center[1] - old_y,
                                 gap, confidence, note)
                assigned_tracks.add(track_id)
                assigned_detections.add(index)

        continuous_pairs = []
        for track_id, track in self.tracks.items():
            if track.missed_frames:
                continue
            for index, detection in enumerate(detections):
                candidate = self._continuous_candidate(
                    track, detection, frame_width, frame_height)
                if candidate is not None:
                    cost, confidence, note = candidate
                    continuous_pairs.append((cost, track_id, index, confidence, note))
        assign(continuous_pairs)

        recovery_pairs = []
        for track_id, track in self.tracks.items():
            if track_id in assigned_tracks or track.identity_uncertain:
                continue
            for index, detection in enumerate(detections):
                if index in assigned_detections or index in uncertain_detections:
                    continue
                candidate, reason = self._recovery_candidate(
                    track, detection, frame_width, frame_height, now)
                if candidate is None:
                    logger.debug("track=%s class=%s reassociation rejected: %s",
                                 track_id, track.class_name, reason)
                    continue
                cost, confidence, note = candidate
                recovery_pairs.append((cost, track_id, index, confidence, note))
        assign(recovery_pairs)

        for track_id, track in self.tracks.items():
            if track_id not in assigned_tracks:
                track.mark_missing(now)

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

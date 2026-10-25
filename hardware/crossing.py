"""Movement confirmation across normalized outside/inside scan zones."""

from dataclasses import dataclass, field
import logging
from statistics import median
from typing import Optional

from .scan_geometry import ScanGeometry


logger = logging.getLogger("smartcart.tracking")


@dataclass(frozen=True)
class Crossing:
    track_id: int
    action: str
    product_id: str
    confidence: float


@dataclass
class _TrackCrossingState:
    stable_zone: Optional[str] = None
    stable_anchor: Optional[tuple[float, float]] = None
    stable_points: list[tuple[float, float]] = field(default_factory=list)
    candidate_zone: Optional[str] = None
    candidate_count: int = 0
    candidate_points: list[tuple[float, float]] = field(default_factory=list)
    previous_zone: Optional[str] = None
    event_status: str = "Waiting for stable zone observations"

    def invalidate(self, reason):
        self.stable_zone = None
        self.stable_anchor = None
        self.stable_points.clear()
        self.candidate_zone = None
        self.candidate_count = 0
        self.candidate_points.clear()
        self.previous_zone = None
        self.event_status = reason


class CrossingDetector:
    """Emit a crossing only after stable zones on both sides and movement."""

    def __init__(self, geometry: ScanGeometry, supported_products):
        self.geometry = geometry
        self.supported_products = set(supported_products)
        self._states: dict[int, _TrackCrossingState] = {}
        self.last_status = "Waiting for a product to enter a scan zone"

    def reset(self):
        self._states.clear()
        self.last_status = "Waiting for a product to enter a scan zone"

    @staticmethod
    def _median_point(points):
        return median(point[0] for point in points), median(point[1] for point in points)

    def update(self, tracks, *, expired_track_ids=(), ambiguous=False):
        for track_id in expired_track_ids:
            self._states.pop(track_id, None)
        if ambiguous:
            was_armed = any(state.stable_zone is not None or state.candidate_zone is not None
                            for state in self._states.values())
            for state in self._states.values():
                state.invalidate("Ambiguous objects; identity history reset")
            self.last_status = "Ambiguous objects in scan region; crossing confirmation paused"
            if was_armed:
                logger.debug("crossing suppressed: multiple objects in scan region")
            return None
        crossings = []
        progress = []
        ignored_status = None
        for track in tracks:
            if getattr(track, "identity_uncertain", False):
                uncertain_state = self._states.setdefault(track.track_id, _TrackCrossingState())
                if uncertain_state.event_status != "Ambiguous reassociation; identity history reset":
                    uncertain_state.invalidate("Ambiguous reassociation; identity history reset")
                    logger.debug("track=%s class=%s crossing state reset: uncertain identity",
                                 track.track_id, track.class_name)
            if not track.observed:
                continue
            state = self._states.setdefault(track.track_id, _TrackCrossingState())
            stable_class, class_confidence = track.stable_class(
                self.geometry.minimum_class_observations,
                self.geometry.class_stability_ratio,
            )
            # The raw box center records where YOLO saw the product *now*.
            # A smoothed center can trail across the divider after an occlusion.
            normalized = track.last_normalized_center
            zone = self.geometry.zone_at(normalized)
            if zone is None:
                state.candidate_zone = None
                state.candidate_count = 0
                state.candidate_points.clear()
                state.event_status = "Boundary band; waiting for a clear zone"
                continue

            if zone == state.stable_zone:
                state.stable_points.append(normalized)
                del state.stable_points[:-3]
                state.stable_anchor = self._median_point(state.stable_points)
                state.candidate_zone = None
                state.candidate_count = 0
                state.candidate_points.clear()
                if (state.event_status.startswith("Confirming")
                        or state.event_status.startswith("Ambiguous")):
                    state.event_status = "Stable in {} zone".format(zone)
                continue
            if state.candidate_zone == zone:
                state.candidate_count += 1
                state.candidate_points.append(normalized)
            else:
                state.candidate_zone = zone
                state.candidate_count = 1
                state.candidate_points = [normalized]
            progress.append("{} {}/{}".format(
                zone, state.candidate_count, self.geometry.zone_observations
            ))
            if state.candidate_count == 1:
                previous_time, _, previous_y = track.position_history[-2] if len(
                    track.position_history) > 1 else track.position_history[-1]
                current_time, _, current_y = track.position_history[-1]
                logger.debug("track=%s class=%s zone=%s->%s observed_y=%.3f->%.3f "
                             "delta_y=%+.3f dt=%.2fs confirming",
                             track.track_id, track.class_name,
                             state.stable_zone or "unknown", zone,
                             previous_y, current_y, current_y - previous_y,
                             current_time - previous_time)
            state.event_status = "Confirming {} -> {} ({}/{})".format(
                state.stable_zone or "unknown", zone, state.candidate_count,
                self.geometry.zone_observations
            )
            if state.candidate_count < self.geometry.zone_observations:
                continue

            source_zone = state.stable_zone
            source_point = state.stable_anchor
            destination_point = self._median_point(state.candidate_points)
            state.previous_zone = source_zone
            state.stable_zone = zone
            state.stable_points = state.candidate_points[-3:]
            state.stable_anchor = destination_point
            state.candidate_zone = None
            state.candidate_count = 0
            state.candidate_points = []
            if source_zone is None:
                state.event_status = "Stable in {} zone".format(zone)
                continue
            if source_point is None:
                state.event_status = "Crossing suppressed: missing source position"
                continue
            delta_y = destination_point[1] - source_point[1]
            expected_downward = source_zone == "outside" and zone == "inside"
            if (abs(delta_y) < self.geometry.minimum_crossing_distance
                    or (expected_downward and delta_y <= 0)
                    or (not expected_downward and delta_y >= 0)
                    or not self.geometry.crosses_boundary(source_point, destination_point)):
                state.event_status = "Transition suppressed: signed vertical movement insufficient"
                logger.debug("track=%s class=%s %s->%s delta_y=%+.3f suppressed: "
                             "vertical movement or boundary not confirmed",
                             track.track_id, track.class_name, source_zone, zone, delta_y)
                continue
            if stable_class is None:
                ignored_status = "Crossing ignored: product class is not stable yet"
                state.event_status = ignored_status
                logger.debug("track=%s crossing suppressed: class is not stable",
                             track.track_id)
                continue
            if stable_class not in self.supported_products:
                ignored_status = "Crossing ignored: unsupported product class {}".format(stable_class)
                state.event_status = ignored_status
                logger.debug("track=%s class=%s crossing suppressed: unsupported product",
                             track.track_id, stable_class)
                continue
            outside_to_inside = source_zone == "outside" and zone == "inside"
            adds_on = self.geometry.add_direction == "outside_to_inside"
            action = "ADD" if outside_to_inside == adds_on else "REMOVE"
            crossings.append(Crossing(track.track_id, action, stable_class,
                                      min(1.0, max(0.0, class_confidence))))
            state.event_status = "{} {} confirmed".format(action, stable_class)
            logger.debug("track=%s class=%s %s->%s delta_y=%+.3f %s confirmed "
                         "reassociation=%s confidence=%.2f",
                         track.track_id, stable_class, source_zone, zone, delta_y,
                         action, track.association_note,
                         track.reassociation_confidence)

        if len(crossings) > 1:
            self.last_status = "Multiple simultaneous crossings suppressed as ambiguous"
            return None
        if crossings:
            crossing = crossings[0]
            self.last_status = "{} {} confirmed".format(crossing.action, crossing.product_id)
            return crossing
        if ignored_status:
            self.last_status = ignored_status
            return None
        if progress:
            self.last_status = "Confirming crossing: " + ", ".join(progress)
        elif not self._states:
            self.last_status = "Waiting for a product to enter a scan zone"
        else:
            self.last_status = "No confirmed crossing"
        return None

    def track_status(self, track_id, normalized_point):
        state = self._states.get(track_id)
        current_zone = self.geometry.zone_at(normalized_point) or "boundary"
        if state is None:
            return {"current_zone": current_zone, "previous_zone": "unknown",
                    "event_status": "Waiting for stable zone observations"}
        return {
            "current_zone": current_zone,
            "previous_zone": state.previous_zone or state.stable_zone or "unknown",
            "event_status": state.event_status,
        }

    def set_track_event_status(self, track_id, status):
        state = self._states.get(track_id)
        if state is not None:
            state.event_status = status

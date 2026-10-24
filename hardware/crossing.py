"""Movement confirmation across normalized outside/inside scan zones."""

from dataclasses import dataclass
from math import hypot
from typing import Optional

from .scan_geometry import ScanGeometry


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
    candidate_zone: Optional[str] = None
    candidate_count: int = 0
    previous_zone: Optional[str] = None
    event_status: str = "Waiting for stable zone observations"


class CrossingDetector:
    """Emit a crossing only after stable zones on both sides and movement."""

    def __init__(self, geometry: ScanGeometry, supported_products):
        self.geometry = geometry
        self.supported_products = set(supported_products)
        self._states: dict[int, _TrackCrossingState] = {}
        self.last_status = "Waiting for a product to enter a scan zone"

    def update(self, tracks, *, expired_track_ids=(), ambiguous=False):
        for track_id in expired_track_ids:
            self._states.pop(track_id, None)
        crossings = []
        progress = []
        ignored_status = None
        for track in tracks:
            if not track.observed:
                continue
            state = self._states.setdefault(track.track_id, _TrackCrossingState())
            stable_class, class_confidence = track.stable_class(
                self.geometry.minimum_class_observations,
                self.geometry.class_stability_ratio,
            )
            if ambiguous:
                state.candidate_zone = None
                state.candidate_count = 0
                state.event_status = "Ambiguous objects; confirmation paused"
                continue

            point = (track.centroid[0], track.centroid[1])
            width, height = track.frame_size
            normalized = (point[0] / width, point[1] / height)
            zone = self.geometry.zone_at(normalized)
            if zone is None:
                state.candidate_zone = None
                state.candidate_count = 0
                state.event_status = "Boundary band; waiting for a clear zone"
                continue

            if zone == state.stable_zone:
                state.stable_anchor = normalized
                state.candidate_zone = None
                state.candidate_count = 0
                if (state.event_status.startswith("Confirming")
                        or state.event_status.startswith("Ambiguous")):
                    state.event_status = "Stable in {} zone".format(zone)
                continue
            if state.candidate_zone == zone:
                state.candidate_count += 1
            else:
                state.candidate_zone = zone
                state.candidate_count = 1
            progress.append("{} {}/{}".format(
                zone, state.candidate_count, self.geometry.zone_observations
            ))
            state.event_status = "Confirming {} -> {} ({}/{})".format(
                state.stable_zone or "unknown", zone, state.candidate_count,
                self.geometry.zone_observations
            )
            if state.candidate_count < self.geometry.zone_observations:
                continue

            source_zone = state.stable_zone
            source_point = state.stable_anchor
            state.previous_zone = source_zone
            state.stable_zone = zone
            state.stable_anchor = normalized
            state.candidate_zone = None
            state.candidate_count = 0
            if source_zone is None:
                state.event_status = "Stable in {} zone".format(zone)
                continue
            if source_point is None:
                state.event_status = "Crossing suppressed: missing source position"
                continue
            distance = hypot(normalized[0] - source_point[0],
                             normalized[1] - source_point[1])
            if (distance < self.geometry.minimum_crossing_distance
                    or not self.geometry.crosses_boundary(source_point, normalized)):
                state.event_status = "Transition suppressed: movement did not cross boundary"
                continue
            if stable_class is None:
                ignored_status = "Crossing ignored: product class is not stable yet"
                state.event_status = ignored_status
                continue
            if stable_class not in self.supported_products:
                ignored_status = "Crossing ignored: unsupported product class {}".format(stable_class)
                state.event_status = ignored_status
                continue
            outside_to_inside = source_zone == "outside" and zone == "inside"
            adds_on = self.geometry.add_direction == "outside_to_inside"
            action = "ADD" if outside_to_inside == adds_on else "REMOVE"
            crossings.append(Crossing(track.track_id, action, stable_class,
                                      min(1.0, max(0.0, class_confidence))))
            state.event_status = "{} {} confirmed".format(action, stable_class)

        if ambiguous:
            self.last_status = "Ambiguous objects in scan region; crossing confirmation paused"
            return None
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

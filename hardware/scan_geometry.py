"""Normalized outside/inside scan geometry and validation."""

import json
from dataclasses import dataclass
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_GEOMETRY_PATH = REPOSITORY_ROOT / "data" / "scan_geometry.json"


@dataclass(frozen=True)
class Rectangle:
    x1: float
    y1: float
    x2: float
    y2: float

    @classmethod
    def from_mapping(cls, value, name):
        if not isinstance(value, dict):
            raise ValueError("{} must be an object with x1, y1, x2, y2".format(name))
        try:
            rect = cls(*(float(value[key]) for key in ("x1", "y1", "x2", "y2")))
        except (KeyError, TypeError, ValueError) as exc:
            raise ValueError("{} needs numeric x1, y1, x2, y2 coordinates".format(name)) from exc
        if not (0 <= rect.x1 < rect.x2 <= 1 and 0 <= rect.y1 < rect.y2 <= 1):
            raise ValueError("{} coordinates must form an ordered rectangle within [0, 1]".format(name))
        return rect

    def contains(self, point, margin=0.0):
        x, y = point
        return (self.x1 + margin <= x <= self.x2 - margin
                and self.y1 + margin <= y <= self.y2 - margin)


@dataclass(frozen=True)
class ScanGeometry:
    outside_zone: Rectangle
    inside_zone: Rectangle
    boundary: tuple[float, float, float, float]
    add_direction: str = "outside_to_inside"
    confidence_threshold: float = 0.5
    zone_margin: float = 0.015
    centroid_smoothing: float = 0.5
    zone_observations: int = 3
    minimum_crossing_distance: float = 0.12
    minimum_class_observations: int = 3
    class_stability_ratio: float = 0.67
    max_missed_seconds: float = 0.75
    minimum_iou: float = 0.05
    maximum_centroid_distance: float = 0.14
    maximum_reacquisition_distance: float = 0.65
    maximum_reacquisition_horizontal_distance: float = 0.15
    maximum_vertical_speed: float = 1.5
    maximum_box_size_ratio: float = 2.0
    reassociation_min_confidence: float = 0.55
    association_ambiguity_margin: float = 0.12

    @classmethod
    def from_file(cls, path=DEFAULT_GEOMETRY_PATH):
        path = Path(path)
        if not path.is_absolute():
            path = REPOSITORY_ROOT / path
        try:
            with path.open("r", encoding="utf-8") as stream:
                raw = json.load(stream)
        except FileNotFoundError as exc:
            raise ValueError("Scan geometry file not found: {}".format(path)) from exc
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            raise ValueError("Malformed scan geometry JSON {}: {}".format(path, exc)) from exc
        except OSError as exc:
            raise ValueError("Could not read scan geometry {}: {}".format(path, exc)) from exc
        return cls.from_mapping(raw)

    @classmethod
    def from_mapping(cls, raw):
        if not isinstance(raw, dict):
            raise ValueError("Scan geometry must be a JSON object")
        if raw.get("coordinate_system") != "normalized_0_to_1":
            raise ValueError("coordinate_system must be 'normalized_0_to_1'")
        outside = Rectangle.from_mapping(raw.get("outside_zone"), "outside_zone")
        inside = Rectangle.from_mapping(raw.get("inside_zone"), "inside_zone")
        if (max(outside.x1, inside.x1) < min(outside.x2, inside.x2)
                and max(outside.y1, inside.y1) < min(outside.y2, inside.y2)):
            raise ValueError("outside_zone and inside_zone must not overlap")
        line = raw.get("boundary")
        if not isinstance(line, dict):
            raise ValueError("boundary must be an object with x1, y1, x2, y2")
        try:
            boundary = tuple(float(line[key]) for key in ("x1", "y1", "x2", "y2"))
        except (KeyError, TypeError, ValueError) as exc:
            raise ValueError("boundary needs numeric x1, y1, x2, y2 coordinates") from exc
        if any(not 0 <= value <= 1 for value in boundary) or boundary[:2] == boundary[2:]:
            raise ValueError("boundary endpoints must be distinct points within [0, 1]")
        values = {
            "outside_zone": outside,
            "inside_zone": inside,
            "boundary": boundary,
            "add_direction": raw.get("add_direction", "outside_to_inside"),
            "confidence_threshold": float(raw.get("confidence_threshold", 0.5)),
            "zone_margin": float(raw.get("zone_margin", 0.015)),
            "centroid_smoothing": float(raw.get("centroid_smoothing", 0.5)),
            "zone_observations": int(raw.get("zone_observations", 3)),
            "minimum_crossing_distance": float(raw.get("minimum_crossing_distance", 0.12)),
            "minimum_class_observations": int(raw.get("minimum_class_observations", 3)),
            "class_stability_ratio": float(raw.get("class_stability_ratio", 0.67)),
            "max_missed_seconds": float(raw.get("max_missed_seconds", 0.75)),
            "minimum_iou": float(raw.get("minimum_iou", 0.05)),
            "maximum_centroid_distance": float(raw.get("maximum_centroid_distance", 0.14)),
            "maximum_reacquisition_distance": float(raw.get("maximum_reacquisition_distance", 0.65)),
            "maximum_reacquisition_horizontal_distance": float(raw.get(
                "maximum_reacquisition_horizontal_distance", 0.15)),
            "maximum_vertical_speed": float(raw.get("maximum_vertical_speed", 1.5)),
            "maximum_box_size_ratio": float(raw.get("maximum_box_size_ratio", 2.0)),
            "reassociation_min_confidence": float(raw.get("reassociation_min_confidence", 0.55)),
            "association_ambiguity_margin": float(raw.get("association_ambiguity_margin", 0.12)),
        }
        if values["add_direction"] not in ("outside_to_inside", "inside_to_outside"):
            raise ValueError("add_direction must be outside_to_inside or inside_to_outside")
        if not 0 <= values["confidence_threshold"] <= 1:
            raise ValueError("confidence_threshold must be in [0, 1]")
        if not 0 <= values["zone_margin"] < 0.25:
            raise ValueError("zone_margin must be in [0, 0.25)")
        if not 0 < values["centroid_smoothing"] <= 1:
            raise ValueError("centroid_smoothing must be in (0, 1]")
        if values["zone_observations"] < 2 or values["minimum_class_observations"] < 1:
            raise ValueError("zone_observations must be at least 2 and class observations positive")
        if values["minimum_crossing_distance"] <= 0:
            raise ValueError("minimum_crossing_distance must be positive")
        if not 0 < values["class_stability_ratio"] <= 1:
            raise ValueError("class_stability_ratio must be in (0, 1]")
        if values["max_missed_seconds"] < 0 or not 0 <= values["minimum_iou"] <= 1:
            raise ValueError("max_missed_seconds and minimum_iou are out of range")
        if values["maximum_centroid_distance"] <= 0:
            raise ValueError("maximum_centroid_distance must be positive")
        if (values["maximum_reacquisition_distance"] <= 0
                or values["maximum_reacquisition_horizontal_distance"] <= 0
                or values["maximum_vertical_speed"] <= 0):
            raise ValueError("reacquisition distances and vertical speed must be positive")
        if values["maximum_box_size_ratio"] <= 1:
            raise ValueError("maximum_box_size_ratio must exceed 1")
        if (not 0 <= values["reassociation_min_confidence"] <= 1
                or not 0 <= values["association_ambiguity_margin"] <= 1):
            raise ValueError("reassociation confidence and ambiguity margin must be in [0, 1]")
        return cls(**values)

    def zone_at(self, point):
        clear_of_boundary = self._distance_to_boundary(point) > self.zone_margin
        if clear_of_boundary and self.outside_zone.contains(point):
            return "outside"
        if clear_of_boundary and self.inside_zone.contains(point):
            return "inside"
        return None

    def in_scan_region(self, point):
        if self.zone_at(point):
            return True
        return self._distance_to_boundary(point) <= self.maximum_centroid_distance

    def _distance_to_boundary(self, point):
        px, py = point
        x1, y1, x2, y2 = self.boundary
        dx, dy = x2 - x1, y2 - y1
        if dx == 0 and dy == 0:
            return 1.0
        t = max(0.0, min(1.0, ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)))
        return ((px - (x1 + t * dx)) ** 2 + (py - (y1 + t * dy)) ** 2) ** 0.5

    def crosses_boundary(self, start, end):
        """Return true when the center segment intersects the finite boundary."""
        ax, ay = start
        bx, by = end
        cx, cy, dx, dy = self.boundary
        rx, ry = bx - ax, by - ay
        sx, sy = dx - cx, dy - cy
        denom = rx * sy - ry * sx
        if abs(denom) < 1e-9:
            return False
        qx, qy = cx - ax, cy - ay
        t = (qx * sy - qy * sx) / denom
        u = (qx * ry - qy * rx) / denom
        return 0 <= t <= 1 and 0 <= u <= 1

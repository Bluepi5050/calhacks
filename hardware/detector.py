"""Ultralytics YOLOv8 detector adapter for COCO grocery classes."""

from .item_tracker import Detection


class YoloDetector:
    def __init__(self, model_path="yolov8n.pt", confidence=0.5, device=None):
        try:
            from ultralytics import YOLO
        except ImportError as exc:
            raise RuntimeError("Ultralytics is missing. Install hardware/requirements.txt") from exc
        self.model = YOLO(model_path)
        self.confidence = confidence
        self.device = device
        self.class_ids = None

    def set_class_ids(self, class_ids):
        self.class_ids = list(class_ids)

    def detect(self, frame):
        kwargs = {"conf": self.confidence, "verbose": False}
        if self.class_ids is not None:
            kwargs["classes"] = self.class_ids
        if self.device:
            kwargs["device"] = self.device
        result = self.model.predict(frame, **kwargs)[0]
        detections = []
        if result.boxes is None:
            return detections
        for box in result.boxes:
            class_id = int(box.cls.item())
            name = result.names[class_id]
            xyxy = tuple(float(value) for value in box.xyxy[0].tolist())
            detections.append(Detection(name, float(box.conf.item()), xyxy))
        return detections

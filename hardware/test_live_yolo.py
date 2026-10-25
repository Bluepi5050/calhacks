"""Live YOLOv8n webcam test filtered by a repository product configuration."""

import argparse
import sys
import time
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPOSITORY_ROOT))

from hardware.product_config import (  # noqa: E402
    DEFAULT_PRODUCTS_CONFIG,
    load_products,
    resolve_products_config,
)


def predict_products(model, frame, confidence, allowed_class_ids):
    """Call Ultralytics with exactly the configured class IDs."""
    return model.predict(source=frame, conf=confidence,
                         classes=allowed_class_ids, verbose=False)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--camera", type=int, default=0, help="OpenCV camera index (default: 0)")
    parser.add_argument("--conf", type=float, default=0.5,
                        help="minimum detection confidence, from 0 to 1 (default: 0.5)")
    parser.add_argument("--products-config", type=Path, default=DEFAULT_PRODUCTS_CONFIG,
                        help="JSON product list (default: data/detectable_products.json)")
    args = parser.parse_args(argv)
    if not 0 <= args.conf <= 1:
        parser.error("--conf must be between 0 and 1")
    args.products_config = resolve_products_config(args.products_config)
    return args


def open_camera(camera_index):
    from hardware.scanner import open_capture  # imports cv2: keep it out of module import, like run()

    try:
        return open_capture(camera_index)
    except RuntimeError as exc:
        raise RuntimeError("{}. Try --camera 1.".format(exc)) from exc


def run(camera_index=0, confidence=0.5, products_config=None):
    import cv2
    from ultralytics import YOLO

    model = YOLO("yolov8n.pt")
    config_path = resolve_products_config(products_config)
    product_names, allowed_class_ids = load_products(config_path, model.names)
    print("Configured products: {}".format(", ".join(product_names)))
    camera = open_camera(camera_index)
    previously_visible = set()
    smoothed_fps = 0.0
    previous_frame_time = time.perf_counter()
    window_name = "YOLOv8n configured product detection"

    try:
        cv2.namedWindow(window_name, cv2.WINDOW_NORMAL)
        cv2.resizeWindow(window_name, 960, 720)
        try:
            cv2.setWindowProperty(window_name, cv2.WND_PROP_TOPMOST, 1)
        except cv2.error as exc:
            print("Preview opened; could not set it above other windows: {}".format(exc),
                  file=sys.stderr)
        print("Camera preview opened. Press Q or Esc in the preview to quit.", flush=True)
        while True:
            ok, frame = camera.read()
            if not ok or frame is None:
                raise RuntimeError("webcam frame capture failed")

            results = predict_products(model, frame, confidence, allowed_class_ids)
            result = results[0]
            detections = []
            visible_names = set()
            if result.boxes is not None:
                for box in result.boxes:
                    class_id = int(box.cls.item())
                    class_name = str(model.names[class_id])
                    # Keep the JSON config authoritative even if a model adapter
                    # unexpectedly returns a class that wasn't requested.
                    if class_name not in product_names:
                        continue
                    score = float(box.conf.item())
                    coordinates = tuple(int(value) for value in box.xyxy[0].tolist())
                    detections.append((class_id, class_name, score, coordinates))
                    visible_names.add(class_name)

            # Announce the highest-confidence instance once when a product
            # first appears. It can be announced again after disappearing.
            for class_name in sorted(visible_names - previously_visible):
                best = max(d for d in detections if d[1] == class_name)
                print("Detected: {} ({:.2f})".format(best[1], best[2]), flush=True)
            previously_visible = visible_names

            for class_id, class_name, score, (x1, y1, x2, y2) in detections:
                label = "{} ({}) {:.2f}".format(class_name, class_id, score)
                cv2.rectangle(frame, (x1, y1), (x2, y2), (40, 220, 40), 2)
                cv2.putText(frame, label, (x1, max(20, y1 - 8)),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.6, (40, 220, 40), 2,
                            cv2.LINE_AA)

            now = time.perf_counter()
            frame_interval = now - previous_frame_time
            previous_frame_time = now
            if frame_interval > 0:
                instant_fps = 1.0 / frame_interval
                smoothed_fps = instant_fps if smoothed_fps == 0 else (
                    0.8 * smoothed_fps + 0.2 * instant_fps
                )
            cv2.putText(frame, "FPS: {:.1f}".format(smoothed_fps), (12, 28),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 255), 2,
                        cv2.LINE_AA)
            cv2.imshow(window_name, frame)
            if cv2.waitKey(1) & 0xFF in (ord("q"), 27):
                break
    finally:
        camera.release()
        cv2.destroyAllWindows()


def main(argv=None):
    args = parse_args(argv)
    try:
        run(args.camera, args.conf, args.products_config)
        return 0
    except KeyboardInterrupt:
        return 130
    except Exception as exc:
        print("Live YOLO test failed: {}".format(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

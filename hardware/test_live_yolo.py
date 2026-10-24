"""Live YOLOv8n webcam test filtered by a repository product configuration."""

import argparse
import json
import sys
import time
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PRODUCTS_CONFIG = REPOSITORY_ROOT / "data" / "detectable_products.json"


def resolve_products_config(path=None):
    """Resolve relative config paths from the repository root, not the CWD."""
    config_path = Path(path) if path is not None else DEFAULT_PRODUCTS_CONFIG
    if not config_path.is_absolute():
        config_path = REPOSITORY_ROOT / config_path
    return config_path.resolve()


def load_products(config_path, model_names):
    """Load configured names and map them to IDs from the model's class map."""
    path = Path(config_path)
    try:
        with path.open("r", encoding="utf-8") as config_file:
            config = json.load(config_file)
    except FileNotFoundError as exc:
        raise ValueError("Product configuration file not found: {}".format(path)) from exc
    except json.JSONDecodeError as exc:
        raise ValueError("Malformed JSON in product configuration {}: {}".format(path, exc)) from exc
    except UnicodeDecodeError as exc:
        raise ValueError("Product configuration is not valid UTF-8 JSON: {}".format(path)) from exc
    except OSError as exc:
        raise ValueError("Could not read product configuration {}: {}".format(path, exc)) from exc

    if not isinstance(config, dict) or "products" not in config:
        raise ValueError("Product configuration must be a JSON object with a 'products' array.")
    products = config["products"]
    if not isinstance(products, list) or not products:
        raise ValueError("The 'products' value must be a nonempty JSON array.")
    if any(not isinstance(name, str) or not name.strip() for name in products):
        raise ValueError("Every configured product must be a nonempty string.")
    if len(set(products)) != len(products):
        raise ValueError("The 'products' array must not contain duplicate names.")

    if isinstance(model_names, dict):
        name_to_id = {name: int(class_id) for class_id, name in model_names.items()}
    else:
        name_to_id = {name: class_id for class_id, name in enumerate(model_names)}
    invalid = [name for name in products if name not in name_to_id]
    if invalid:
        available = ", ".join(name_to_id)
        raise ValueError(
            "Unsupported product name(s): {}. Available model classes: {}".format(
                ", ".join(repr(name) for name in invalid), available
            )
        )
    return products, [name_to_id[name] for name in products]


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
    import cv2

    # DirectShow is usually the most reliable OpenCV backend for Windows webcams.
    if sys.platform == "win32":
        camera = cv2.VideoCapture(camera_index, cv2.CAP_DSHOW)
        if camera.isOpened():
            return camera
        camera.release()
    camera = cv2.VideoCapture(camera_index)
    if not camera.isOpened():
        camera.release()
        raise RuntimeError(
            "could not open camera index {}. Check that the webcam is connected, "
            "not in use by another app, and try --camera 1.".format(camera_index)
        )
    return camera


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

    try:
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
            cv2.imshow("YOLOv8n configured product detection", frame)
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

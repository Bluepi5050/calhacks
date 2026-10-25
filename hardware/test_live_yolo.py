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


def predict_products(model, frame, confidence, allowed_class_ids, image_size=None):
    """Call Ultralytics with exactly the configured class IDs."""
    options = {"source": frame, "conf": confidence,
               "classes": allowed_class_ids, "verbose": False}
    if image_size is not None:
        options["imgsz"] = image_size
    return model.predict(**options)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--camera", "--source", dest="camera", type=int, default=0,
                        help="OpenCV camera index (default: 0)")
    parser.add_argument("--conf", type=float, default=0.5,
                        help="minimum detection confidence, from 0 to 1 (default: 0.5)")
    parser.add_argument("--width", type=int, default=640,
                        help="requested camera width (default: 640)")
    parser.add_argument("--height", type=int, default=480,
                        help="requested camera height (default: 480)")
    parser.add_argument("--fps", type=float, default=30,
                        help="requested camera capture FPS (default: 30)")
    parser.add_argument("--imgsz", type=int, default=416,
                        help="YOLO input size (default: 416; use 640 for more detail)")
    parser.add_argument("--products-config", type=Path, default=DEFAULT_PRODUCTS_CONFIG,
                        help="JSON product list (default: data/detectable_products.json)")
    parser.add_argument("--http-port", type=int,
                        help="serve the annotated camera in Chrome on this port")
    parser.add_argument("--http-host", default="127.0.0.1",
                        help="HTTP bind address (default: 127.0.0.1)")
    parser.add_argument("--no-window", action="store_true",
                        help="show the camera in Chrome without an OpenCV window")
    args = parser.parse_args(argv)
    if not 0 <= args.conf <= 1:
        parser.error("--conf must be between 0 and 1")
    if args.width <= 0 or args.height <= 0 or args.fps <= 0 or args.imgsz <= 0:
        parser.error("--width, --height, --fps, and --imgsz must be positive")
    if args.http_port is not None and not 1 <= args.http_port <= 65535:
        parser.error("--http-port must be between 1 and 65535")
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


def run(camera_index=0, confidence=0.5, products_config=None, *,
        http_host="127.0.0.1", http_port=None, no_window=False,
        width=640, height=480, fps=30, image_size=416):
    import cv2
    from ultralytics import YOLO

    from hardware.http_preview import HttpPreview
    from hardware.scanner import configure_capture

    model = YOLO("yolov8n.pt")
    config_path = resolve_products_config(products_config)
    product_names, allowed_class_ids = load_products(config_path, model.names)
    print("Configured products: {}".format(", ".join(product_names)))
    camera = open_camera(camera_index)
    previously_visible = set()
    smoothed_fps = 0.0
    previous_frame_time = time.perf_counter()
    window_name = "YOLOv8n configured product detection"
    publisher = None

    try:
        configure_capture(camera, width=width, height=height, fps=fps)
        previous_frame_time = time.perf_counter()
        if http_port is not None:
            publisher = HttpPreview(http_host, http_port).start()
        if not no_window:
            cv2.namedWindow(window_name, cv2.WINDOW_NORMAL | cv2.WINDOW_KEEPRATIO)
            cv2.resizeWindow(window_name, 960, 720)
            try:
                cv2.setWindowProperty(window_name, cv2.WND_PROP_TOPMOST, 1)
            except cv2.error as exc:
                print("Preview opened; could not set it above other windows: {}".format(exc),
                      file=sys.stderr)
            print("Camera preview opened. Press Q or Esc in the preview to quit.", flush=True)
        else:
            print("Press Ctrl+C in the terminal to quit.", flush=True)
        while True:
            ok, frame = camera.read()
            if not ok or frame is None:
                raise RuntimeError("webcam frame capture failed")

            results = predict_products(model, frame, confidence,
                                       allowed_class_ids, image_size)
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
            cv2.putText(frame, "YOLO FPS: {:.1f}".format(smoothed_fps), (12, 28),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 255), 2,
                        cv2.LINE_AA)
            if publisher is not None:
                publisher.publish(frame)
            if not no_window:
                cv2.imshow(window_name, frame)
                if cv2.waitKey(1) & 0xFF in (ord("q"), 27):
                    break
    finally:
        camera.release()
        if not no_window:
            cv2.destroyAllWindows()
        if publisher is not None:
            publisher.close()


def main(argv=None):
    args = parse_args(argv)
    try:
        run(args.camera, args.conf, args.products_config,
            http_host=args.http_host, http_port=args.http_port, no_window=args.no_window,
            width=args.width, height=args.height, fps=args.fps,
            image_size=args.imgsz)
        return 0
    except KeyboardInterrupt:
        return 130
    except Exception as exc:
        print("Live YOLO test failed: {}".format(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

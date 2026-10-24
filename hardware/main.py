"""Run the Windows YOLO camera/video scanner with bag crossing events."""

import argparse
import sys
from pathlib import Path

from .detector import YoloDetector
from .product_config import DEFAULT_PRODUCTS_CONFIG, load_products, resolve_products_config
from .scan_geometry import DEFAULT_GEOMETRY_PATH, ScanGeometry
from .scanner import run_scanner


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default="0", help="camera index or prerecorded video/image path")
    parser.add_argument("--model", default="yolov8n.pt", help="pretrained YOLOv8 weights")
    parser.add_argument("--confidence", type=float, default=None,
                        help="detection confidence (default from scan_geometry.json)")
    parser.add_argument("--width", type=int, default=640,
                        help="requested camera width in pixels (default: 640)")
    parser.add_argument("--height", type=int, default=360,
                        help="requested camera height in pixels (default: 360)")
    parser.add_argument("--fps", type=float, default=30,
                        help="requested camera capture rate (default: 30)")
    parser.add_argument("--products-config", type=Path, default=DEFAULT_PRODUCTS_CONFIG)
    parser.add_argument("--geometry-config", type=Path, default=DEFAULT_GEOMETRY_PATH)
    parser.add_argument("--preview", action="store_true")
    parser.add_argument("--headless", action="store_true")
    parser.add_argument("--device", help="Ultralytics inference device, e.g. cpu or 0")
    parser.add_argument("--transport", choices=("both", "console", "mock"), default="both")
    args = parser.parse_args(argv)
    if args.preview and args.headless:
        parser.error("--preview and --headless cannot be combined")
    if args.confidence is not None and not 0 <= args.confidence <= 1:
        parser.error("--confidence must be between 0 and 1")
    if args.width <= 0 or args.height <= 0 or args.fps <= 0:
        parser.error("--width, --height, and --fps must be positive")
    try:
        args.geometry = ScanGeometry.from_file(args.geometry_config)
    except ValueError as exc:
        parser.error(str(exc))
    args.products_config = resolve_products_config(args.products_config)
    if args.confidence is None:
        args.confidence = args.geometry.confidence_threshold
    return args


def run(args):
    detector = YoloDetector(args.model, args.confidence, args.device)
    products, class_ids = load_products(args.products_config, detector.model.names)
    detector.set_class_ids(class_ids)
    print("Configured products: {}".format(", ".join(products)))
    run_scanner(detector, args.source, products, args.geometry,
                preview=args.preview, headless=args.headless,
                transport_mode=args.transport, width=args.width,
                height=args.height, requested_fps=args.fps)
    return 0


def main(argv=None):
    try:
        return run(parse_args(argv))
    except KeyboardInterrupt:
        return 130
    except Exception as exc:
        print("SmartCart scanner error: {}".format(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

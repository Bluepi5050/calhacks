"""Run the Windows YOLO camera/video scanner with bag crossing events."""

import argparse
import sys

from .cli import add_scanner_arguments, finish_scanner_arguments
from .detector import YoloDetector
from .product_config import load_products
from .scanner import run_scanner


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default="0", help="camera index or prerecorded video/image path")
    parser.add_argument("--model", default="yolov8n.pt", help="pretrained YOLOv8 weights")
    parser.add_argument("--width", type=int, default=640,
                        help="requested camera width in pixels (default: 640)")
    parser.add_argument("--height", type=int, default=360,
                        help="requested camera height in pixels (default: 360)")
    parser.add_argument("--fps", type=float, default=30,
                        help="requested camera capture rate (default: 30)")
    parser.add_argument("--headless", action="store_true")
    parser.add_argument("--device", help="Ultralytics inference device, e.g. cpu or 0")
    add_scanner_arguments(parser)
    args = parser.parse_args(argv)
    if args.preview and args.headless:
        parser.error("--preview and --headless cannot be combined")
    if args.width <= 0 or args.height <= 0 or args.fps <= 0:
        parser.error("--width, --height, and --fps must be positive")
    return finish_scanner_arguments(parser, args)


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

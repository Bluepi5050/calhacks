"""Headless Jetson scanner sharing the Windows track and crossing logic."""

import argparse
import sys
from pathlib import Path

from .product_config import DEFAULT_PRODUCTS_CONFIG, load_products, resolve_products_config
from .scan_geometry import DEFAULT_GEOMETRY_PATH, ScanGeometry
from .scanner import run_scanner
from .tensorrt_detector import TensorRTDetector


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default="/dev/video0", help="camera index or device path")
    parser.add_argument("--engine", default="hardware/models/yolov8n_fp16.engine")
    parser.add_argument("--confidence", type=float, default=None,
                        help="detection confidence (default from scan_geometry.json)")
    parser.add_argument("--products-config", type=Path, default=DEFAULT_PRODUCTS_CONFIG)
    parser.add_argument("--geometry-config", type=Path, default=DEFAULT_GEOMETRY_PATH)
    parser.add_argument("--preview", action="store_true")
    parser.add_argument("--transport", choices=("both", "console", "mock", "ble", "ble+console"),
                        default="both",
                        help="ble / ble+console: send events to the Bag It app over Bluetooth "
                             "(see hardware/smartcart_ble/README.md)")
    args = parser.parse_args(argv)
    if args.confidence is not None and not 0 <= args.confidence <= 1:
        parser.error("--confidence must be between 0 and 1")
    try:
        geometry = ScanGeometry.from_file(args.geometry_config)
    except ValueError as exc:
        parser.error(str(exc))
    if args.confidence is None:
        args.confidence = geometry.confidence_threshold
    products_config = resolve_products_config(args.products_config)
    names = {index: name for index, name in enumerate(TensorRTDetector.COCO_NAMES)}
    try:
        products, _ = load_products(products_config, names)
    except ValueError as exc:
        parser.error(str(exc))

    detector = None
    try:
        detector = TensorRTDetector(args.engine, confidence=args.confidence)
        run_scanner(detector, args.source, products, geometry,
                    preview=args.preview, headless=not args.preview,
                    transport_mode=args.transport)
        return 0
    except KeyboardInterrupt:
        return 130
    except Exception as exc:
        print("SmartCart TensorRT scanner error: {}".format(exc), file=sys.stderr)
        return 1
    finally:
        if detector is not None:
            detector.close()


if __name__ == "__main__":
    sys.exit(main())

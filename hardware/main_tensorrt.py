"""Headless Jetson scanner sharing the Windows track and crossing logic."""

import argparse
import sys

from .cli import add_scanner_arguments, finish_scanner_arguments
from .coco import COCO_NAMES
from .product_config import load_products
from .scanner import run_scanner
from .tensorrt_detector import TensorRTDetector


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default="/dev/video0", help="camera index or device path")
    parser.add_argument("--engine", default="hardware/models/yolov8n_fp16.engine")
    add_scanner_arguments(parser)
    args = finish_scanner_arguments(parser, parser.parse_args(argv))
    try:
        products, _ = load_products(args.products_config, dict(enumerate(COCO_NAMES)))
    except ValueError as exc:
        parser.error(str(exc))

    detector = None
    try:
        detector = TensorRTDetector(args.engine, confidence=args.confidence)
        run_scanner(detector, args.source, products, args.geometry,
                    preview=args.preview, headless=not args.preview,
                    transport_mode=args.transport,
                    tracking_debug=args.tracking_debug)
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

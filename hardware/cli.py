"""Command-line options shared by the Windows (main.py) and Jetson (main_tensorrt.py) scanners."""

from pathlib import Path

from .product_config import DEFAULT_PRODUCTS_CONFIG, resolve_products_config
from .scan_geometry import DEFAULT_GEOMETRY_PATH, ScanGeometry
from .scanner import add_transport_argument


def add_scanner_arguments(parser):
    """--confidence, --products-config, --geometry-config, --preview and --transport."""
    parser.add_argument("--confidence", type=float, default=None,
                        help="detection confidence (default from scan_geometry.json)")
    parser.add_argument("--products-config", type=Path, default=DEFAULT_PRODUCTS_CONFIG)
    parser.add_argument("--geometry-config", type=Path, default=DEFAULT_GEOMETRY_PATH)
    parser.add_argument("--preview", action="store_true")
    add_transport_argument(parser)


def finish_scanner_arguments(parser, args):
    """Validate the shared options; set args.geometry, the absolute args.products_config and the
    confidence default from the geometry file. Errors exit through parser.error."""
    if args.confidence is not None and not 0 <= args.confidence <= 1:
        parser.error("--confidence must be between 0 and 1")
    try:
        args.geometry = ScanGeometry.from_file(args.geometry_config)
    except ValueError as exc:
        parser.error(str(exc))
    args.products_config = resolve_products_config(args.products_config)
    if args.confidence is None:
        args.confidence = args.geometry.confidence_threshold
    return args

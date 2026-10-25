"""Command-line options shared by the Windows (main.py) and Jetson (main_tensorrt.py) scanners."""

from pathlib import Path

from .product_config import DEFAULT_PRODUCTS_CONFIG, resolve_products_config
from .scan_geometry import DEFAULT_GEOMETRY_PATH, ScanGeometry
from .scanner import add_transport_argument


def add_scanner_arguments(parser):
    """--confidence, --products-config, --geometry-config, --preview, --http-port/--http-host,
    --tracking-debug and --transport."""
    parser.add_argument("--confidence", type=float, default=None,
                        help="detection confidence (default from scan_geometry.json)")
    parser.add_argument("--products-config", type=Path, default=DEFAULT_PRODUCTS_CONFIG)
    parser.add_argument("--geometry-config", type=Path, default=DEFAULT_GEOMETRY_PATH)
    parser.add_argument("--preview", action="store_true")
    parser.add_argument("--http-port", type=int,
                        help="serve the annotated camera to a browser on this port")
    parser.add_argument("--http-host", default="127.0.0.1",
                        help="HTTP bind address (default: 127.0.0.1; 0.0.0.0 for other devices)")
    parser.add_argument("--tracking-debug", action="store_true",
                        help="log track recovery and crossing decisions")
    add_transport_argument(parser)


def finish_scanner_arguments(parser, args):
    """Validate the shared options; set args.geometry, the absolute args.products_config and the
    confidence default from the geometry file. Errors exit through parser.error."""
    if args.confidence is not None and not 0 <= args.confidence <= 1:
        parser.error("--confidence must be between 0 and 1")
    if args.http_port is not None and not 1 <= args.http_port <= 65535:
        parser.error("--http-port must be between 1 and 65535")
    try:
        args.geometry = ScanGeometry.from_file(args.geometry_config)
    except ValueError as exc:
        parser.error(str(exc))
    args.products_config = resolve_products_config(args.products_config)
    if args.confidence is None:
        args.confidence = args.geometry.confidence_threshold
    return args

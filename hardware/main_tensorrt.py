"""Headless SmartCart camera scanner using a TensorRT 10 engine."""

import argparse
import sys

import cv2

from .events import print_event
from .item_tracker import ItemTracker
from .tensorrt_detector import TensorRTDetector


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default="/dev/video0", help="camera index or device path")
    parser.add_argument("--engine", default="hardware/models/yolov8n_fp16.engine")
    parser.add_argument("--confidence", type=float, default=0.5)
    parser.add_argument("--confirm-frames", type=int, default=4)
    parser.add_argument("--clear-frames", type=int, default=8)
    parser.add_argument("--zone-width", type=float, default=0.5)
    parser.add_argument("--zone-height", type=float, default=0.5)
    parser.add_argument("--zone-center-x", type=float, default=0.5)
    parser.add_argument("--zone-center-y", type=float, default=0.5)
    args = parser.parse_args(argv)
    source = int(args.source) if str(args.source).isdecimal() else args.source
    tracker = ItemTracker(confidence_threshold=args.confidence, confirm_frames=args.confirm_frames,
                          clear_frames=args.clear_frames, zone_width=args.zone_width,
                          zone_height=args.zone_height, zone_center_x=args.zone_center_x,
                          zone_center_y=args.zone_center_y)
    capture = detector = None
    try:
        detector = TensorRTDetector(args.engine, confidence=args.confidence)
        capture = cv2.VideoCapture(source)
        if not capture.isOpened():
            raise RuntimeError("could not open camera source {!r}".format(source))
        while True:
            ok, frame = capture.read()
            if not ok or frame is None:
                raise RuntimeError("camera read failed")
            h, w = frame.shape[:2]
            event = tracker.update(detector.detect(frame), w, h)
            if event:
                print_event(event.product_id, event.confidence)
    except KeyboardInterrupt:
        return 130
    except Exception as exc:
        print("SmartCart TensorRT scanner error: {}".format(exc), file=sys.stderr)
        return 1
    finally:
        if capture is not None:
            capture.release()
        if detector is not None:
            detector.close()


if __name__ == "__main__":
    sys.exit(main())

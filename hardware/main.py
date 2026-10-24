"""Run the SmartCart grocery scanner on a camera, video, or still image."""

import argparse
import sys
from pathlib import Path

import cv2

from .detector import YoloDetector
from .events import print_event
from .item_tracker import ItemTracker


IMAGE_SUFFIXES = {".bmp", ".jpg", ".jpeg", ".png", ".tif", ".tiff", ".webp"}


def source_value(value):
    return int(value) if value.isdecimal() else value


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default="0", help="camera index or video/image path")
    parser.add_argument("--model", default="yolov8n.pt", help="Ultralytics model weights")
    parser.add_argument("--confidence", type=float, default=0.5)
    parser.add_argument("--confirm-frames", type=int, default=4)
    parser.add_argument("--clear-frames", type=int, default=8)
    parser.add_argument("--zone-width", type=float, default=0.5, help="zone width as frame fraction")
    parser.add_argument("--zone-height", type=float, default=0.5, help="zone height as frame fraction")
    parser.add_argument("--zone-center-x", type=float, default=0.5)
    parser.add_argument("--zone-center-y", type=float, default=0.5)
    parser.add_argument("--preview", action="store_true", help="show annotated OpenCV preview")
    parser.add_argument("--headless", action="store_true", help="disable all GUI windows")
    parser.add_argument("--device", help="Ultralytics inference device, e.g. cpu or 0")
    args = parser.parse_args(argv)
    if args.preview and args.headless:
        parser.error("--preview and --headless cannot be used together")
    try:
        ItemTracker(confidence_threshold=args.confidence, confirm_frames=args.confirm_frames,
                    clear_frames=args.clear_frames, zone_width=args.zone_width,
                    zone_height=args.zone_height, zone_center_x=args.zone_center_x,
                    zone_center_y=args.zone_center_y)
    except ValueError as exc:
        parser.error(str(exc))
    return args


def run(args):
    source = source_value(args.source)
    is_image = isinstance(source, str) and Path(source).suffix.lower() in IMAGE_SUFFIXES
    detector = YoloDetector(args.model, args.confidence, args.device)
    tracker = ItemTracker(confidence_threshold=args.confidence, confirm_frames=args.confirm_frames,
                          clear_frames=args.clear_frames, zone_width=args.zone_width,
                          zone_height=args.zone_height, zone_center_x=args.zone_center_x,
                          zone_center_y=args.zone_center_y)
    capture = None
    try:
        if is_image:
            frame = cv2.imread(source)
            if frame is None:
                raise RuntimeError("could not read image {!r}".format(source))
            frames = (frame.copy() for _ in range(args.confirm_frames))
        else:
            capture = cv2.VideoCapture(source)
            if not capture.isOpened():
                raise RuntimeError("could not open video source {!r}".format(source))
            def video_frames():
                while True:
                    yield capture.read()
            frames = video_frames()

        for item in frames:
            if is_image:
                frame = item
            else:
                ok, frame = item
                if not ok or frame is None:
                    if not isinstance(source, int) and capture.get(cv2.CAP_PROP_FRAME_COUNT) > 0:
                        break
                    raise RuntimeError("camera/video read failed")
            height, width = frame.shape[:2]
            detections = detector.detect(frame)
            event = tracker.update(detections, width, height)
            if event:
                print_event(event.product_id, event.confidence)
            if args.preview and not args.headless:
                x1 = int((args.zone_center_x - args.zone_width / 2) * width)
                y1 = int((args.zone_center_y - args.zone_height / 2) * height)
                x2 = int((args.zone_center_x + args.zone_width / 2) * width)
                y2 = int((args.zone_center_y + args.zone_height / 2) * height)
                cv2.rectangle(frame, (x1, y1), (x2, y2), (255, 200, 0), 2)
                for d in detections:
                    x1, y1, x2, y2 = map(int, d.bbox)
                    cv2.rectangle(frame, (x1, y1), (x2, y2), (0, 220, 0), 2)
                    cv2.putText(frame, "{} {:.2f}".format(d.class_name, d.confidence),
                                (x1, max(20, y1 - 6)), cv2.FONT_HERSHEY_SIMPLEX, .6,
                                (0, 220, 0), 2)
                cv2.imshow("SmartCart scanner", frame)
                if cv2.waitKey(1) & 0xFF in (ord("q"), 27):
                    break
        return 0
    finally:
        if capture is not None:
            capture.release()
        if args.preview and not args.headless:
            cv2.destroyAllWindows()


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

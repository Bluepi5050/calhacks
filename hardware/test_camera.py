#!/usr/bin/env python3
"""Capture frames from a Linux camera and save the last one, without a GUI."""

import argparse
import sys
import time
from pathlib import Path

import cv2


def camera_device(value):
    """Treat an all-digit device argument as an OpenCV camera index."""
    if value.isdecimal():
        return int(value)
    return value


def parse_args():
    parser = argparse.ArgumentParser(
        description="Capture a series of frames from a Linux camera device."
    )
    parser.add_argument(
        "--device",
        type=camera_device,
        default="/dev/video0",
        help="camera index (for example 0) or device path (default: /dev/video0)",
    )
    parser.add_argument(
        "--frames",
        type=int,
        default=100,
        help="number of frames to capture (default: 100)",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("camera_test.jpg"),
        help="path for the last captured frame (default: camera_test.jpg)",
    )
    args = parser.parse_args()
    if args.frames < 1:
        parser.error("--frames must be at least 1")
    return args


def main():
    args = parse_args()
    capture = None
    try:
        capture = cv2.VideoCapture(args.device)
        if not capture.isOpened():
            raise RuntimeError("could not open camera device {!r}".format(args.device))

        last_frame = None
        first_frame_shape = None
        start_time = time.perf_counter()
        for frame_number in range(1, args.frames + 1):
            ok, frame = capture.read()
            if not ok or frame is None:
                raise RuntimeError(
                    "camera read failed on frame {} of {}".format(
                        frame_number, args.frames
                    )
                )
            if first_frame_shape is None:
                first_frame_shape = frame.shape
            last_frame = frame

        elapsed = time.perf_counter() - start_time
        height, width = first_frame_shape[:2]
        average_fps = args.frames / elapsed if elapsed > 0 else float("inf")

        args.output.parent.mkdir(parents=True, exist_ok=True)
        if not cv2.imwrite(str(args.output), last_frame):
            raise RuntimeError("could not write image to {!s}".format(args.output))

        print("Captured {} frames".format(args.frames))
        print("Actual resolution: {}x{} pixels".format(width, height))
        print("Average frame rate: {:.2f} FPS".format(average_fps))
        print("Last frame saved to: {}".format(args.output))
        return 0
    except Exception as exc:
        print("Camera test failed: {}".format(exc), file=sys.stderr)
        return 1
    finally:
        if capture is not None:
            capture.release()


if __name__ == "__main__":
    sys.exit(main())

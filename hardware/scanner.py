"""Inference-independent camera/video tracking and bag crossing pipeline."""

from pathlib import Path

import sys
import threading
import time

import cv2

from .crossing import CrossingDetector
from .events import EventProcessor
from .item_tracker import ItemTracker
from .scan_geometry import ScanGeometry
from .transport import CompositeTransport, JsonLineTransport, MockTransport


IMAGE_SUFFIXES = {".bmp", ".jpg", ".jpeg", ".png", ".tif", ".tiff", ".webp"}
PREVIEW_WINDOW = "SmartCart bag crossing scanner"


def source_value(value):
    return int(value) if str(value).isdecimal() else value


def open_capture(source):
    """Open a camera with the Windows backend used by the recognition test."""
    if sys.platform == "win32" and isinstance(source, int):
        capture = cv2.VideoCapture(source, cv2.CAP_DSHOW)
        if capture.isOpened():
            return capture
        capture.release()

    capture = cv2.VideoCapture(source)
    if not capture.isOpened():
        capture.release()
        raise RuntimeError(
            "could not open camera/video source {!r}; check that it is connected "
            "and not in use by another app".format(source)
        )
    return capture


def configure_capture(capture, *, width=1280, height=720, fps=30, is_camera=True):
    """Request a camera mode, retain the device's working mode if unsupported."""
    if is_camera:
        capture.set(cv2.CAP_PROP_FRAME_WIDTH, width)
        capture.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
        capture.set(cv2.CAP_PROP_FPS, fps)

        if sys.platform == "win32":
            try:
                zoom_ok = capture.set(cv2.CAP_PROP_ZOOM, 0)
                actual_zoom = capture.get(cv2.CAP_PROP_ZOOM)
                if zoom_ok:
                    print("Camera zoom set to minimum (reported {}).".format(actual_zoom),
                          flush=True)
                else:
                    print("Camera zoom control is unavailable; using native view.", flush=True)
            except (cv2.error, TypeError):
                print("Camera zoom control is unavailable; using native view.", flush=True)

    actual_width = int(round(capture.get(cv2.CAP_PROP_FRAME_WIDTH)))
    actual_height = int(round(capture.get(cv2.CAP_PROP_FRAME_HEIGHT)))
    actual_fps = capture.get(cv2.CAP_PROP_FPS)
    fps_text = "unknown" if not actual_fps or actual_fps < 0 else "{:.2f}".format(actual_fps)
    if is_camera:
        print("Camera mode: {}x{} at {} FPS (requested {}x{} at {} FPS).".format(
            actual_width, actual_height, fps_text, width, height, fps), flush=True)
    return actual_width, actual_height


def make_transport(mode="both"):
    if mode in ("ble", "ble+console"):
        # Bluetooth to the Bag It app (hardware/smartcart_ble, contract SMARTCART-BLE.md).
        from .smartcart_ble.transport_adapter import BleTransport
        return BleTransport(also=JsonLineTransport() if mode == "ble+console" else None), None
    if mode == "console":
        return CompositeTransport(JsonLineTransport()), None
    if mode == "mock":
        receiver = MockTransport()
        return CompositeTransport(receiver), receiver
    receiver = MockTransport()
    return CompositeTransport(JsonLineTransport(), receiver), receiver


def connect_session_hooks(transport, event_processor):
    """BLE: a new shopping session resets the local bag counts. No-op for other transports."""
    register = getattr(transport, "add_session_start_listener", None)
    if callable(register):
        register(event_processor.reset_counts)


def close_transport(transport):
    close = getattr(transport, "close", None)
    if callable(close):
        close()


def _draw(frame, tracks, geometry, crossing_detector, status, counts, fps,
          camera_fps=None):
    height, width = frame.shape[:2]

    def pixel_box(rect):
        return (int(rect.x1 * width), int(rect.y1 * height)), \
               (int(rect.x2 * width), int(rect.y2 * height))

    for rect, color, label in (
        (geometry.outside_zone, (255, 150, 30), "OUTSIDE BAG"),
        (geometry.inside_zone, (40, 220, 40), "INSIDE BAG"),
    ):
        top_left, bottom_right = pixel_box(rect)
        cv2.rectangle(frame, top_left, bottom_right, color, 2)
        cv2.putText(frame, label, (top_left[0] + 4, max(18, top_left[1] + 20)),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.55, color, 2, cv2.LINE_AA)

    x1, y1, x2, y2 = geometry.boundary
    cv2.line(frame, (int(x1 * width), int(y1 * height)),
             (int(x2 * width), int(y2 * height)), (0, 255, 255), 2)
    for track in tracks:
        if not track.observed:
            continue
        bx1, by1, bx2, by2 = map(int, track.bbox)
        stable_name, _ = track.stable_class(
            geometry.minimum_class_observations, geometry.class_stability_ratio
        )
        name = stable_name or track.class_name
        cv2.rectangle(frame, (bx1, by1), (bx2, by2), (0, 180, 255), 2)
        point = (track.centroid[0] / width, track.centroid[1] / height)
        track_status = crossing_detector.track_status(track.track_id, point)
        lines = (
            "#{} {} {:.2f}".format(track.track_id, name, track.confidence),
            "zone={} prev={}".format(track_status["current_zone"], track_status["previous_zone"]),
            track_status["event_status"],
        )
        for index, label in enumerate(lines):
            cv2.putText(frame, label, (bx1, max(18, by1 - 8) + index * 18),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 180, 255), 2,
                        cv2.LINE_AA)
    count_summary = "  ".join("{}:{}".format(name, count)
                              for name, count in sorted(counts.items()) if count)
    cv2.putText(frame, "Status: {}".format(status), (10, height - 35),
                cv2.FONT_HERSHEY_SIMPLEX, 0.58, (255, 255, 255), 2, cv2.LINE_AA)
    cv2.putText(frame, "Bag: {}".format(count_summary or "empty"), (10, height - 10),
                cv2.FONT_HERSHEY_SIMPLEX, 0.58, (255, 255, 255), 2, cv2.LINE_AA)
    fps_label = ("CAM {:.1f} | YOLO {:.1f} FPS".format(camera_fps, fps)
                 if camera_fps is not None else "YOLO {:.1f} FPS".format(fps))
    cv2.putText(frame, fps_label, (max(10, width - 360), 26),
                cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2, cv2.LINE_AA)


def _run_live_camera_preview(capture, detector, tracker, crossing_detector,
                             event_processor, geometry, products, requested_fps,
                             frame_width, frame_height):
    """Keep camera display smooth while inference consumes the newest frame."""
    lock = threading.Lock()
    stop = threading.Event()
    camera_state = {"frame": None, "sequence": 0, "fps": 0.0,
                    "done": False, "error": None}
    inference_state = {"tracks": [], "status": "Waiting for camera frames",
                       "fps": 0.0, "error": None}

    def capture_loop():
        previous = None
        try:
            while not stop.is_set():
                ok, frame = capture.read()
                if not ok or frame is None:
                    raise RuntimeError("camera frame capture failed")
                now = time.perf_counter()
                with lock:
                    if previous is not None and now > previous:
                        instant = 1.0 / (now - previous)
                        camera_state["fps"] = (
                            instant if camera_state["fps"] == 0
                            else 0.8 * camera_state["fps"] + 0.2 * instant
                        )
                    camera_state["frame"] = frame
                    camera_state["sequence"] += 1
                previous = now
        except Exception as exc:
            with lock:
                camera_state["error"] = exc
        finally:
            with lock:
                camera_state["done"] = True
            stop.set()

    def inference_loop():
        last_sequence = 0
        previous_inference = None
        try:
            while not stop.is_set():
                with lock:
                    sequence = camera_state["sequence"]
                    frame = camera_state["frame"]
                    if sequence == last_sequence or frame is None:
                        frame = None
                    else:
                        frame = frame.copy()
                if frame is None:
                    time.sleep(0.001)
                    continue

                detections = [d for d in detector.detect(frame)
                              if d.class_name in products]
                now = time.perf_counter()
                with lock:
                    tracks = tracker.update(
                        detections, frame_width, frame_height, timestamp=now
                    )
                    relevant = [track for track in tracks if geometry.in_scan_region(
                        (track.centroid[0] / frame_width,
                         track.centroid[1] / frame_height)
                    )]
                    crossing = crossing_detector.update(
                        tracks, expired_track_ids=tracker.expired_track_ids,
                        ambiguous=len(relevant) > 1,
                    )
                    status = crossing_detector.last_status
                    if crossing is not None:
                        event_processor.process(crossing)
                        status = event_processor.last_status
                        crossing_detector.set_track_event_status(crossing.track_id, status)
                    if previous_inference is not None and now > previous_inference:
                        instant = 1.0 / (now - previous_inference)
                        inference_state["fps"] = (
                            instant if inference_state["fps"] == 0
                            else 0.8 * inference_state["fps"] + 0.2 * instant
                        )
                    inference_state["tracks"] = tracks
                    inference_state["status"] = status
                previous_inference = now
                last_sequence = sequence
        except Exception as exc:
            with lock:
                inference_state["error"] = exc
            stop.set()

    cv2.namedWindow(PREVIEW_WINDOW, cv2.WINDOW_NORMAL | cv2.WINDOW_KEEPRATIO)
    frame_width = frame_width or 1280
    frame_height = frame_height or 720
    preview_width = min(1280, max(320, frame_width))
    preview_height = max(180, round(preview_width * frame_height / frame_width))
    cv2.resizeWindow(PREVIEW_WINDOW, preview_width, preview_height)
    try:
        cv2.setWindowProperty(PREVIEW_WINDOW, cv2.WND_PROP_TOPMOST, 1)
    except cv2.error:
        pass
    print("Live preview targets {} FPS; inference processes the latest frame independently. "
          "Press Q or Esc to quit.".format(requested_fps), flush=True)

    capture_thread = threading.Thread(target=capture_loop, name="smartcart-camera", daemon=True)
    inference_thread = threading.Thread(target=inference_loop, name="smartcart-yolo", daemon=True)
    capture_thread.start()
    inference_thread.start()
    actual_fps = capture.get(cv2.CAP_PROP_FPS)
    preview_fps = min(requested_fps, actual_fps) if actual_fps and actual_fps > 0 else requested_fps
    target_interval = 1.0 / preview_fps
    last_render = time.perf_counter()
    try:
        while not stop.is_set():
            loop_start = time.perf_counter()
            with lock:
                frame = camera_state["frame"]
                if frame is not None:
                    frame = frame.copy()
                tracks = list(inference_state["tracks"])
                status = inference_state["status"]
                camera_fps = camera_state["fps"]
                inference_fps = inference_state["fps"]
                finished = camera_state["done"]
                error = camera_state["error"] or inference_state["error"]
                if frame is not None:
                    _draw(frame, tracks, geometry, crossing_detector, status,
                          event_processor.counts, inference_fps, camera_fps)
            if error is not None:
                raise RuntimeError("live camera pipeline failed: {}".format(error)) from error
            if finished:
                break
            if frame is not None:
                cv2.imshow(PREVIEW_WINDOW, frame)
                if cv2.waitKey(1) & 0xFF in (ord("q"), 27):
                    break
            else:
                cv2.waitKey(1)
            elapsed = time.perf_counter() - loop_start
            if elapsed < target_interval:
                time.sleep(target_interval - elapsed)
            now = time.perf_counter()
            if now > last_render:
                last_render = now
    finally:
        stop.set()
        capture_thread.join(timeout=1.0)
        capture.release()
        capture_thread.join(timeout=1.0)
        inference_thread.join(timeout=1.0)
        cv2.destroyAllWindows()
    with lock:
        error = camera_state["error"] or inference_state["error"]
    if error is not None:
        raise RuntimeError("live camera pipeline failed: {}".format(error)) from error
    return event_processor


def run_scanner(detector, source, product_names, geometry: ScanGeometry, *,
                preview=False, headless=False, transport_mode="both",
                width=1280, height=720, requested_fps=30):
    products = set(product_names)
    tracker = ItemTracker(max_missed_seconds=geometry.max_missed_seconds,
                          min_iou=geometry.minimum_iou,
                          max_centroid_distance=geometry.maximum_centroid_distance,
                          centroid_smoothing=geometry.centroid_smoothing)
    crossing_detector = CrossingDetector(geometry, products)
    transport, _mock_receiver = make_transport(transport_mode)
    event_processor = EventProcessor(transport)
    connect_session_hooks(transport, event_processor)
    source = source_value(source)
    is_image = isinstance(source, str) and Path(source).suffix.lower() in IMAGE_SUFFIXES
    is_video_file = isinstance(source, str) and Path(source).is_file() and not is_image
    capture = None
    frame_width, frame_height = width, height
    previous_time = time.perf_counter()
    fps = 0.0
    try:
        if is_image:
            first_frame = cv2.imread(source)
            if first_frame is None:
                raise RuntimeError("could not read image {!r}".format(source))
            frames = iter((first_frame,))
        else:
            print("Opening camera/video source {!r}...".format(source), flush=True)
            capture = open_capture(source)
            frame_width, frame_height = configure_capture(
                capture, width=width, height=height, fps=requested_fps,
                is_camera=not is_video_file,
            )
            print("Camera/video source opened.", flush=True)

            def captured_frames():
                while True:
                    ok, frame = capture.read()
                    if not ok or frame is None:
                        if is_video_file:
                            return
                        raise RuntimeError("camera/video frame capture failed")
                    yield frame

            frames = captured_frames()

            if (preview and not headless and sys.platform == "win32"
                    and isinstance(source, int)
                    and not is_video_file):
                return _run_live_camera_preview(
                    capture, detector, tracker, crossing_detector, event_processor,
                    geometry, products, requested_fps, frame_width, frame_height,
                )

        if preview and not headless:
            cv2.namedWindow(PREVIEW_WINDOW, cv2.WINDOW_NORMAL | cv2.WINDOW_KEEPRATIO)
            if is_image:
                frame_height, frame_width = first_frame.shape[:2]
            aspect_ratio = frame_width / frame_height if frame_height else 16 / 9
            preview_width = min(1280, max(320, frame_width))
            preview_height = max(180, round(preview_width / aspect_ratio))
            cv2.resizeWindow(PREVIEW_WINDOW, preview_width, preview_height)
            try:
                cv2.setWindowProperty(PREVIEW_WINDOW, cv2.WND_PROP_TOPMOST, 1)
            except cv2.error as exc:
                print("Preview opened; could not set it above other windows: {}".format(exc))
            print("SmartCart preview opened. Press Q or Esc in the preview to quit.", flush=True)

        for frame in frames:
            frame_height, frame_width = frame.shape[:2]
            now = time.perf_counter()
            interval = now - previous_time
            previous_time = now
            if interval > 0:
                measured = 1.0 / interval
                fps = measured if fps == 0 else 0.8 * fps + 0.2 * measured
            detections = [d for d in detector.detect(frame) if d.class_name in products]
            tracks = tracker.update(detections, frame_width, frame_height, timestamp=now)
            relevant = [t for t in tracks if geometry.in_scan_region(
                (t.centroid[0] / frame_width, t.centroid[1] / frame_height)
            )]
            ambiguous = len(relevant) > 1
            crossing = crossing_detector.update(
                tracks, expired_track_ids=tracker.expired_track_ids, ambiguous=ambiguous
            )
            status = crossing_detector.last_status
            if crossing is not None:
                event = event_processor.process(crossing)
                status = event_processor.last_status
                crossing_detector.set_track_event_status(crossing.track_id, status)
            if preview and not headless:
                _draw(frame, tracks, geometry, crossing_detector, status,
                      event_processor.counts, fps)
                cv2.imshow(PREVIEW_WINDOW, frame)
                if cv2.waitKey(1) & 0xFF in (ord("q"), 27):
                    break
        return event_processor
    finally:
        if capture is not None:
            capture.release()
        if preview and not headless:
            cv2.destroyAllWindows()
        close_transport(transport)

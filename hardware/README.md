# SmartCart camera scanner

The Windows and Jetson scanners share the same Python-only box tracker, crossing state machine, local bag accounting, and event transport interface. Inference is supplied by Ultralytics YOLOv8n on Windows or the native TensorRT 10 detector on Jetson. No PyTorch dependency is used for tracking or crossing logic.

## Windows setup

Use Python 3.9 or newer (Python 3.10+ recommended). In Git Bash at the repository root, activate the existing Windows virtual environment and install the dependencies if needed:

```bash
source .venv/Scripts/activate
python -m pip install -r hardware/requirements.txt
```

The first model run downloads `yolov8n.pt` if it is not already in the repository root.

## Event-based bag crossing scanner

The event scanner tracks configured products from `data/detectable_products.json`. The top half of the full frame is OUTSIDE BAG and the bottom half is INSIDE BAG. It emits `ADD` on a confirmed top-to-bottom crossing and `REMOVE` on bottom-to-top. It confirms each side over multiple observations and applies a neutral hysteresis band around the boundary. It never infers removal from a missing detection. Tracks reconnect by IoU/centroid during continuous detection. After a missed detection or a slow inference interval, class, horizontal position, elapsed time, vertical motion, box size, and competing matches govern reacquisition for up to 1.8 seconds by default. Expiry never produces an event. Multiple tracked products in the scan region suspend crossing confirmation.

Run the Logitech webcam with the annotated preview:

```bash
./.venv/Scripts/python.exe -m hardware.main --source 1 --confidence 0.5 --preview
```

Run headless, or replay prerecorded footage through the same tracker:

```bash
./.venv/Scripts/python.exe -m hardware.main --source 1 --confidence 0.5 --headless
./.venv/Scripts/python.exe -m hardware.main --source data/grocery_clip.mp4 --confidence 0.5 --preview
```

The Windows scanner defaults to 640x480 at a requested 30 camera FPS. On camera source 1, the native YUY2 mode measured about 29.8 FPS; the scanner keeps that mode when it already matches the requested size. Override with `--width`, `--height`, and `--fps`; for another mode it attempts MJPG where supported. It prints the mode, codec, and backend reported by OpenCV. On Windows it also attempts to set supported camera zoom controls to their minimum. With the local window or HTTP preview enabled, camera capture runs independently from YOLO inference and always keeps the newest frame. The overlay reports measured **CAM** and **YOLO** FPS separately; tracking and events update at the YOLO rate. YOLO uses a 416-pixel input by default for quicker observations; use `--imgsz 640` when small products need more detail. The preview displays the complete uncropped frame at its original aspect ratio; higher resolution improves detail but does not change the lens's physical field of view.

Press Q or Esc to stop a preview; Ctrl+C stops headless mode. Events print as JSON lines and are also delivered to an in-process mock receiver by default. Use `--transport console` or `--transport mock` to select one. The preview labels each box with its stable product class, track ID, confidence, current zone, previous stable zone, and event status. It also shows the outside/inside zones, crossing boundary, FPS, global event status, and current bag counts.

### Product and geometry configuration

Edit `data/detectable_products.json` to select exact COCO class names. The scanner validates every name against the loaded model mapping. No product whitelist is embedded in the tracking code.

Edit `data/scan_geometry.json` to adjust normalized `x1`, `y1`, `x2`, `y2` zone rectangles and the boundary segment. Coordinates range from 0 to 1 relative to frame width and height. Defaults define OUTSIDE BAG as y=0.0-0.5, INSIDE BAG as y=0.5-1.0, and the horizontal boundary at y=0.5; `zone_margin` creates the neutral hysteresis gap around that line. Set `add_direction` to `outside_to_inside` (default) or `inside_to_outside` if the camera view is mirrored or the entry direction is reversed; the opposite direction emits REMOVE. `--confidence` overrides the geometry file's confidence threshold. Restart the scanner after editing either JSON file.

### Occlusion and crossing tuning

The tracker retains timestamped normalized box centers, recent movement, class observations, and box size. A detection can reclaim that track without box overlap if its class agrees and the elapsed time, horizontal offset, vertical speed, and box size are plausible. This also works when inference skips ahead without producing an explicit empty-detection frame. Moderate perspective growth or shrinkage is allowed; similar competing matches are rejected. Crossing zones use the current measured box center, while a short median history reduces noise. A crossing requires two clear observations in the destination zone, a previously confirmed source zone, the correct sign of vertical movement (positive Y for ADD), at least `minimum_crossing_distance` of **vertical** travel, and intersection with the configured boundary. A missing object alone never produces an event. If the object is entirely invisible inside the bag, the camera cannot confirm the destination zone; it cannot prove identity when two nearly identical objects overlap or exchange places either.

The starting values in `data/scan_geometry.json` are experimental. All distances below use normalized frame coordinates:

| Setting | Meaning | Starting value |
| --- | --- | ---: |
| `confidence_threshold` | Minimum model detection confidence | `0.40` |
| `max_missed_seconds` | Time before a missing track expires | `1.8` |
| `zone_margin` | Neutral band around the divider | `0.02` |
| `zone_observations` | Consecutive clear observations to confirm a zone | `2` |
| `minimum_crossing_distance` | Minimum signed vertical travel magnitude | `0.12` |
| `maximum_centroid_distance` | Nearby match radius between consecutive observations | `0.14` |
| `maximum_reacquisition_distance` | Maximum vertical jump after a gap | `0.65` |
| `maximum_reacquisition_horizontal_distance` | Maximum horizontal offset after a gap | `0.15` |
| `maximum_vertical_speed` | Maximum plausible vertical travel per second | `1.5` |
| `maximum_box_size_ratio` | Largest allowed width or height scale change | `3.0` |
| `reassociation_min_confidence` | Minimum composite match score, 0 to 1 | `0.55` |
| `association_ambiguity_margin` | Reject competing matches whose costs differ by no more than this | `0.12` |

The loader supplies backward compatible defaults when these new keys are absent. For diagnostic track IDs, Y movement, elapsed time, association decisions, zone changes, and event decisions, add `--tracking-debug` to either scanner command. These timestamps and measured positions provide the proposed delta-Y snapshots without saving full camera images. Normal runs do not print tracking diagnostics. A new BLE shopping session or session stop queues a reset of active track and crossing history on the scanner loop; it does not change the BLE protocol.

### Recognition-only comparison mode

`test_live_yolo.py` remains available to compare raw product recognition without crossing logic:

```bash
./.venv/Scripts/python.exe hardware/test_live_yolo.py --camera 1 --conf 0.4
```

It uses the same product JSON and prints names as they appear/disappear. The resizable preview is raised above other windows at startup. An alternate list can be passed with `--products-config data/other-products.json`.

### Chrome camera preview on Windows

The recognition test can serve its annotated camera frames over HTTP. From Git Bash at the repository root, use camera index 1 (also accepted as `--source 1`):

```bash
./.venv/Scripts/python.exe hardware/test_live_yolo.py --camera 1 --conf 0.5 --fps 30 --imgsz 416 --http-port 8080 --no-window
```

Open `http://127.0.0.1:8080/` in Chrome on the same laptop. The page shows the product boxes, labels, confidence scores, and processing FPS. `--no-window` skips the OpenCV window; omit it to keep both previews. Press Ctrl+C in Git Bash to stop browser-only mode.

The crossing scanner can serve its existing zone, track ID, event status, bag count, and FPS annotations in the same way:

```bash
./.venv/Scripts/python.exe -m hardware.main --source 1 --confidence 0.5 --width 640 --height 480 --fps 30 --imgsz 416 --headless --http-port 8080
```

Only one of these commands can use port 8080 at a time. Both use the camera already opened by their scanner, with a latest-frame MJPEG stream and no additional Python packages. The crossing scanner updates browser video at the camera rate and redraws the newest YOLO tracking result; its ADD/REMOVE decisions update at YOLO FPS. The recognition-only test updates the browser image after each model inference. By default, the HTTP server listens on `127.0.0.1` for the laptop's own Chrome browser. To view from another device on the local network, add `--http-host 0.0.0.0` and open `http://<LAPTOP_IP>:8080/` there.

## Tests

Run all hardware unit tests from Git Bash at the repository root:

```bash
./.venv/Scripts/python.exe -m unittest discover -s hardware/tests -v
```

Tests cover tracking association, 0.5- and 1.5-second dropouts and expiry, a slow inference interval without an empty-detection frame, box growth/shrinkage during occluded entry/exit, class stability, stationary products, top-to-bottom ADD and bottom-to-top REMOVE crossings, jitter at the center boundary, missing detections, repeated identical products, unsupported classes, ambiguity suppression, shopping-session reset, and rejecting REMOVE when no matching item is in the local bag. Crossing behavior is checked at 640x480, 1280x720, and 1920x1080 to confirm normalized geometry. No camera, model weights, GPU, or Bluetooth is needed for these unit tests.

For physical validation, run the webcam command above, present a whitelisted object well inside the OUTSIDE BAG top zone, move it downward fully through the yellow center boundary, and hold it in the INSIDE BAG bottom zone. Move it upward to test REMOVE. Repeat with a brief occlusion, stationary placement, and two objects near the opening. Video replay uses `--source path/to/clip.mp4`; tune `scan_geometry.json` to match the camera framing before relying on events.

## Events and transport

The current event fields are `event_id`, `action`, `product_id`, `confidence`, and `timestamp`, for example:

```json
{"event_id":"sessiona1b2c3d4e5-17","action":"ADD","product_id":"apple","confidence":0.93,"timestamp":"2026-10-24T18:30:00Z"}
```

The session-scoped sequence makes IDs unique across events and sessions. Local bag counts increase on accepted ADD and decrease on accepted REMOVE; a REMOVE with no recorded item is rejected. `hardware/transport.py` defines the `send(event)` interface with console, mock, and composite transports; `--transport ble` adds Bluetooth (below). Coordinate this event contract with the teammate who owns the app before connecting it; this work does not modify app or backend code.

### Bluetooth to the Bag It app (`--transport ble`)

Implemented in `hardware/smartcart_ble/` against the shared contract `hardware/smartcart_ble/SMARTCART-BLE.md` (v1.2): the Jetson is a BLE peripheral advertising service `7ccd0001-d416-4afc-a97d-f7c98e1afca9` as `SmartCart-XXXX`; the phone starts a session when it enters a store and receives each accepted ADD/REMOVE as `seq|A or R|label|confidence`. Needs `bluez`, `python3-dbus`, `python3-gi` (in the Jetson Linux image) and root or the `bluetooth` group.

```bash
bash hardware/smartcart_ble/tools/check_adapter.sh               # adapter / BlueZ / permission check
sudo python3 -m hardware.smartcart_ble.tools.fake_cart --demo    # cart without camera/YOLO, for app testing
sudo python3 -m hardware.main_tensorrt --transport ble+console   # real scanner over Bluetooth (+ JSON lines)
```

With `ble` the backend owns the cart: crossings while no shopping session is active are neither sent nor counted, a new session clears the local bag counts, local counts follow only events that were sent, and a REMOVE is sent even when the local count is 0 (the backend answers `invalid_remove` if it has none). Bluetooth runs in a child process (restarted automatically if it dies), so `--preview` works too; when the cart starts it disconnects any phone that was already connected so the app reconnects cleanly. From a laptop, `python3 -m hardware.smartcart_ble.tools.test_central` (needs `pip install bleak`) plays the phone. Details, troubleshooting and the API: `hardware/smartcart_ble/README.md`.

## Jetson TensorRT deployment

Export the fixed 640x640 ONNX model on Windows after installing the requirements:

```bash
./.venv/Scripts/python.exe -m hardware.export_onnx --weights yolov8n.pt --output-dir hardware/models
```

Transfer the inference code, both configuration files, model and COCO mapping over SSH/SCP. This requires no Jetson internet access:

```powershell
.\hardware\deploy_jetson.ps1 -JetsonIp <JETSON_IP>
```

On the Jetson, from `/home/scott170/calhacks`, compile the engine with the installed TensorRT:

```bash
cd /home/scott170/calhacks
/usr/src/tensorrt/bin/trtexec --onnx=hardware/models/yolov8n.onnx \
  --saveEngine=hardware/models/yolov8n_fp16.engine --fp16 --shapes=images:1x3x640x640
```

Then run the same crossing logic headless on the Logitech camera:

```bash
cd /home/scott170/calhacks
python3 -m hardware.main_tensorrt --source /dev/video0 \
  --engine hardware/models/yolov8n_fp16.engine --confidence 0.5
```

For this tracking update, copy only the changed scanner modules and geometry from Windows Git Bash. Do this after comparing the Jetson copies if it has local changes:

```bash
scp hardware/item_tracker.py hardware/crossing.py hardware/scan_geometry.py hardware/scanner.py hardware/main_tensorrt.py scott170@192.168.55.1:/home/scott170/calhacks/hardware/
scp hardware/tests/test_hybrid_tracking.py scott170@192.168.55.1:/home/scott170/calhacks/hardware/tests/
scp data/scan_geometry.json scott170@192.168.55.1:/home/scott170/calhacks/data/
```

Then on the Jetson:

```bash
cd /home/scott170/calhacks
/usr/bin/python3 -m unittest discover -s hardware/tests -v
/usr/bin/python3 -m hardware.main_tensorrt --source /dev/video0 --engine hardware/models/yolov8n_fp16.engine --transport ble+console --tracking-debug
```

`--tracking-debug` is optional. The default headless command remains available without it. No model export or engine rebuild is needed for the tracking update.

The Jetson runtime expects its existing Python 3, NumPy, OpenCV, TensorRT 10 Python bindings, and CUDA runtime library. It does not reinstall or modify CUDA, TensorRT, PyTorch, system Python, or NVIDIA drivers. The runtime decodes all model classes, then applies the shared JSON whitelist before tracking. Tracking and crossing depend only on Python and do not import PyTorch.

## Validation status

Automated tests are hardware-independent. Webcam preview, real YOLO inference, ONNX export, SCP transfer, TensorRT engine compilation, and Jetson camera operation require manual execution on the Windows laptop or physical Jetson and are not claimed as run by this implementation.

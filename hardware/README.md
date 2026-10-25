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

The event scanner tracks configured products from `data/detectable_products.json`. The top half of the full frame is OUTSIDE BAG and the bottom half is INSIDE BAG. It emits `ADD` on a confirmed top-to-bottom crossing and `REMOVE` on bottom-to-top. It confirms each side over multiple observations, applies configurable centroid smoothing and a neutral hysteresis band around the boundary, and does not infer removal from a missing detection. Tracks reconnect by IoU/centroid for up to 0.75 seconds by default; expiry never produces an event. Multiple tracked products in the scan region suspend crossing confirmation.

Run the Logitech webcam with the annotated preview:

```bash
./.venv/Scripts/python.exe -m hardware.main --source 1 --confidence 0.5 --preview
```

Run headless, or replay prerecorded footage through the same tracker:

```bash
./.venv/Scripts/python.exe -m hardware.main --source 1 --confidence 0.5 --headless
./.venv/Scripts/python.exe -m hardware.main --source data/grocery_clip.mp4 --confidence 0.5 --preview
```

The scanner requests 640x360 at 30 FPS by default because this Logitech source delivers frames faster at that mode than at 1280x720. Override the request with `--width`, `--height`, and `--fps`; unsupported settings leave the camera's current working mode in place. On Windows it attempts to set supported camera zoom controls to their minimum. During a live preview, camera capture/display refresh runs independently from YOLO inference so slow inference does not stall the webcam view. The overlay reports camera FPS and YOLO FPS separately; tracking and events update at the YOLO rate. The preview displays the complete uncropped frame at its original aspect ratio; higher resolution improves detail but does not change the lens's physical field of view. YOLO processes the full frame using its normal inference resizing.

Press Q or Esc to stop a preview; Ctrl+C stops headless mode. Events print as JSON lines and are also delivered to an in-process mock receiver by default. Use `--transport console` or `--transport mock` to select one. The preview labels each box with its stable product class, track ID, confidence, current zone, previous stable zone, and event status. It also shows the outside/inside zones, crossing boundary, FPS, global event status, and current bag counts.

### Product and geometry configuration

Edit `data/detectable_products.json` to select exact COCO class names. The scanner validates every name against the loaded model mapping. No product whitelist is embedded in the tracking code.

Edit `data/scan_geometry.json` to adjust normalized `x1`, `y1`, `x2`, `y2` zone rectangles and the boundary segment. Coordinates range from 0 to 1 relative to frame width and height. Defaults define OUTSIDE BAG as y=0.0-0.5, INSIDE BAG as y=0.5-1.0, and the horizontal boundary at y=0.5; `zone_margin` creates the neutral hysteresis gap around that line. Set `add_direction` to `outside_to_inside` (default) or `inside_to_outside` if the camera view is mirrored or the entry direction is reversed; the opposite direction emits REMOVE. The same file controls detection confidence, centroid smoothing, required observations, minimum travel distance, class stability, 0.75-second default track retention, and spatial matching tolerances. `--confidence` overrides the geometry file's confidence threshold. Restart the scanner after editing either JSON file.

### Recognition-only comparison mode

`test_live_yolo.py` remains available to compare raw product recognition without crossing logic:

```bash
./.venv/Scripts/python.exe hardware/test_live_yolo.py --camera 1 --conf 0.4
```

It uses the same product JSON and prints names as they appear/disappear. The resizable preview is raised above other windows at startup. An alternate list can be passed with `--products-config data/other-products.json`.

## Tests

Run all hardware unit tests from Git Bash at the repository root:

```bash
./.venv/Scripts/python.exe -m unittest discover -s hardware/tests -v
```

Tests cover tracking association, short dropouts and expiry, class stability, stationary products, top-to-bottom ADD and bottom-to-top REMOVE crossings, jitter at the center boundary, missing detections, repeated identical products, unsupported classes, ambiguity suppression, and rejecting REMOVE when no matching item is in the local bag. Crossing behavior is checked at 640x480, 1280x720, and 1920x1080 to confirm normalized geometry. No camera, model weights, GPU, or Bluetooth is needed for these unit tests.

For physical validation, run the webcam command above, present a whitelisted object well inside the OUTSIDE BAG top zone, move it downward fully through the yellow center boundary, and hold it in the INSIDE BAG bottom zone. Move it upward to test REMOVE. Repeat with a brief occlusion, stationary placement, and two objects near the opening. Video replay uses `--source path/to/clip.mp4`; tune `scan_geometry.json` to match the camera framing before relying on events.

## Events and transport

The current event fields are `event_id`, `action`, `product_id`, `confidence`, and `timestamp`, for example:

```json
{"event_id":"sessiona1b2c3d4e5-17","action":"ADD","product_id":"apple","confidence":0.93,"timestamp":"2026-10-24T18:30:00Z"}
```

The session-scoped sequence makes IDs unique across events and sessions. Local bag counts increase on accepted ADD and decrease on accepted REMOVE; a REMOVE with no recorded item is rejected. `hardware/transport.py` defines the `send(event)` interface with console, mock, and composite transports; `--transport ble` adds Bluetooth (below). Coordinate this event contract with the teammate who owns the app before connecting it; this work does not modify app or backend code.

### Bluetooth to the Bag It app (`--transport ble`)

Implemented in `hardware/smartcart_ble/` against the shared contract [`docs/SMARTCART-BLE.md`](../docs/SMARTCART-BLE.md) (v1.2): the Jetson is a BLE peripheral advertising service `7ccd0001-d416-4afc-a97d-f7c98e1afca9` as `SmartCart-XXXX`; the phone starts a session when it enters a store and receives each accepted ADD/REMOVE as `seq|A or R|label|confidence`. Needs `bluez`, `python3-dbus`, `python3-gi` (in the Jetson Linux image) and root or the `bluetooth` group.

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
  --saveEngine=hardware/models/yolov8n_fp16.engine --fp16
```

Then run the same crossing logic headless on the Logitech camera:

```bash
cd /home/scott170/calhacks
python3 -m hardware.main_tensorrt --source /dev/video0 \
  --engine hardware/models/yolov8n_fp16.engine --confidence 0.5
```

The Jetson runtime expects its existing Python 3, NumPy, OpenCV, TensorRT 10 Python bindings, and CUDA runtime library. It does not reinstall or modify CUDA, TensorRT, PyTorch, system Python, or NVIDIA drivers. The runtime decodes all model classes, then applies the shared JSON whitelist before tracking. Tracking and crossing depend only on Python and do not import PyTorch.

## Validation status

Automated tests are hardware-independent. Webcam preview, real YOLO inference, ONNX export, SCP transfer, TensorRT engine compilation, and Jetson camera operation require manual execution on the Windows laptop or physical Jetson and are not claimed as run by this implementation.

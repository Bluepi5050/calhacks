# SmartCart camera scanner

The scanner uses pretrained YOLOv8n COCO weights. It looks for `apple`, `banana`, and `orange` and prints one JSON `ADD` event after the same supported item is detected for several consecutive frames. A confirmed item cannot be counted again until the zone has been clear for the configured number of frames. Multiple confident objects, including unsupported COCO objects, are treated as ambiguous occupancy.

## Windows setup and run

Use Python 3.9 or newer (Python 3.10+ recommended). Run the commands that match your terminal; `Activate.ps1` is only for PowerShell.

### PowerShell

```powershell
py -3 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r hardware\requirements.txt
```

### Git Bash on Windows

In Git Bash, activate the Windows virtual environment with its Bash activation script:

```bash
py -3 -m venv .venv
source .venv/Scripts/activate
python -m pip install --upgrade pip
python -m pip install -r hardware/requirements.txt
```

If you are in WSL or another Linux environment, create a Linux virtual environment there instead. A Windows `.venv` cannot be activated or used from Linux:

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r hardware/requirements.txt
```

First run downloads the pretrained `yolov8n.pt` weights from Ultralytics. Examples:

```powershell
# Default webcam (0), with annotated preview
python -m hardware.main --source 0 --preview

# Video file, headless
python -m hardware.main --source .\data\groceries.mp4 --headless

# Still image; evaluates it repeatedly to apply the confirmation rule
python -m hardware.main --source .\data\apple.jpg --headless

# Tune threshold, confirmation, clear count, and central zone size
python -m hardware.main --source 0 --confidence 0.55 --confirm-frames 4 --clear-frames 8 `
  --zone-width 0.5 --zone-height 0.5 --preview
```

Press `q` or Esc to close preview; Ctrl+C stops headless mode. JSON events are printed one per line. `--zone-center-x` and `--zone-center-y` move the zone center; all zone coordinates and dimensions are frame fractions from 0 to 1. `hardware/test_camera.py` remains available as the pre-existing camera capture utility.

## Live YOLO webcam test with configurable products

Edit [`data/detectable_products.json`](../data/detectable_products.json) to choose exact class names from the pretrained model's COCO mapping. The default list is `apple`, `banana`, `orange`, `bottle`, `carrot`, and `broccoli`. Save the JSON and restart the scanner to apply changes. Product config paths are resolved from the repository root.

From Git Bash on Windows, at the repository root:

```bash
./.venv/Scripts/python.exe hardware/test_live_yolo.py --camera 0 --conf 0.4
```

`--products-config` can select another JSON file; a relative path is resolved from the repository root. For example:

```bash
./.venv/Scripts/python.exe hardware/test_live_yolo.py --camera 1 --conf 0.5 \
  --products-config data/seasonal_products.json
```

Change `--camera` for another webcam index. The preview shows each allowed detection's original model class name, class ID, confidence, box, and approximate FPS. The terminal prints a product name when it appears and can print it again after it disappears and returns. Press Q or Esc to stop.

## Tests

Tests use only Python's standard library and simulated frame detections:

```powershell
python -m unittest discover -s hardware\tests -v
```

The suite covers confirmation, no duplicate while visible, rearming after removal, unsupported and low confidence detections, ambiguous multiple objects, and zone boundaries. For a live Windows camera check, run the webcam command above and present/remove/re-present one fruit at a time. A real camera/model run requires OpenCV, Ultralytics, the downloaded weights, and an attached camera.

## Export the Jetson model

After installing the Windows requirements and confirming the scanner environment works:

```powershell
python -m hardware.export_onnx --weights yolov8n.pt --output-dir hardware\models
```

This writes fixed 640x640, batch-1, non-dynamic ONNX with built-in NMS disabled and `coco_classes.json`. `hardware/models/` and model artifacts are ignored by Git.

## Jetson deployment

The Jetson runtime expects its existing Python 3, NumPy, OpenCV (`cv2`), TensorRT 10 Python bindings, and CUDA runtime library (`libcudart`). It does not install or modify PyTorch, CUDA, TensorRT, system Python, or NVIDIA drivers. The TensorRT runtime code uses `ctypes` for CUDA memory management and adds no CUDA Python package dependency.

The deployment script transfers the ONNX model, class mapping, TensorRT scanner, shared tracker, and event module. It requires Windows OpenSSH `ssh`/`scp` and reachable Jetson networking:

```powershell
.\hardware\deploy_jetson.ps1 -JetsonIp <JETSON_IP>
```

On the Jetson, from `/home/scott170/calhacks`:

```bash
cd /home/scott170/calhacks
/usr/src/tensorrt/bin/trtexec \
  --onnx=hardware/models/yolov8n.onnx \
  --saveEngine=hardware/models/yolov8n_fp16.engine \
  --fp16 --shapes=images:1x3x640x640
```

Check the ONNX input name if `trtexec` reports that `images` is not found:

```bash
/usr/src/tensorrt/bin/trtexec --onnx=hardware/models/yolov8n.onnx --verbose
```

The Python runtime does not import PyTorch. `tensorrt_detector.py` inspects engine tensor names, shapes, and dtypes at startup, expects the fixed `[1,3,640,640]` input, handles the standard raw `[1,84,8400]` YOLOv8 output, letterboxes RGB input, normalizes to `[0,1]`, applies confidence filtering and class-aware NMS, and maps box coordinates back to the camera frame. It uses `libcudart` via Python `ctypes` for device allocation/copies, so neither PyCUDA nor cuda-python is needed. Inspect any installed optional memory bindings before adding packages:

```bash
python3 - <<'PY'
import importlib.util
for name in ("cuda", "cuda.cudart", "cuda.bindings.runtime", "pycuda", "tensorrt"):
    try:
        print(name, bool(importlib.util.find_spec(name)))
    except (ImportError, ModuleNotFoundError, ValueError):
        print(name, False)
PY
```

Run continuously with a Logitech camera in headless mode:

```bash
cd /home/scott170/calhacks
python3 -m hardware.main_tensorrt --source /dev/video0 --engine hardware/models/yolov8n_fp16.engine
```

Press Ctrl+C to stop. The camera is released and the JSON `ADD` line has the same fields as on Windows. If OpenCV cannot open `/dev/video0`, check `v4l2-ctl --list-devices`, permissions for `/dev/video0`, and whether another process has claimed it. If TensorRT fails to deserialize or reports a different output layout, inspect the startup tensor shape and `trtexec --verbose`; this implementation targets the standard single-output YOLOv8n ONNX export described above.

## Verification status

The code and test cases are prepared in the repository. Run the commands above on Windows and Jetson to validate the actual model, camera, ONNX export, and TensorRT engine. No physical Jetson or webcam run is claimed by this repository change.

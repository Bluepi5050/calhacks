"""Export the pretrained YOLOv8n COCO model as fixed-shape ONNX."""

import argparse
import json
import shutil
from pathlib import Path

from .coco import COCO_NAMES


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--weights", default="yolov8n.pt")
    parser.add_argument("--output-dir", type=Path, default=Path("hardware/models"))
    args = parser.parse_args()
    try:
        from ultralytics import YOLO
    except ImportError:
        parser.error("Install Ultralytics first: pip install -r hardware/requirements.txt")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    exported = YOLO(args.weights).export(format="onnx", imgsz=640, batch=1,
                                         dynamic=False, nms=False, opset=17)
    output = args.output_dir / "yolov8n.onnx"
    shutil.copy2(exported, output)
    (args.output_dir / "coco_classes.json").write_text(
        json.dumps({str(i): name for i, name in enumerate(COCO_NAMES)}, indent=2) + "\n",
        encoding="utf-8")
    print("ONNX model: {}".format(output.resolve()))
    print("COCO mapping: {}".format((args.output_dir / "coco_classes.json").resolve()))


if __name__ == "__main__":
    main()

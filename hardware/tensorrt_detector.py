"""TensorRT 10 inference using libcudart through ctypes (no PyTorch/PyCUDA)."""

import ctypes
import ctypes.util

import cv2
import numpy as np

from .coco import COCO_NAMES
from .item_tracker import Detection


class CudaRuntime:
    """Small synchronous CUDA runtime wrapper for memory allocation/copies."""
    def __init__(self):
        library = ctypes.util.find_library("cudart") or "libcudart.so"
        self.lib = ctypes.CDLL(library)
        self.lib.cudaMalloc.argtypes = [ctypes.POINTER(ctypes.c_void_p), ctypes.c_size_t]
        self.lib.cudaMalloc.restype = ctypes.c_int
        self.lib.cudaFree.argtypes = [ctypes.c_void_p]
        self.lib.cudaFree.restype = ctypes.c_int
        self.lib.cudaMemcpy.argtypes = [ctypes.c_void_p, ctypes.c_void_p,
                                       ctypes.c_size_t, ctypes.c_int]
        self.lib.cudaMemcpy.restype = ctypes.c_int
        self.lib.cudaDeviceSynchronize.argtypes = []
        self.lib.cudaDeviceSynchronize.restype = ctypes.c_int

    def check(self, code, operation):
        if code != 0:
            raise RuntimeError("{} failed with CUDA runtime error {}".format(operation, code))

    def allocate(self, size):
        pointer = ctypes.c_void_p()
        self.check(self.lib.cudaMalloc(ctypes.byref(pointer), size), "cudaMalloc")
        return pointer

    def copy_to_device(self, device, host, size):
        self.check(self.lib.cudaMemcpy(device, ctypes.c_void_p(host.ctypes.data), size, 1), "H2D copy")

    def copy_to_host(self, host, device, size):
        self.check(self.lib.cudaMemcpy(ctypes.c_void_p(host.ctypes.data), device, size, 2), "D2H copy")


def _nms(boxes, scores, class_ids, iou_threshold=0.45):
    keep = []
    for class_id in np.unique(class_ids):
        indices = np.where(class_ids == class_id)[0]
        indices = indices[np.argsort(scores[indices])[::-1]]
        while len(indices):
            current = indices[0]
            keep.append(current)
            if len(indices) == 1:
                break
            rest = indices[1:]
            x1 = np.maximum(boxes[current, 0], boxes[rest, 0])
            y1 = np.maximum(boxes[current, 1], boxes[rest, 1])
            x2 = np.minimum(boxes[current, 2], boxes[rest, 2])
            y2 = np.minimum(boxes[current, 3], boxes[rest, 3])
            intersection = np.maximum(0, x2 - x1) * np.maximum(0, y2 - y1)
            area_a = (boxes[current, 2] - boxes[current, 0]) * (boxes[current, 3] - boxes[current, 1])
            area_b = (boxes[rest, 2] - boxes[rest, 0]) * (boxes[rest, 3] - boxes[rest, 1])
            iou = intersection / np.maximum(area_a + area_b - intersection, 1e-8)
            indices = rest[iou <= iou_threshold]
    return keep


class TensorRTDetector:
    COCO_NAMES = COCO_NAMES

    def __init__(self, engine_path, confidence=0.5, iou_threshold=0.45):
        try:
            import tensorrt as trt
        except ImportError as exc:
            raise RuntimeError("TensorRT Python bindings are unavailable") from exc
        self.trt = trt
        self.confidence = confidence
        self.iou_threshold = iou_threshold
        self.cuda = CudaRuntime()
        self.logger = trt.Logger(trt.Logger.WARNING)
        with open(engine_path, "rb") as stream:
            self.runtime = trt.Runtime(self.logger)
            self.engine = self.runtime.deserialize_cuda_engine(stream.read())
        if self.engine is None:
            raise RuntimeError("could not deserialize TensorRT engine: {}".format(engine_path))
        self.context = self.engine.create_execution_context()
        self.input_name = next(self.engine.get_tensor_name(i) for i in range(self.engine.num_io_tensors)
                               if self.engine.get_tensor_mode(self.engine.get_tensor_name(i)) == trt.TensorIOMode.INPUT)
        self.output_names = [self.engine.get_tensor_name(i) for i in range(self.engine.num_io_tensors)
                             if self.engine.get_tensor_mode(self.engine.get_tensor_name(i)) == trt.TensorIOMode.OUTPUT]
        if len(self.output_names) != 1:
            raise RuntimeError("expected one YOLO output tensor; found {}".format(len(self.output_names)))
        if tuple(self.engine.get_tensor_shape(self.input_name)) != (1, 3, 640, 640):
            raise RuntimeError("expected fixed input [1,3,640,640], got {}".format(
                tuple(self.engine.get_tensor_shape(self.input_name))))
        self.input_dtype = trt.nptype(self.engine.get_tensor_dtype(self.input_name))
        self.input_shape = tuple(self.engine.get_tensor_shape(self.input_name))
        self.output_name = self.output_names[0]
        self.output_shape = tuple(self.engine.get_tensor_shape(self.output_name))
        self.output_dtype = trt.nptype(self.engine.get_tensor_dtype(self.output_name))
        self.input_host = np.empty(self.input_shape, dtype=self.input_dtype)
        self.output_host = np.empty(self.output_shape, dtype=self.output_dtype)
        self.input_device = self.cuda.allocate(self.input_host.nbytes)
        self.output_device = self.cuda.allocate(self.output_host.nbytes)
        self.context.set_tensor_address(self.input_name, int(self.input_device.value))
        self.context.set_tensor_address(self.output_name, int(self.output_device.value))
        print("TensorRT input {} {} output {} {}".format(self.input_name, self.input_shape,
                                                         self.output_name, self.output_shape))

    @staticmethod
    def _letterbox(frame):
        h, w = frame.shape[:2]
        scale = min(640 / w, 640 / h)
        nw, nh = int(round(w * scale)), int(round(h * scale))
        resized = cv2.resize(frame, (nw, nh), interpolation=cv2.INTER_LINEAR)
        left, top = (640 - nw) // 2, (640 - nh) // 2
        canvas = np.full((640, 640, 3), 114, dtype=np.uint8)
        canvas[top:top + nh, left:left + nw] = resized
        chw = canvas[:, :, ::-1].transpose(2, 0, 1).astype(np.float32) / 255.0
        return chw[None].astype(np.float16 if chw.dtype == np.float16 else np.float32), scale, left, top

    def detect(self, frame):
        image, scale, pad_x, pad_y = self._letterbox(frame)
        self.input_host[...] = image.astype(self.input_dtype)
        self.cuda.copy_to_device(self.input_device, self.input_host, self.input_host.nbytes)
        if not self.context.execute_async_v3(0):
            raise RuntimeError("TensorRT execute_async_v3 failed")
        self.cuda.check(self.cuda.lib.cudaDeviceSynchronize(), "cudaDeviceSynchronize")
        self.cuda.copy_to_host(self.output_host, self.output_device, self.output_host.nbytes)
        predictions = self.output_host.astype(np.float32)
        if predictions.ndim == 3:
            predictions = predictions[0]
        if predictions.shape[0] < predictions.shape[1] and predictions.shape[0] <= 128:
            predictions = predictions.T
        if predictions.shape[1] != 84:
            raise RuntimeError("unrecognized YOLO output shape {}".format(self.output_shape))
        scores_all = predictions[:, 4:]
        class_ids = np.argmax(scores_all, axis=1)
        scores = scores_all[np.arange(len(predictions)), class_ids]
        mask = scores >= self.confidence
        if not np.any(mask):
            return []
        rows, scores, class_ids = predictions[mask], scores[mask], class_ids[mask]
        xywh = rows[:, :4]
        boxes = np.column_stack((xywh[:, 0] - xywh[:, 2] / 2, xywh[:, 1] - xywh[:, 3] / 2,
                                 xywh[:, 0] + xywh[:, 2] / 2, xywh[:, 1] + xywh[:, 3] / 2))
        boxes[:, [0, 2]] = (boxes[:, [0, 2]] - pad_x) / scale
        boxes[:, [1, 3]] = (boxes[:, [1, 3]] - pad_y) / scale
        h, w = frame.shape[:2]
        boxes[:, [0, 2]] = boxes[:, [0, 2]].clip(0, w)
        boxes[:, [1, 3]] = boxes[:, [1, 3]].clip(0, h)
        selected = _nms(boxes, scores, class_ids, self.iou_threshold)
        return [Detection(self.COCO_NAMES[int(class_ids[i])], float(scores[i]),
                          tuple(float(v) for v in boxes[i])) for i in selected]

    def close(self):
        self.cuda.lib.cudaFree(self.input_device)
        self.cuda.lib.cudaFree(self.output_device)

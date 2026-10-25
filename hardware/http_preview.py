"""Optional browser preview for already annotated OpenCV frames.

The scanner owns the camera. This server keeps only the newest frame and JPEG,
so a slow browser cannot make inference wait or accumulate old video frames.
"""

import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import cv2


VIEWER_HTML = b"""<!doctype html>
<html lang="en">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>SmartCart camera</title>
<style>
  body { margin: 0; background: #111; color: #eee; font: 16px sans-serif; }
  main { max-width: 1280px; margin: auto; padding: 16px; }
  img { display: block; width: 100%; height: auto; background: #222; }
</style>
<main><h1>SmartCart camera</h1><img src="/stream.mjpg" alt="Live annotated camera"></main>
</html>
"""


class HttpPreview:
    def __init__(self, host="127.0.0.1", port=8080, jpeg_quality=80):
        self.host = host
        self.port = port
        self.jpeg_quality = jpeg_quality
        self._condition = threading.Condition()
        self._running = False
        self._viewers = 0
        self._latest_frame = None
        self._frame_sequence = 0
        self._jpeg = None
        self._jpeg_sequence = 0
        self._server = None
        self._server_thread = None
        self._encoder_thread = None

    def start(self):
        preview = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path == "/":
                    self.send_response(200)
                    self.send_header("Content-Type", "text/html; charset=utf-8")
                    self.send_header("Cache-Control", "no-store")
                    self.send_header("Content-Length", str(len(VIEWER_HTML)))
                    self.end_headers()
                    self.wfile.write(VIEWER_HTML)
                elif self.path == "/stream.mjpg":
                    self.send_response(200)
                    self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
                    self.send_header("Cache-Control", "no-store")
                    self.end_headers()
                    with preview._condition:
                        preview._viewers += 1
                        seen_sequence = preview._jpeg_sequence
                        preview._condition.notify_all()
                    try:
                        while True:
                            with preview._condition:
                                preview._condition.wait_for(
                                    lambda: not preview._running
                                    or preview._jpeg_sequence > seen_sequence
                                )
                                if not preview._running:
                                    break
                                jpeg = preview._jpeg
                                seen_sequence = preview._jpeg_sequence
                            self.wfile.write(
                                b"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "
                                + str(len(jpeg)).encode("ascii") + b"\r\n\r\n" + jpeg + b"\r\n"
                            )
                            self.wfile.flush()
                    except (BrokenPipeError, ConnectionResetError, OSError):
                        pass  # The browser closed or refreshed the page.
                    finally:
                        with preview._condition:
                            preview._viewers -= 1
                            preview._condition.notify_all()
                else:
                    self.send_error(404)

            def log_message(self, format, *args):
                pass

        self._server = ThreadingHTTPServer((self.host, self.port), Handler)
        self._server.daemon_threads = True
        with self._condition:
            self._running = True
        self._encoder_thread = threading.Thread(
            target=self._encode_loop, name="smartcart-http-jpeg", daemon=True
        )
        self._server_thread = threading.Thread(
            target=self._server.serve_forever, name="smartcart-http-server", daemon=True
        )
        self._encoder_thread.start()
        self._server_thread.start()
        print("Chrome preview: http://{}:{}/".format(self.host, self._server.server_port),
              flush=True)
        return self

    def publish(self, annotated_frame):
        """Return promptly; the encoder owns a copy only while a viewer is connected."""
        with self._condition:
            if not self._running or not self._viewers:
                return
            self._latest_frame = annotated_frame.copy()
            self._frame_sequence += 1
            self._condition.notify_all()

    def _encode_loop(self):
        last_sequence = 0
        while True:
            with self._condition:
                self._condition.wait_for(
                    lambda: not self._running
                    or (self._viewers and self._frame_sequence > last_sequence)
                )
                if not self._running:
                    return
                frame = self._latest_frame
                sequence = self._frame_sequence
            success, image = cv2.imencode(
                ".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, self.jpeg_quality]
            )
            with self._condition:
                last_sequence = sequence
                if success and self._running:
                    self._jpeg = image.tobytes()
                    self._jpeg_sequence = sequence
                    self._condition.notify_all()

    def close(self):
        if self._server is None:
            return
        with self._condition:
            self._running = False
            self._condition.notify_all()
        self._server.shutdown()
        self._server.server_close()
        self._server_thread.join(timeout=2)
        self._encoder_thread.join(timeout=2)
        self._server = None

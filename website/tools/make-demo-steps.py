#!/usr/bin/env python3
"""Stills for the Demo section's step row, cut from the recorded live trip.

Each step is one frame of the 1080p recording (the phone screen only, cropped out of the frame),
so the row shows exactly what the video below shows. Edit STEPS (time in seconds), then run
    python3 tools/make-demo-steps.py
Labels and lines live in index.html (section #demo); keep their order in step with STEPS.
Needs ffmpeg and Pillow. Not deployed (hidden by _redirects, not in the deploy ZIP).
"""
import os, subprocess, tempfile
from PIL import Image

SRC = "/workspace/smartbag/film/live-trip-1080.webm"   # 1920x1080 recording of the live trip (receipt FXEH7X)
CROP = (1196, 77, 1617, 982)                            # phone screen inside the bezel, in source pixels
STEPS = [("walk-in", 5.5), ("drop-it-in", 6.5), ("price", 10.5), ("walk-out", 12.5), ("receipt", 15.5)]
WIDTH = 420
QUALITY = 82

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "media", "steps")
os.makedirs(out, exist_ok=True)
for name, t in STEPS:
    with tempfile.TemporaryDirectory() as d:
        png = os.path.join(d, "f.png")
        subprocess.run(["ffmpeg", "-v", "error", "-ss", str(t), "-i", SRC, "-frames:v", "1", png], check=True)
        im = Image.open(png).convert("RGB").crop(CROP)
    # the crop's corners still show the recording's black bezel; paint them in the screen colour
    # (the page frames each still with its own rounded corners)
    px, R = im.load(), 56
    for cx, cy in ((0, 0), (im.width - R, 0), (0, im.height - R), (im.width - R, im.height - R)):
        for y in range(cy, cy + R):
            for x in range(cx, cx + R):
                if sum(px[x, y]) < 600:
                    px[x, y] = (255, 255, 255)
    im = im.resize((WIDTH, round(im.height * WIDTH / im.width)), Image.LANCZOS)
    dst = os.path.join(out, f"trip-{name}.webp")
    im.save(dst, "WEBP", quality=QUALITY, method=6)
    print(f"trip-{name}.webp {im.size[0]}x{im.size[1]} {os.path.getsize(dst)//1024} KB  @ {t}s")
